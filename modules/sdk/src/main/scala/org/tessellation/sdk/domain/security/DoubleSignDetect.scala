package org.tessellation.sdk.domain.security

import org.tessellation.schema.SnapshotOrdinal
import org.tessellation.schema.peer.PeerId
import org.tessellation.sdk.domain.fork.ForkInfoMap

trait DoubleSignDetect[F[_]] {
  def detect(peerId: PeerId, forkMap: ForkInfoMap): Option[SnapshotOrdinal]
}
