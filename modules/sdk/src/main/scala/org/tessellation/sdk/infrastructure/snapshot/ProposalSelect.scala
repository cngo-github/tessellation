package org.tessellation.sdk.infrastructure.snapshot

import cats.syntax.functor._
import cats.{Monad, Order}

import org.tessellation.schema.peer.PeerId
import org.tessellation.schema.trust.{TrustScores, defaultPeerTrustScore}
import org.tessellation.sdk.domain.snapshot.ProposalSelect
import org.tessellation.sdk.infrastructure.consensus.PeerDeclarations

import eu.timepit.refined.api.Refined

object ProposalSelect {

  def make[F[_]: Monad](
    getCurrentTrust: F[TrustScores]
  ): ProposalSelect[F] = (declarations: Map[PeerId, PeerDeclarations]) =>
    getCurrentTrust.map(_.scores).map { trustScores =>
      declarations.map {
        case (peerId, declarations) =>
          declarations.proposal.map {
            _.hash -> trustScores.getOrElse(peerId, defaultPeerTrustScore.value)
          }.filter { case (_, s) => s > 0.0 }
      }.flatten.groupMapReduce { case (hash, _) => hash } { case (_, score) => score }(_ + _).toList.sortBy { case (_, score) => score }(
        Order[Double].toOrdering.reverse
      ).map { case (h, s) => h -> Refined.unsafeApply(s) }
    }
}
