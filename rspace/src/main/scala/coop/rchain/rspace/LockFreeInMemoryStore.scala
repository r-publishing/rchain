package coop.rchain.rspace

import cats.effect.Sync
import cats.implicits._
import com.typesafe.scalalogging.Logger
import coop.rchain.catscontrib.ski.kp

import scala.collection.concurrent.TrieMap
import coop.rchain.rspace.history.{Branch, ITrieStore}
import coop.rchain.rspace.internal._
import coop.rchain.rspace.util.canonicalize
import coop.rchain.shared.SeqOps.{dropIndex, removeFirst}
import kamon._
import scodec.Codec

import scala.util.control.NonFatal

/**
  * This implementation of Transaction exists only to satisfy the requirements of IStore.
  * Ideally this can be dropped after InMemoryStore is removed.
  */
class NoopTxn[S] extends InMemTransaction[S] {
  override def commit(): Unit                   = ()
  override def abort(): Unit                    = ()
  override def close(): Unit                    = ()
  override def readState[R](f: S => R): R       = ???
  override def writeState[R](f: S => (S, R)): R = ???
  override def name: String                     = "noop"
}

/**
  * This store is an optimized version of IStore.
  * It does not handle high level locking.
  *
  * It should be used with RSpace that solves high level locking (e.g. FineGrainedRSpace).
  */
