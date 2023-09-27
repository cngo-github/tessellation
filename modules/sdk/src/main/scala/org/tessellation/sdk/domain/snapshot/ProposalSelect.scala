package org.tessellation.sdk.domain.snapshot

import org.tessellation.schema.peer.PeerId
import org.tessellation.sdk.infrastructure.consensus.PeerDeclarations
import org.tessellation.security.hash.Hash

import eu.timepit.refined.types.numeric.PosDouble

trait ProposalSelect[F[_]] {

  def score(declarations: Map[PeerId, PeerDeclarations]): F[Either[Throwable, List[(Hash, PosDouble)]]]

}
