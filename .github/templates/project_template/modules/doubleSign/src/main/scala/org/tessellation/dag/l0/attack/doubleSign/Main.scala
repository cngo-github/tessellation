package org.tessellation.dag.l0.inspect.attack.doubleSign

import cats.effect.IO

import org.tessellation.dag.l0.DagL0Application
import org.tessellation.dag.l0.infrastructure.snapshot.GlobalSnapshotArtifact
import org.tessellation.node.shared.domain.gossip.Gossip
import org.tessellation.node.shared.infrastructure.snapshot.GossipForkInfo
import org.tessellation.security.Hasher

import eu.timepit.refined.auto._

object Main extends DagL0Application {

  private val doubleSignConfig = DoubleSignConfig(1L)

  override def mkGossipForkInfo(
    gossip: Gossip[IO]
  )(implicit H: Hasher[IO]): GossipForkInfo[IO, GlobalSnapshotArtifact] =
    GossipDoubleSign.make(gossip, doubleSignConfig.offset)

}
