package org.tessellation.sdk.infrastructure.security.deamon

import cats.effect.std.Supervisor
import cats.effect.{Async, Ref, Temporal}
import cats.syntax.applicative._
import cats.syntax.eq._
import cats.syntax.flatMap._
import cats.syntax.functor._
import cats.{Applicative, Monad}

import scala.concurrent.duration.FiniteDuration

import org.tessellation.cli.AppEnvironment
import org.tessellation.cli.AppEnvironment.Dev
import org.tessellation.schema.node.NodeState
import org.tessellation.schema.node.NodeState.SessionStarted
import org.tessellation.sdk.domain.Daemon
import org.tessellation.sdk.domain.cluster.storage.ClusterStorage
import org.tessellation.sdk.domain.node.NodeStorage
import org.tessellation.sdk.domain.security.PeerDiscoveryDelay

object PeerDiscoveryDelayProgram {
  val maxRounds = 10
  val minRepeatedRounds = 5

  case class RoundInfo(joined: Int, seen: Int, round: Int)

  def make[F[_]: Monad: Ref.Make](
    clusterStorage: ClusterStorage[F],
    nodeStorage: NodeStorage[F],
    stateAfterJoining: NodeState,
    environment: AppEnvironment
  ): F[PeerDiscoveryDelay[F]] =
    Ref[F]
      .of[RoundInfo](RoundInfo(0, 0, 0))
      .map(make(clusterStorage, nodeStorage, stateAfterJoining, environment, _))

  def make[F[_]: Monad](
    clusterStorage: ClusterStorage[F],
    nodeStorage: NodeStorage[F],
    stateAfterJoining: NodeState,
    environment: AppEnvironment,
    roundsRef: Ref[F, RoundInfo]
  ): PeerDiscoveryDelay[F] =
    new PeerDiscoveryDelay[F] {
      def update: F[Unit] = clusterStorage.getResponsivePeers.flatMap { s =>
        roundsRef.update { info =>
          val seen = if (info.joined === s.size) info.seen + 1 else 1

          RoundInfo(s.size, seen, info.round)
        }
      }

      def canProceed: F[Boolean] = for {
        (rounds, seen) <- roundsRef.get.map(r => (r.round, r.seen))

        proceed = environment === Dev ||
          seen >= minRepeatedRounds ||
          rounds >= maxRounds
      } yield proceed

      def run: F[Boolean] = for {
        _ <- update
        proceed <- canProceed
        _ <- nodeStorage.tryModifyState(SessionStarted, stateAfterJoining).whenA(proceed)
      } yield proceed
    }

  def run[F[_]: Async: Temporal](
    peerDiscoveryDelay: PeerDiscoveryDelay[F],
    runInterval: FiniteDuration
  ): F[Unit] = Supervisor[F].use { implicit supervisor =>
    Daemon.spawn {
      Monad[F].whileM_(Applicative[F].pure(false)) {
        peerDiscoveryDelay.run.flatTap { proceed =>
          Temporal[F].sleep(runInterval).as(proceed)
        }
      }
    }.start
  }

}
