package org.tessellation.dag.l0.inspect.attack.rollback

import cats.effect.Async

import org.tessellation.dag.l0.DagL0Application
import org.tessellation.dag.l0.infrastructure.snapshot.GlobalSnapshotArtifact
import org.tessellation.dag.l0.attack.doubleSign.{DoubleSignConfig, GossipDoubleSign}
import org.tessellation.kryo.KryoSerializer
import org.tessellation.node.shared.domain.gossip.Gossip
import org.tessellation.node.shared.infrastructure.snapshot.GossipForkInfo
import org.tessellation.security.{Hasher, SecurityProvider}

import eu.timepit.refined.auto._

object Main extends DagL0Application {

  private val doubleSignConfig = DoubleSignConfig(4L)

  override def mkGossipForkInfo(
    gossip: Gossip[IO]
  )(implicit SP: SecurityProvider[IO], K: KryoSerializer[IO], H: Hasher[IO]): GossipForkInfo[IO, GlobalSnapshotArtifact] =
    GossipDoubleSign.make(gossip, doubleSignConfig.offset)

}
