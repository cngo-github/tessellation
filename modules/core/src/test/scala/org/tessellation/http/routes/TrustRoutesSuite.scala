package org.tessellation.http.routes

import cats.data.OptionT
import cats.effect._
import cats.syntax.eq._
import cats.syntax.option._

import scala.reflect.runtime.universe.TypeTag

import org.tessellation.domain.cluster.programs.TrustPush
import org.tessellation.ext.codecs.BinaryCodec._
import org.tessellation.infrastructure.trust.storage.TrustStorage
import org.tessellation.kryo.KryoSerializer
import org.tessellation.schema.generators._
import org.tessellation.schema.trust.{PeerObservationAdjustmentUpdate, PeerObservationAdjustmentUpdateBatch, TrustScores}
import org.tessellation.sdk.config.types.TrustStorageConfig
import org.tessellation.sdk.domain.gossip.Gossip
import org.tessellation.sdk.domain.trust.storage.{TrustMap, TrustStorage}
import org.tessellation.sdk.sdkKryoRegistrar

import eu.timepit.refined.auto._
import io.circe.{Encoder => CirceEncoder}
import org.http4s.Method._
import org.http4s._
import org.http4s.client.dsl.io._
import org.http4s.syntax.literals._
import suite.HttpSuite
import weaver.Expectations

object TrustRoutesSuite extends HttpSuite {

  def mkTrustStorage(trust: TrustMap = TrustMap.empty): IO[TrustStorage[IO]] = {
    val config = TrustStorageConfig(
      ordinalTrustUpdateInterval = 1000L,
      ordinalTrustUpdateDelay = 500L,
      seedlistInputBias = 0.7,
      seedlistOutputBias = 0.5
    )

    TrustStorage.make[IO](trust, config, none)
  }

  def mockGossip: Gossip[IO] = new Gossip[IO] {
    override def spread[A: TypeTag: CirceEncoder](rumorContent: A): IO[Unit] = IO.unit

    override def spreadCommon[A: TypeTag: CirceEncoder](rumorContent: A): IO[Unit] = IO.unit
  }

  test("GET trust succeeds") {
    val req = GET(uri"/trust")

    KryoSerializer
      .forAsync[IO](sdkKryoRegistrar)
      .use { implicit kryoPool =>
        forall(peerGen) { peer =>
          for {
            ts <- mkTrustStorage()
            tp = TrustPush.make[IO](ts, mockGossip)
            _ <- ts.updateTrust(
              PeerObservationAdjustmentUpdateBatch(List(PeerObservationAdjustmentUpdate(peer.id, 0.5)))
            )
            routes = TrustRoutes[IO](ts, tp).p2pRoutes
            result <- expectHttpStatus(routes, req)(Status.Ok)
          } yield result
        }
      }
  }

  test("GET trust latest succeeds") {
    val req = GET(uri"/trust/latest")

    KryoSerializer
      .forAsync[IO](sdkKryoRegistrar)
      .use { implicit kryoPool =>
        forall(peerGen) { peer =>
          for {
            ts <- mkTrustStorage()
            tp = TrustPush.make[IO](ts, mockGossip)
            _ <- ts.updateTrust(
              PeerObservationAdjustmentUpdateBatch(List(PeerObservationAdjustmentUpdate(peer.id, 0.5)))
            )
            routes = TrustRoutes[IO](ts, tp).p2pRoutes
            test2 <- ts.getTrust.map(_.trust).map(_.view.mapValues(_.predictedTrust.getOrElse(0.0)).toMap)
            result <- expectHttpKryoBodyAndStatus(routes, req)(TrustScores(Map.empty), Status.Ok)
          } yield result
        }
      }
  }
/*
f: A => F[Option[B]]
 value: IO[Option[Double]]
 result: IO[Option[B]]
 OptionT(
    IO.flatMap(value)(opt match {
        case None => IO.pure[Option[B]](None)
        case Some(a) => f(a)
    )
 )
 */
  def expectHttpKryoBodyAndStatus(routes: HttpRoutes[IO], req: Request[IO])(
    expectedBody: TrustScores,
    expectedStatus: Status
  )(implicit kryo: KryoSerializer[IO]): IO[Expectations] = {
    val expectations = for {
      resp <- routes.run(req)
      scores0 = resp.as[TrustScores]
      scores <- OptionT.liftF(scores0)
    } yield expect.all(expectedStatus === resp.status, expectedBody === scores)
    expectations.getOrElse(failure("Unexpected failure"))
  }

}
