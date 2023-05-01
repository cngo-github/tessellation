package org.tessellation.sdk.infrastructure.snapshot

import cats.data.NonEmptyList
import cats.effect.Async
import cats.effect.std.Random
import cats.syntax.applicative._
import cats.syntax.applicativeError._
import cats.syntax.eq._
import cats.syntax.flatMap._
import cats.syntax.functor._
import cats.syntax.list._

import scala.util.control.NoStackTrace

import org.tessellation.kryo.KryoSerializer
import org.tessellation.schema.node.NodeState.Ready
import org.tessellation.schema.peer.Peer
import org.tessellation.schema.peer.Peer.toP2PContext
import org.tessellation.sdk.domain.cluster.storage.ClusterStorage
import org.tessellation.sdk.domain.snapshot.PeerSelect
import org.tessellation.sdk.http.p2p.clients.L0GlobalSnapshotClient

import org.typelevel.log4cats.slf4j.Slf4jLogger

object MajorityPeerSelect {

  case object NoPeersToSelect extends NoStackTrace

  def make[F[_]: Async: KryoSerializer: Random](
    storage: ClusterStorage[F],
    snapshotClient: L0GlobalSnapshotClient[F]
  ): PeerSelect[F] = new PeerSelect[F] {

    val logger = Slf4jLogger.getLoggerFromClass[F](MajorityPeerSelect.getClass)

    def select: F[Peer] = storage.getResponsivePeers
      .map(_.filter(_.state === Ready))
      .flatMap(getPeerSublist)
      .flatMap { peers =>
        peers.toNel match {
          case Some(value) => value.pure[F]
          case None =>
            logger.error("No Ready peers were found to be selected.") >>
              NoPeersToSelect.raiseError[F, NonEmptyList[Peer]]
        }
      }
      .flatMap(filterPeerList)
      .map(_.toList)
      .flatMap(Random[F].elementOf)

    def filterPeerList(peers: NonEmptyList[Peer]): F[NonEmptyList[Peer]] =
      peers
        .traverse(snapshotClient.getLatestOrdinal(_))
        .map {
          _.groupBy(identity).maxBy { case (_, ordinals) => ordinals.size }
        }
        .flatMap {
          case (majorityOrdinal, _) =>
            logger.debug(s"The majority ordinal among ${peers.size} peers is $majorityOrdinal") >>
              peers.traverse(snapshotClient.get(majorityOrdinal).run(_).flatMap(_.toHashed.map(_.hash)))
        }
        .map(_.zip(peers))
        .map(_.groupMap { case (hash, _) => hash } { case (_, ps) => ps })
        .map(_.maxBy { case (_, peers) => peers.size })
        .flatMap {
          case (majorityHash, peerCandidates) =>
            logger.debug(
              s"The majority hash at the majority ordinal is $majorityHash. There are ${peerCandidates.size} peers with that hash"
            ) >>
              peerCandidates.pure[F]
        }

    def getPeerSublist(peers: Set[Peer]): F[List[Peer]] = {
      val maxSublistPercent = 0.25
      val maxSublistSize = Math.max((peers.size * maxSublistPercent).toInt, 1)

      logger.debug(s"${peers.size} peers have been discovered. Selecting at most $maxSublistSize of them to filter.") >>
        Random[F].nextIntBounded(maxSublistSize).flatMap { peerCount =>
          Random[F]
            .shuffleList(peers.toList)
            .map(_.take(peerCount))
        }
    }
  }
}
