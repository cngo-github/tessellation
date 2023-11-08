package org.tessellation.sdk.infrastructure.security

import cats.Monad
import cats.syntax.either._
import cats.syntax.flatMap._
import cats.syntax.functor._
import cats.syntax.option._

import org.tessellation.schema.peer.PeerId
import org.tessellation.sdk.config.types.DoubleSignDetectConfig
import org.tessellation.sdk.domain.fork.{ForkInfo, ForkInfoMap}
import org.tessellation.sdk.domain.security.DoubleSignDetect
import org.tessellation.security.hash.Hash

object DoubleSignDetect {

  def make[F[_]: Monad](
    config: DoubleSignDetectConfig
  ): DoubleSignDetect[F] = new DoubleSignDetect[F] {
    case class IndexedForkInfo(info: ForkInfo, idx: Int)

    def hasDoubleSign(hashMap: Map[Hash, Iterable[IndexedForkInfo]]): Boolean =
      hashMap.exists { case (_, infos) => hasDoubleSign(infos) }

    def hasDoubleSign(infos: Iterable[IndexedForkInfo]): Boolean =
      infos.toList
        .sortBy(_.idx)
        .tailRecM[Option, Boolean] {
          case Nil | _ :: Nil                                           => false.asRight.some
          case a :: b :: _ if b.idx - a.idx <= config.minDistance.value => true.asRight.some
          case _ :: tail                                                => tail.asLeft.some
        }
        .contains(true)

    def detect(peerId: PeerId, forkMap: ForkInfoMap): Option[PeerId] =
      forkMap.forks
        .get(peerId)
        .flatMap { entries =>
          val byOrdinalAndHash: Iterable[Map[Hash, Iterable[IndexedForkInfo]]] =
            entries.getEntries.zipWithIndex.map { case (info, idx) => IndexedForkInfo(info, idx) }
              .groupBy(_.info.ordinal)
              .values
              .map(_.groupBy(_.info.hash))

          byOrdinalAndHash
            .find(hasDoubleSign)
            .as(peerId)
        }

  }
}
