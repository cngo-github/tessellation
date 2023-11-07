package org.tessellation.sdk.infrastructure.security

import cats.Monad

import scala.collection.MapView

import org.tessellation.schema.SnapshotOrdinal
import org.tessellation.schema.peer.PeerId
import org.tessellation.sdk.config.types.DoubleSignDetectConfig
import org.tessellation.sdk.domain.fork.{ForkInfo, ForkInfoEntries, ForkInfoMap}
import org.tessellation.sdk.domain.security.DoubleSignDetect
import org.tessellation.security.hash.Hash

import eu.timepit.refined.auto._
import cats.syntax.functor._
import cats.syntax.either._
import cats.syntax.flatMap._
object DoubleSignDetect {

  def make[F[_]: Monad](
    config: DoubleSignDetectConfig
  ): DoubleSignDetect[F] = new DoubleSignDetect[F] {
    def hasDoubleSign(data: Iterable[(Hash, Int)]): Boolean = {
      /*
      We want to make sure that we are only dealing with unique hashes and that the unique hashes
      have a receipt time difference less than or equal to a threshold.
       */
      val g: Map[Hash, Iterable[Int]] = data.groupMap { case (hash, _) => hash } { case (_, i) => i }.filter {
        case (_, idxs) => idxs.size > 1
      }

      val possibleDoubleSigns: MapView[Hash, Boolean] = g.view
        .mapValues(
          _.toSeq.sorted
            .foldLeft((false, 0)) {
              case ((accState, lastIdx), currIdx) =>
                val isDoubleSign = currIdx - lastIdx <= config.minDistance

                (accState && isDoubleSign, currIdx)
            }
            ._1
        )
        .filter { case (_, isDoubleSign) => isDoubleSign }

      possibleDoubleSigns.nonEmpty
    }
      /*
      We want to make sure that we are only dealing with unique hashes and that the unique hashes
      have a receipt time difference less than or equal to a threshold.
       */
      val g: Map[Hash, Iterable[Int]] = data.groupMap { case (hash, _) => hash } { case (_, i) => i }.filter {
        case (_, idxs) => idxs.size > 1
      }

      val possibleDoubleSigns: MapView[Hash, Boolean] = g.view
        .mapValues(
          _.toSeq.sorted
            .foldLeft((false, 0)) {
              case ((accState, lastIdx), currIdx) =>
                val isDoubleSign = currIdx - lastIdx <= config.minDistance

                (accState && isDoubleSign, currIdx)
            }
            ._1
        )
        .filter { case (_, isDoubleSign) => isDoubleSign }

      possibleDoubleSigns.nonEmpty
    }

    def detect2(peerId: PeerId, forkMap: ForkInfoMap): Option[SnapshotOrdinal] = {
      /*
      Intent:
      A double signing occurs when you you repeatedly have ForkInfos at the same ordinal, but
      with different hashes.

      - First, detect the ordinals with multiple hashes associated with them. That will only occur
      when there is a rollback or a double signing event.
      - Second, discriminate between rollbacks and double-signing events. This can be done by looking
      at the receipt times (index) for each ForkInfo. If the delta between the receipt times is
      less than a threshold, it may be a double-signing event.
       */
      val forks: Iterable[ForkInfo] = forkMap.forks.getOrElse(peerId, ForkInfoEntries(1)).getEntries

      val groupedWithIndex: MapView[SnapshotOrdinal, Iterable[(Hash, Int)]] =
        forks.zipWithIndex.groupMap {
          case (info, _) => info.ordinal
        } {
          case (info, i) => (info, i)
        }.view.mapValues(_.map {
          case (info, i) => (info.hash, i)
        })

      val filtered: MapView[SnapshotOrdinal, Boolean] =
        groupedWithIndex.mapValues { f =>
          hasDoubleSign(f)
        }.filter { case (_, b) => b }

      filtered.headOption.map { case (ordinal, _) => ordinal }
    }
    case class IndexedForkInfo(info: ForkInfo, idx: Int)

    def hasDoubleSign(hashMap: Map[Hash, Iterable[IndexedForkInfo]]): Boolean =
      hashMap.exists { case (_, infos) => hasDoubleSign(infos) }

    def hasDoubleSign(infos: Iterable[IndexedForkInfo]): Boolean =
      infos
        .toList
        .sortBy(_.idx)
        .tailRecM {
          case Nil | head :: Nil => false.asRight
          case a :: b :: _ if b.idx - a.idx <= config.minDistance => true.asRight
          case _ :: tail => tail.asLeft
        }


    def detect(peerId: PeerId, forkMap: ForkInfoMap): Option[PeerId] = {
      forkMap
        .forks
        .get(peerId)
        .flatMap { entries =>
          val byOrdinalAndHash: Iterable[Map[Hash, Iterable[IndexedForkInfo]]] =
            entries
              .getEntries
              .zipWithIndex
              .map { case (info, idx) => IndexedForkInfo(info, idx) }
              .groupBy(_.info.ordinal)
              .values
              .map(_.groupBy(_.info.hash))

          byOrdinalAndHash
            .find(hasDoubleSign)
            .as(peerId)
        }
    }

}
