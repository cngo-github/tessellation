package org.tessellation.sdk.domain.snapshot

import cats.data.NonEmptyList
import cats.effect.Async

import org.tessellation.schema.peer.PeerId

import eu.timepit.refined.types.numeric.PosInt

trait CandidatePeerSelect {

  def select[F[_]: Async](candidates: Map[PeerId, Double], sampleSize: PosInt): F[Option[NonEmptyList[PeerId]]]

}