@SuppressWarnings(Array("org.wartremover.warts.NonUnitStatements"))
class LockFreeInMemoryStore[F[_], T, C, P, A, K](
    val trieStore: ITrieStore[T, Blake2b256Hash, GNAT[C, P, A, K]],
    val trieBranch: Branch
)(implicit sc: Serialize[C], sp: Serialize[P], sa: Serialize[A], sk: Serialize[K], syncF: Sync[F])
    extends IStore[F, C, P, A, K]
    with CloseOps {

  protected[rspace] type Transaction = InMemTransaction[State[C, P, A, K]]

  private[this] def createTxnRead(): Transaction = {
    failIfClosed()
    new NoopTxn[State[C, P, A, K]]
  }

  private[this] def createTxnWrite(): Transaction = {
    failIfClosed()
    new NoopTxn[State[C, P, A, K]]
  }

  @SuppressWarnings(Array("org.wartremover.warts.Throw"))
  private[rspace] def withTxn[R](txn: Transaction)(f: Transaction => R): R =
    try {
      val ret: R = f(txn)
      txn.commit()
      ret
    } catch {
      case NonFatal(ex) =>
        ex.printStackTrace()
        throw ex
    } finally {
      txn.close()
      updateGauges()
    }

  private[rspace] def withReadTxnF[R](f: Transaction => R): F[R] =
    syncF.delay {
      withTxn(createTxnRead())(f)
    }

  def withWriteTxnF[R](f: Transaction => R): F[R] =
    syncF.delay {
      withTxn(createTxnWrite())(f)
    }

  override def close(): Unit = super.close()

  type TrieTransaction = T

  implicit private val codecC: Codec[C] = sc.toCodec
  implicit private val codecP: Codec[P] = sp.toCodec
  implicit private val codecA: Codec[A] = sa.toCodec
  implicit private val codecK: Codec[K] = sk.toCodec

  private val stateGNAT: TrieMap[Blake2b256Hash, GNAT[C, P, A, K]] =
    TrieMap[Blake2b256Hash, GNAT[C, P, A, K]]()
  private val stateJoin: TrieMap[C, Seq[Seq[C]]] = TrieMap[C, Seq[Seq[C]]]()

  private[this] val MetricsSource = RSpaceMetricsSource + ".lock-free-in-mem"
  private[this] val refine        = Map("path" -> "inmem")
  private[this] val entriesGauge  = Kamon.gauge(MetricsSource + ".entries").refine(refine)

  private[rspace] def updateGauges() =
    entriesGauge.set(stateGNAT.readOnlySnapshot.size.toLong)

  private[rspace] def hashChannels(channels: Seq[C]): Blake2b256Hash =
    StableHashProvider.hash(channels)(Serialize.fromCodec(codecC))

  override def withTrieTxn[R](txn: Transaction)(f: TrieTransaction => R): R =
    trieStore.withTxn(trieStore.createTxnWrite()) { ttxn =>
      f(ttxn)
    }

  private[rspace] def getChannels(txn: Transaction, key: Blake2b256Hash): Seq[C] =
    stateGNAT.get(key).map(_.channels).getOrElse(Seq.empty)

  private[rspace] def getData(txn: Transaction, channels: Seq[C]): Seq[Datum[A]] =
    stateGNAT.get(hashChannels(channels)).map(_.data).getOrElse(Seq.empty)

  private[this] def getMutableWaitingContinuation(
      txn: Transaction,
      channels: Seq[C]
  ): Seq[WaitingContinuation[P, K]] =
    stateGNAT
      .get(hashChannels(channels))
      .map(_.wks)
      .getOrElse(Seq.empty)

  private[rspace] def getWaitingContinuation(
      txn: Transaction,
      channels: Seq[C]
  ): Seq[WaitingContinuation[P, K]] =
    getMutableWaitingContinuation(txn, channels)
      .map { wk =>
        wk.copy(continuation = InMemoryStore.roundTrip(wk.continuation))
      }

  def getPatterns(txn: Transaction, channels: Seq[C]): Seq[Seq[P]] =
    getMutableWaitingContinuation(txn, channels).map(_.patterns)

  private[rspace] def getJoin(txn: Transaction, channel: C): Seq[Seq[C]] =
    stateJoin.getOrElse(channel, Seq.empty)

  private[rspace] def joinMap: Map[Blake2b256Hash, Seq[Seq[C]]] =
    stateJoin.map {
      case (k, v) => (Blake2b256Hash.create(Codec[C].encode(k).map(_.toByteArray).get), v)
    }.toMap

  private[rspace] def putDatum(txn: Transaction, channels: Seq[C], datum: Datum[A]): Unit = {
    val hash = hashChannels(channels)
    val v = stateGNAT
      .get(hash)
      .map { gnat =>
        gnat.copy(data = datum +: gnat.data)
      }
      .getOrElse(GNAT(channels = channels, data = Seq(datum), wks = Seq.empty))
    stateGNAT.put(hash, v)
    trieInsert(hash, v)
  }

  private[rspace] def putWaitingContinuation(
      txn: Transaction,
      channels: Seq[C],
      continuation: WaitingContinuation[P, K]
  ): Unit = {
    val hash = hashChannels(channels)
    val v = stateGNAT
      .get(hash)
      .map { gnat =>
        gnat.copy(wks = continuation +: gnat.wks)
      }
      .getOrElse(GNAT(channels = channels, data = Seq.empty, wks = Seq(continuation)))
    stateGNAT.put(hash, v)
    trieInsert(hash, v)
  }

  private[rspace] def addJoin(txn: Transaction, channel: C, channels: Seq[C]): Unit =
    stateJoin
      .get(channel) match {
      case Some(joins) if !joins.contains(channels) => stateJoin.put(channel, channels +: joins)
      case None                                     => stateJoin.put(channel, Seq(channels))
      case _                                        => ()
    }

  private[rspace] def removeDatum(txn: Transaction, channels: Seq[C], index: Int): Unit = {
    val hash = hashChannels(channels)
    stateGNAT
      .get(hash)
      .map { gnat =>
        gnat.copy(data = dropIndex(gnat.data, index))
      }
      .foreach { v =>
        if (!isOrphaned(v)) {
          stateGNAT.put(hash, v)
          trieInsert(hash, v)
        } else {
          stateGNAT.remove(hash)
          trieDelete(hash, v)
        }
      }
  }

  private[rspace] def removeWaitingContinuation(
      txn: Transaction,
      channels: Seq[C],
      index: Int
  ): Unit = {
    val hash = hashChannels(channels)
    stateGNAT
      .get(hash)
      .map { gnat =>
        gnat.copy(wks = dropIndex(gnat.wks, index))
      }
      .foreach { v =>
        if (!isOrphaned(v)) {
          stateGNAT.put(hash, v)
          trieInsert(hash, v)
        } else {
          stateGNAT.remove(hash)
          trieDelete(hash, v)
        }
      }
  }

  private[rspace] def removeJoin(txn: Transaction, channel: C, channels: Seq[C]): Unit = {
    val gnatOpt = stateGNAT.get(hashChannels(channels))
    if (gnatOpt.isEmpty || gnatOpt.get.wks.isEmpty) {
      stateJoin
        .get(channel)
        .map(removeFirst(_)(_ == channels))
        .filter(_.nonEmpty) match {
        case Some(value) => stateJoin.put(channel, value)
        case None        => stateJoin.remove(channel)
      }

    }
  }

  private[rspace] def clear(txn: Transaction): Unit = {
    stateJoin.clear()
    stateGNAT.clear()
  }

  def isEmpty: Boolean = stateGNAT.isEmpty && stateJoin.isEmpty

  def toMap: Map[Seq[C], Row[P, A, K]] =
    stateGNAT.readOnlySnapshot.map {
      case (_, GNAT(cs, data, wks)) => (cs, Row(data, wks))
    }.toMap

  private[this] def isOrphaned(gnat: GNAT[C, P, A, K]): Boolean =
    gnat.data.isEmpty && gnat.wks.isEmpty

  protected[this] val dataLogger: Logger = Logger("coop.rchain.rspace.datametrics")

  private def measure(value: TrieUpdate[C, P, A, K]): Unit =
    dataLogger.whenDebugEnabled {
      val maybeData = value match {
        case _ @TrieUpdate(_, operation, channelsHash, gnat) =>
          val hex     = channelsHash.bytes.toHex
          val data    = gnat.data
          val dataLen = Codec[Seq[Datum[A]]].encode(data).get.size
          val wks     = gnat.wks
          val wksLen  = Codec[Seq[WaitingContinuation[P, K]]].encode(wks).get.size
          val gnatLen = Codec[GNAT[C, P, A, K]].encode(gnat).get.size
          Some((hex, gnatLen, operation.toString, data.size, dataLen, wks.size, wksLen))
        case _ => None
      }
      maybeData.foreach {
        case (key, size, action, datumSize, datumLen, continuationSize, continuationLen) =>
          dataLogger.debug(
            s"$key;$size;$action;$datumSize;$datumLen;$continuationLen;$continuationSize"
          )
      }
    }

  protected def processTrieUpdate(update: TrieUpdate[C, P, A, K]): Unit = {
    measure(update)
    update match {
      case TrieUpdate(_, Insert, channelsHash, gnat) =>
        history.insert(trieStore, trieBranch, channelsHash, canonicalize(gnat))
      case TrieUpdate(_, Delete, channelsHash, gnat) =>
        history.delete(trieStore, trieBranch, channelsHash, canonicalize(gnat))
    }
  }

  private[rspace] def bulkInsert(
      txn: Transaction,
      gnats: Seq[(Blake2b256Hash, GNAT[C, P, A, K])]
  ): Unit =
    gnats.foreach {
      case (hash, gnat @ GNAT(channels, _, wks)) =>
        stateGNAT.put(hash, gnat)
        for {
          wk      <- wks
          channel <- channels
        } {
          addJoin(txn, channel, channels)
        }
    }

  private[rspace] def installWaitingContinuation(
      txn: Transaction,
      channels: Seq[C],
      continuation: WaitingContinuation[P, K]
  ): Unit = {
    val key  = hashChannels(channels)
    val gnat = GNAT[C, P, A, K](channels, Seq.empty, Seq(continuation))
    stateGNAT.put(key, gnat)
  }
}

object LockFreeInMemoryStore {

  def create[F[_], T, C, P, A, K](
      trieStore: ITrieStore[T, Blake2b256Hash, GNAT[C, P, A, K]],
      branch: Branch
  )(
      implicit sc: Serialize[C],
      sp: Serialize[P],
      sa: Serialize[A],
      sk: Serialize[K],
      syncF: Sync[F]
  ): IStore[F, C, P, A, K] =
    new LockFreeInMemoryStore[F, T, C, P, A, K](trieStore, branch)(sc, sp, sa, sk, syncF)
}
