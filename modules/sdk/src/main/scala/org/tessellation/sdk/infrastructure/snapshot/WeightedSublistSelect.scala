package org.tessellation.sdk.infrastructure.snapshot

import cats.data.NonEmptyList
import cats.effect.Async
import cats.syntax.applicative._
import cats.syntax.applicativeError._
import cats.syntax.either._
import cats.syntax.flatMap._
import cats.syntax.functor._
import cats.syntax.list._
import cats.syntax.option._

import scala.util.Random

import org.tessellation.sdk.domain.snapshot.SublistSelect
import org.tessellation.sdk.domain.snapshot.SublistSelect.NotPossible

import eu.timepit.refined.types.numeric.PosInt

object WeightedSublistSelect {

  def make: SublistSelect = new SublistSelect {
    private def sample[F[_]: Async, A](distribution: Map[A, Double]): F[A] = {
      val chosenProbability = Random.nextDouble()
      type Agg = (Double, Map[A, Double], Option[A])
      // println(distribution)

      (0.0, distribution, none[A]).tailRecM {
        case (a, d, Some(l)) if d.isEmpty && a <= chosenProbability =>
          l.asRight[Agg].pure
        case (a, d, Some(l)) if d.isEmpty && a > chosenProbability =>
          NotPossible.raiseError[F, Either[Agg, A]]
        case (accProbability, d, _) if (accProbability + d.head._2) >= chosenProbability =>
          d.head._1.asRight[Agg].pure
        case (accProbability, d, _) =>
          (accProbability + d.head._2, d.tail, d.head._1.some).asLeft[A].pure
      }
    }

    def getSublist[F[_]: Async, A](candidates: Map[A, Double], sampleSize: PosInt): F[Option[NonEmptyList[A]]] = {
      val filteredCandidates = candidates.filter(_._2 > 0.0)
      val trustSum = filteredCandidates.foldLeft(0.0)(_ + _._2)

      if (filteredCandidates.size <= sampleSize.value)
        filteredCandidates.keySet.toList.toNel.pure
      else {
        val normalizedCandidates = filteredCandidates.view.mapValues(_ / trustSum).toMap

        type Result = List[A]
        type Agg = (Result, Map[A, Double])

        (List.empty[A], normalizedCandidates).tailRecM {
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
