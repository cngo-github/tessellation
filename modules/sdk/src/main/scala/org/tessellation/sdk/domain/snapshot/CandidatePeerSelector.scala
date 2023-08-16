package org.tessellation.sdk.domain.snapshot

import cats.data.NonEmptyList
import org.tessellation.schema.peer.PeerId

trait CandidatePeerSelector {

  def select(candidates: Map[PeerId, Double], sampleSize: Int): Option[NonEmptyList[PeerId]]

}
