package org.tessellation.node.shared.infrastructure.snapshot

import cats.Eq
import cats.effect.Async
import cats.syntax.all._

import org.tessellation.ext.crypto._
import org.tessellation.node.shared.domain.fork.ForkInfo
import org.tessellation.node.shared.domain.gossip.Gossip
import org.tessellation.schema.snapshot.Snapshot
import org.tessellation.security.Hasher
import org.tessellation.security.signature.Signed

import io.circe.Encoder

trait GossipForkInfo[F[_], Artifact <: Snapshot] {
  def gossip(signed: Signed[Artifact]): F[Unit]
}

object GossipForkInfo {
  def make[F[_]: Async: Hasher, Artifact <: Snapshot: Eq: Encoder](
    gossip: Gossip[F]
  ): GossipForkInfo[F, Artifact] =
    (signed: Signed[Artifact]) => signed.hash.flatMap(h => gossip.spread(ForkInfo(signed.value.ordinal, h)))
}
