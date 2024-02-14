package org.tessellation.dag.l0

import cats.effect._

import org.tessellation.dag.l0.infrastructure.snapshot.GlobalSnapshotArtifact
import org.tessellation.node.shared.domain.gossip.Gossip
import org.tessellation.node.shared.infrastructure.snapshot.GossipForkInfo
import org.tessellation.security.Hasher

object Main extends DagL0Application {

  override def mkGossipForkInfo(
    gossip: Gossip[IO]
  )(implicit H: Hasher[IO]): GossipForkInfo[IO, GlobalSnapshotArtifact] =
    GossipForkInfo.make[IO, GlobalSnapshotArtifact](gossip)

}
