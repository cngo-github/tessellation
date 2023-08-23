package org.tessellation.sdk.infrastructure.snapshot

import cats.data.NonEmptyList
import cats.effect.Async
import cats.syntax.applicative._
import cats.syntax.applicativeError._
import cats.syntax.either._
import cats.syntax.flatMap._
import cats.syntax.functor._
import cats.syntax.list._

import scala.util.Random
import scala.util.control.NoStackTrace

import org.tessellation.schema.peer.PeerId
import org.tessellation.sdk.domain.snapshot.CandidatePeerSelect

import eu.timepit.refined.types.numeric.PosInt

object WeightedCandidatePeerSelect {

  case object NotPossible extends NoStackTrace

  private def sample[F[_]: Async, A](distribution: Map[A, Double]): F[A] = {
    val chosenProbability = Random.nextDouble()
    type Agg = (Double, Map[A, Double])

    (0.0, distribution).tailRecM {
      case (_, d) if d.isEmpty => NotPossible.raiseError[F, Either[Agg, A]]
      case (accProbability, d) if (accProbability + d.head._2) >= chosenProbability =>
        d.head._1.asRight[Agg].pure
      case (accProbability, d) =>
        (accProbability + d.head._2, d.tail).asLeft[A].pure
    }
  }

  def make: CandidatePeerSelect = new CandidatePeerSelect {
    def select[F[_]: Async](candidates: Map[PeerId, Double], sampleSize: PosInt): F[Option[NonEmptyList[PeerId]]] = {
      val filteredCandidates = candidates.filter(_._2 > 0.0)
      val trustSum = filteredCandidates.foldLeft(0.0)(_ + _._2)

      if (filteredCandidates.size <= sampleSize.value)
        filteredCandidates.keySet.toList.toNel.pure
      else {
        val normalizedCandidates = filteredCandidates.view.mapValues(_ / trustSum).toMap

        type Result = List[PeerId]
        type Agg = (Result, Map[PeerId, Double])

        (List.empty[PeerId], normalizedCandidates).tailRecM {
          case (_, candidates) if candidates.isEmpty => NotPossible.raiseError[F, Either[Agg, Result]]
          case (selected, _) if selected.size == sampleSize.value =>
            selected.asRight[Agg].pure
          case (selected, candidates) =>
            sample(candidates).map { c =>
              (selected :+ c, candidates.removed(c)).asLeft[Result]
            }
        }.map(_.toNel)
      }
    }
  }

}
