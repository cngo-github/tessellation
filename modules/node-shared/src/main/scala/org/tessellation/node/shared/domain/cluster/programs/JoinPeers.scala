package org.tessellation.node.shared.domain.cluster.programs

import cats.MonadThrow
import cats.effect.Async
import cats.syntax.all._

import scala.util.control.NoStackTrace

import org.tessellation.node.shared.config.types.JoinPeersConfig
import org.tessellation.node.shared.domain.cluster.storage.ClusterStorage
import org.tessellation.node.shared.domain.seedlist.SeedlistEntry
import org.tessellation.node.shared.domain.seedlist.SeedlistEntry._

import eu.timepit.refined.auto._
import org.typelevel.log4cats.slf4j.Slf4jLogger
import retry.RetryPolicies.limitRetries
import retry.{RetryPolicy, Sleep, retryingOnSomeErrors}

trait JoinPeers[F[_]] {
  def join(peers: Set[SeedlistEntry]): F[Unit]
}

object JoinPeers {

  case object NoResponsivePeers extends NoStackTrace

  def make[F[_]: Async: Sleep](
    joining: Joining[F],
    clusterStorage: ClusterStorage[F],
    config: JoinPeersConfig
  ): JoinPeers[F] = new JoinPeers[F] {

    val logger = Slf4jLogger.getLogger[F]

    def retryPolicy: RetryPolicy[F] = limitRetries(config.maxRetries)

    def isWorthRetrying(err: Throwable): F[Boolean] = err match {
      case NoResponsivePeers => true.pure[F]
      case _                 => false.pure[F]
    }

    def join(peers: Set[SeedlistEntry]): F[Unit] = {
      val contexts = peers.map(toPeerToJoin).collect { case Some(p) => p }

      retryingOnSomeErrors(retryPolicy, isWorthRetrying, retry.noop[F, Throwable]) {
        contexts.toList.traverse { peer =>
          logger.info(s"Joining to peer ${peer.id}") >>
            joining.join(peer)
        }.attempt >>
          clusterStorage.getResponsivePeers.flatMap { r =>
            MonadThrow[F].raiseError(NoResponsivePeers).whenA(r.nonEmpty)
          }
      }
    }

  }

}
