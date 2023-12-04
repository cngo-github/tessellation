package org.tessellation.sdk.domain.fork

import org.tessellation.schema.peer.PeerId

import derevo.cats.{eqv, show}
import derevo.derive

@derive(eqv, show)
case class ForkInfoMap(forks: Map[PeerId, ForkInfoEntries])

object ForkInfoMap {
  val empty: ForkInfoMap = ForkInfoMap(Map.empty)
}