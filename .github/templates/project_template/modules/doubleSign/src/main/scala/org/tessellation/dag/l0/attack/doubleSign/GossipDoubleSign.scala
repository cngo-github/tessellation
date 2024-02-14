package org.tessellation.dag.l0.inspect.attack.doubleSign

import cats.effect.Async
import cats.syntax.all._

import org.tessellation.ext.crypto._
import org.tessellation.node.shared.domain.fork.ForkInfo
import org.tessellation.node.shared.domain.gossip.Gossip
import org.tessellation.node.shared.infrastructure.snapshot.GossipForkInfo
import org.tessellation.schema.snapshot.Snapshot
import org.tessellation.security.hash.Hash
import org.tessellation.security.signature.Signed
import org.tessellation.security.Hasher

import eu.timepit.refined.types.numeric.NonNegLong
import io.circe.Encoder

object GossipDoubleSign {

  val fakeHashSuffix = "B5D54C39E66671C9731B9F471E585D8262CD4F54963F0C93082D8DCF334D4C78"

  def make[F[_]: Async: Hasher, Artifact <: Snapshot: Encoder](
    gossip: Gossip[F],
    offsetInterval: NonNegLong
  ): GossipForkInfo[F, Artifact] =
    (signed: Signed[Artifact]) =>
      signed.hash.flatMap { h =>
        gossip.spread(ForkInfo(signed.value.ordinal, h)) >>
          gossip.spread(
            ForkInfo(
              signed.value.ordinal.plus(offsetInterval),
              Hash(h.value + fakeHashSuffix)
            )
          )
      }

}
