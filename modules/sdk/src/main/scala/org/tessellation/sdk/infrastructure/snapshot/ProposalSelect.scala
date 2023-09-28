package org.tessellation.sdk.infrastructure.snapshot

import cats.data.NonEmptyList
import cats.syntax.all._
import cats.{Applicative, MonadThrow, Order}
import eu.timepit.refined.api.Refined
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
import org.tessellation.sdk.infrastructure.consensus.declaration.Proposal
import org.tessellation.security.hash
import org.tessellation.security.hash.Hash

import scala.util.control.NoStackTrace

object ProposalSelect {

  case object NoTrustScores extends NoStackTrace

  def make[F[_]: MonadThrow](
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

      _ <- MonadThrow[F].raiseError(NoTrustScores).whenA(deterministic.isEmpty && relative.isEmpty)
    } yield scoredHashes

  def makeDefault[F[_]: Applicative]: ProposalSelect[F] = (declarations: Map[PeerId, PeerDeclarations]) =>
    declarations.values
      .flatMap(_.proposal.map(_.hash))
      .toList
      .foldMap(a => Map(a -> 1.0))
      .view
      .mapValues(refineV[Positive](_))
      .collect { case (hash, Right(o)) => hash -> o }
      .toList
      .sortBy { case (_, o) => o }(Order[PosDouble].toOrdering.reverse)
      .pure

  def make[F[_]: MonadThrow](strategies: NonEmptyList[ProposalSelect[F]]): ProposalSelect[F] =
    (declarations: Map[PeerId, PeerDeclarations]) =>
      strategies.toList.tailRecM {
        case Nil => MonadThrow[F].raiseError(NoTrustScores)
        case strategy :: remainingStrategies =>
          strategy
            .score(declarations)
            .map(_.asRight[List[ProposalSelect[F]]])
            .handleErrorWith(_ => remainingStrategies.asLeft[List[(Hash, PosDouble)]].pure[F])
      }

  def makeRefactored[F[_]: MonadThrow](
    trustStorage: TrustStorage[F],
    config: ProposalSelectConfig
  ): ProposalSelect[F] = (declarations: Map[PeerId, PeerDeclarations]) =>
    for {
      maybeDeterministic <-
        trustStorage.getBiasedSeedlistOrdinalPeerLabels
          .map(_.map(_.filter { case (peerId, s) => s > 0.0 && declarations.contains(peerId) }))
          .map(_.filter(_.nonEmpty))

      maybeRelative <-
        trustStorage.getTrust
          .map(
            _.trust.view
              .mapValues(_.predictedTrust)
              .collect { case (peerId, Some(s)) if s > 0.0 && declarations.contains(peerId) => peerId -> s }
              .toMap
          )
          .map(m => Option.when(m.nonEmpty)(m))

      maybeScoredHashes = (maybeDeterministic, maybeRelative).mapN { (deterministic, relative) =>
        declarations.flatMap {
          case (peerId, pd) =>
            pd.proposal.map { p =>
              val d = deterministic.getOrElse(peerId, 0.0)
              val r = relative.getOrElse(peerId, 0.0)
              val score = if (r > d * config.trustMultiplier) r else d
              p.hash -> score
            }
              .filter(_._2 > 0.0)
        }.toList.sortBy { case (_, d) => d }(Ordering[Double].reverse).map { case (h, d) => h -> Refined.unsafeApply[Double, PosDouble](d) }
      }
        .filter(_.nonEmpty)

      scoredHashes <- MonadThrow[F].fromOption(maybeScoredHashes, NoTrustScores)
    } yield scoredHashes

}
