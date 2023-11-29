package org.tessellation.sdk.domain.security

trait PeerDiscoveryDelay[F[_]] {

  def run: F[Boolean]

}
