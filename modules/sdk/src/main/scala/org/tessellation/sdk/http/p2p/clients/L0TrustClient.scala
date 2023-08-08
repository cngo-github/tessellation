package org.tessellation.sdk.http.p2p.clients

import cats.effect.Async

import org.tessellation.ext.codecs.BinaryCodec._
import org.tessellation.kryo.KryoSerializer
import org.tessellation.schema.peer.{L0Peer, PeerId}
import org.tessellation.sdk.domain.trust.storage.OrdinalTrustMap
import org.tessellation.sdk.http.p2p.PeerResponse
import org.tessellation.security.SecurityProvider

import org.http4s.client.Client

trait L0TrustClient[F[_]] {
  def getLatestTrust: F[Map[PeerId, Double]]
  def getLatestDeterministicTrust: F[OrdinalTrustMap]
}

object L0TrustClient {
  def make[F[_]: Async: SecurityProvider: KryoSerializer](client: Client[F], globalL0Peer: L0Peer): L0TrustClient[F] =
    new L0TrustClient[F] {

      def getLatestTrust: F[Map[PeerId, Double]] =
        PeerResponse[F, Map[PeerId, Double]]("trust/latest")(client).run(globalL0Peer)

      def getLatestDeterministicTrust: F[OrdinalTrustMap] =
        PeerResponse[F, OrdinalTrustMap]("trust/deterministic/latest")(client).run(globalL0Peer)
    }
}
