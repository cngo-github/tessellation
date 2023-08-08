package org.tessellation.http.routes

import cats.effect.Async
import cats.syntax.applicativeError._
import cats.syntax.flatMap._
import cats.syntax.functor._

import org.tessellation.domain.cluster.programs.TrustPush
import org.tessellation.ext.codecs.BinaryCodec._
import org.tessellation.kryo.KryoSerializer
import org.tessellation.schema.trust.{PeerObservationAdjustmentUpdateBatch, TrustScores}
import org.tessellation.sdk.domain.trust.storage.TrustStorage
import org.tessellation.sdk.ext.http4s.refined.RefinedRequestDecoder

import org.http4s._
import org.http4s.dsl.Http4sDsl
import org.http4s.server.Router

final case class TrustRoutes[F[_]: Async: KryoSerializer](
  trustStorage: TrustStorage[F],
  trustPush: TrustPush[F]
) extends Http4sDsl[F] {
  // import org.http4s.circe.CirceEntityCodec.circeEntityEncoder

  private[routes] val prefixPath = "/trust"

  private val p2p: HttpRoutes[F] = HttpRoutes.of[F] {
    case GET -> Root =>
      trustStorage.getPublicTrust.flatMap { publicTrust =>
        Ok(publicTrust)
      }

    case GET -> Root / "latest" =>
      trustStorage.getTrust
        .map(_.trust.view.mapValues(_.predictedTrust))
        .map(_.collect { case (k, Some(v)) => (k, v) }.toMap)
        .flatMap(test => Ok(TrustScores(test)))

    case GET -> Root / "deterministic" / "latest" =>
      trustStorage.getCurrentOrdinalTrust.flatMap(Ok(_))
  }

  private val cli: HttpRoutes[F] = HttpRoutes.of[F] {
    case req @ POST -> Root =>
      req.decodeR[PeerObservationAdjustmentUpdateBatch] { trustUpdates =>
        trustStorage
          .updateTrust(trustUpdates)
          .flatMap(_ => trustPush.publishUpdated())
          .flatMap(_ => Ok())
          .recoverWith {
            case _ =>
              Conflict(s"Internal trust update failure")
          }
      }
  }

  val p2pRoutes: HttpRoutes[F] = Router(
    prefixPath -> p2p
  )

  val cliRoutes: HttpRoutes[F] = Router(
    prefixPath -> cli
  )
}
