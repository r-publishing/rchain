package coop.rchain.catscontrib

import cats.Monad

trait MonadTrans[F[_[_], _]] {

  def liftM[G[_]: Monad, A](a: G[A]): F[G, A]

  /** The [[Monad]] implied by this transformer. */
  implicit def apply[G[_]: Monad]: Monad[F[G, ?]]
}

object MonadTrans {
  def apply[F[_[_], _]](implicit F: MonadTrans[F]): MonadTrans[F] = F
}
