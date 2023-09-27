package org.tessellation.sdk.infrastructure.snapshot

import cats.syntax.functor._
import cats.syntax.flatMap._
import cats.syntax.either._
import cats.syntax.traverse._
import cats.{Monad, Order}
import org.tessellation.schema.peer.PeerId
import org.tessellation.sdk.domain.snapshot.ProposalSelect
import org.tessellation.sdk.infrastructure.consensus.PeerDeclarations
import eu.timepit.refined.auto._
import eu.timepit.refined.types.numeric.PosDouble
import org.tessellation.sdk.domain.trust.storage.TrustStorage
import eu.timepit.refined.cats.refTypeOrder
import eu.timepit.refined.numeric.Positive
import eu.timepit.refined.refineV
import org.tessellation.sdk.config.types.ProposalSelectConfig
import org.tessellation.security.hash.Hash

import scala.util.control.NoStackTrace

object ProposalSelect {

  case class NoTrustScores() extends NoStackTrace

  def make[F[_]: Monad](
    maybeTrustStorage: Option[TrustStorage[F]],
    config: ProposalSelectConfig
  ): ProposalSelect[F] = (declarations: Map[PeerId, PeerDeclarations]) =>
    for {
      deterministic <- maybeTrustStorage.traverse { s =>
        s.getBiasedSeedlistOrdinalPeerLabels.map(_.getOrElse(Map.empty[PeerId, Double]).filter {
          case (peerId, s) => s > 0.0 && declarations.contains(peerId)
        })
      }.map(_.getOrElse(Map.empty[PeerId, Double]))
      relative <- maybeTrustStorage
        .traverse(
          _.getTrust.map(
            _.trust.view
              .mapValues(_.predictedTrust)
              .collect { case (peerId, Some(s)) if s > 0.0 && declarations.contains(peerId) => peerId -> s }
              .toMap
          )
        )
        .map(_.getOrElse(Map.empty[PeerId, Double]))

      scoredHashes = declarations.flatMap {
        case (peerId, declarations) =>
          declarations.proposal.map { p =>
            val d = deterministic.getOrElse(peerId, 0.0)
            val r = relative.getOrElse(peerId, 0.0)
            val score = if (r > d * config.trustMultiplier) r else d

            p.hash -> refineV[Positive](score)
          }.collect { case (h, Right(s)) => h -> s }
      }.toList.sortBy { case (_, s) => s }(Order[PosDouble].toOrdering.reverse)

      result =
        if (deterministic.isEmpty && relative.isEmpty)
          NoTrustScores().asLeft[List[(Hash, PosDouble)]]
        else
          scoredHashes.asRight[Throwable]
    } yield result

}
