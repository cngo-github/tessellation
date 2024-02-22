package org.tessellation.dag.l0.infrastructure.snapshot

import java.security.KeyPair

import cats.effect.kernel.Async
import cats.effect.std.{Random, Supervisor}

import org.tessellation.kryo.KryoSerializer
import org.tessellation.node.shared.config.types.SnapshotConfig
import org.tessellation.node.shared.domain.cluster.services.Session
import org.tessellation.node.shared.domain.cluster.storage.ClusterStorage
import org.tessellation.node.shared.domain.gossip.Gossip
import org.tessellation.node.shared.domain.node.NodeStorage
import org.tessellation.node.shared.domain.seedlist.SeedlistEntry
import org.tessellation.node.shared.infrastructure.consensus.Consensus
import org.tessellation.node.shared.infrastructure.metrics.Metrics
import org.tessellation.schema.peer.PeerId
import org.tessellation.security.{Hasher, SecurityProvider}

import io.circe.disjunctionCodecs._
import org.http4s.client.Client

object GlobalSnapshotConsensus {

  def make[F[_]: Async: Random: KryoSerializer: Hasher: SecurityProvider: Metrics: Supervisor](
    gossip: Gossip[F],
    selfId: PeerId,
    keyPair: KeyPair,
    seedlist: Option[Set[SeedlistEntry]],
    clusterStorage: ClusterStorage[F],
    nodeStorage: NodeStorage[F],
    snapshotConfig: SnapshotConfig,
    client: Client[F],
    session: Session[F],
    consensusFunctions: GlobalSnapshotConsensusFunctions[F]
  ): F[Consensus[F, GlobalSnapshotEvent, GlobalSnapshotKey, GlobalSnapshotArtifact, GlobalSnapshotContext]] =
    Consensus.make[F, GlobalSnapshotEvent, GlobalSnapshotKey, GlobalSnapshotArtifact, GlobalSnapshotContext](
      consensusFunctions,
      gossip,
      selfId,
      keyPair,
      snapshotConfig.consensus,
      seedlist,
      clusterStorage,
      nodeStorage,
      client,
      session
    )
}
