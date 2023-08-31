package org.tessellation.sdk.domain.snapshot

import cats.data.NonEmptyList
import cats.effect.Async

import scala.util.control.NoStackTrace

import eu.timepit.refined.types.numeric.PosInt

trait SublistSelect {

  def getSublist[F[_]: Async, A](candidates: Map[A, Double], sampleSize: PosInt): F[Option[NonEmptyList[A]]]

}

object SublistSelect {

  case object NotPossible extends NoStackTrace

}
