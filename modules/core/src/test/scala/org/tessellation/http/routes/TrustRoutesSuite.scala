package org.tessellation.http.routes

import cats.effect._
import cats.effect.unsafe.implicits.global
import cats.syntax.option._

import scala.reflect.runtime.universe.TypeTag

import org.tessellation.coreKryoRegistrar
import org.tessellation.domain.cluster.programs.TrustPush
import org.tessellation.ext.kryo._
import org.tessellation.infrastructure.trust.storage.TrustStorage
import org.tessellation.kryo.KryoSerializer
import org.tessellation.schema.generators._
import org.tessellation.schema.trust.{PeerObservationAdjustmentUpdate, PeerObservationAdjustmentUpdateBatch, TrustScores}
import org.tessellation.sdk.config.types.TrustStorageConfig
import org.tessellation.sdk.domain.gossip.Gossip
import org.tessellation.sdk.domain.trust.storage.{TrustMap, TrustStorage}
import org.tessellation.sdk.sdkKryoRegistrar

import eu.timepit.refined.auto._
import io.circe.Encoder
import io.circe.syntax.EncoderOps
import org.http4s.Method._
import org.http4s._
import org.http4s.client.dsl.io._
import org.http4s.syntax.literals._
import suite.HttpSuite

object TrustRoutesSuite extends HttpSuite {

  def mkTrustStorage(trust: TrustMap = TrustMap.empty): F[TrustStorage[F]] = {
    val config = TrustStorageConfig(
      ordinalTrustUpdateInterval = 1000L,
      ordinalTrustUpdateDelay = 500L,
      seedlistInputBias = 0.7,
      seedlistOutputBias = 0.5
    )

    TrustStorage.make(trust, config, none)
  }

  test("GET trust succeeds") {
    val req = GET(uri"/trust")
    val peer = (for {
      peers <- peersGen()
    } yield peers.head).sample.get

    KryoSerializer
      .forAsync[IO](sdkKryoRegistrar.union(coreKryoRegistrar))
      .use { implicit kryoPool =>
        for {
          ts <- mkTrustStorage()
          gossip = new Gossip[IO] {
            override def spread[A: TypeTag: Encoder](rumorContent: A): IO[Unit] = IO.unit

            override def spreadCommon[A: TypeTag: Encoder](rumorContent: A): IO[Unit] = IO.unit
          }
          tp = TrustPush.make[IO](ts, gossip)
          _ <- ts.updateTrust(
            PeerObservationAdjustmentUpdateBatch(List(PeerObservationAdjustmentUpdate(peer.id, 0.5)))
          )
          routes = TrustRoutes[IO](ts, tp).p2pRoutes
        } yield expectHttpStatus(routes, req)(Status.Ok)
      }
      .unsafeRunSync()
  }

  test("GET trust latest succeeds") {
    val req = GET(uri"/trust/latest")
    val peer = (for {
      peers <- peersGen()
    } yield peers.head).sample.get

    KryoSerializer
      .forAsync[IO](sdkKryoRegistrar.union(coreKryoRegistrar))
      .use { implicit kryoPool =>
        for {
          ts <- mkTrustStorage()
          gossip = new Gossip[IO] {
            override def spread[A: TypeTag: Encoder](rumorContent: A): IO[Unit] = IO.unit

            override def spreadCommon[A: TypeTag: Encoder](rumorContent: A): IO[Unit] = IO.unit
          }
          tp = TrustPush.make[IO](ts, gossip)
          _ <- ts.updateTrust(
            PeerObservationAdjustmentUpdateBatch(List(PeerObservationAdjustmentUpdate(peer.id, 0.5)))
          )
          routes = TrustRoutes[IO](ts, tp).p2pRoutes
          test2 <- ts.getTrust.map(_.trust).map(_.view.mapValues(_.predictedTrust.getOrElse(0.0)).toMap)
          test = TrustScores(test2)
          res = test.asJson
          _ = println(res)
        } yield expectHttpBodyAndStatus(routes, req)(TrustScores(Map.empty), Status.Ok)
      }
      .unsafeRunSync()
  }
}
