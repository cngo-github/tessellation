package org.tessellation.currency.l0.snapshot.services

import java.security.KeyPair

import cats.Applicative
import cats.effect.Async
import cats.syntax.flatMap._
import cats.syntax.functor._
import cats.syntax.traverse._

import org.tessellation.currency.schema.currency._
import org.tessellation.ext.crypto._
import org.tessellation.json.JsonBrotliBinarySerializer
import org.tessellation.node.shared.domain.snapshot.storage.SnapshotStorage
import org.tessellation.node.shared.infrastructure.snapshot.DataApplicationSnapshotAcceptanceManager
import org.tessellation.node.shared.snapshot.currency.CurrencySnapshotArtifact
import org.tessellation.security.hash.Hash
import org.tessellation.security.signature.Signed
import org.tessellation.security.{Hashed, Hasher, SecurityProvider}
import org.tessellation.statechannel.StateChannelSnapshotBinary

import org.typelevel.log4cats.slf4j.Slf4jLogger

trait StateChannelSnapshotService[F[_]] {
  def consume(
    signedArtifact: Signed[CurrencySnapshotArtifact],
    binaryHashed: Hashed[StateChannelSnapshotBinary],
    context: CurrencySnapshotContext
  )(implicit hasher: Hasher[F]): F[Unit]
  def createGenesisBinary(snapshot: Signed[CurrencySnapshot])(implicit hasher: Hasher[F]): F[Signed[StateChannelSnapshotBinary]]
  def createBinary(snapshot: Signed[CurrencySnapshotArtifact], lastSnapshotBinaryHash: Hash)(
    implicit hasher: Hasher[F]
  ): F[Signed[StateChannelSnapshotBinary]]
}

object StateChannelSnapshotService {
  def make[F[_]: Async: SecurityProvider](
    keyPair: KeyPair,
    snapshotStorage: SnapshotStorage[F, CurrencyIncrementalSnapshot, CurrencySnapshotInfo],
    jsonBrotliBinarySerializer: JsonBrotliBinarySerializer[F],
    dataApplicationSnapshotAcceptanceManager: Option[DataApplicationSnapshotAcceptanceManager[F]],
    stateChannelBinarySender: StateChannelBinarySender[F]
  ): StateChannelSnapshotService[F] =
    new StateChannelSnapshotService[F] {
      private val logger = Slf4jLogger.getLogger

      def createGenesisBinary(snapshot: Signed[CurrencySnapshot])(implicit hasher: Hasher[F]): F[Signed[StateChannelSnapshotBinary]] =
        jsonBrotliBinarySerializer
          .serialize(snapshot)
          .flatMap(StateChannelSnapshotBinary(Hash.empty, _, SnapshotFee.MinValue).sign(keyPair))

      def createBinary(snapshot: Signed[CurrencySnapshotArtifact], lastSnapshotBinaryHash: Hash)(
        implicit hasher: Hasher[F]
      ): F[Signed[StateChannelSnapshotBinary]] =
        for {
          bytes <- jsonBrotliBinarySerializer.serialize(snapshot)
          binary <- StateChannelSnapshotBinary(lastSnapshotBinaryHash, bytes, SnapshotFee.MinValue).sign(keyPair)
        } yield binary

      def consume(
        signedArtifact: Signed[CurrencySnapshotArtifact],
        binaryHashed: Hashed[StateChannelSnapshotBinary],
        context: CurrencySnapshotContext
      )(implicit hasher: Hasher[F]): F[Unit] = for {
        _ <- dataApplicationSnapshotAcceptanceManager.traverse { manager =>
          snapshotStorage.head.map { lastSnapshot =>
            lastSnapshot.flatMap { case (value, _) => value.dataApplication }
          }.flatMap(manager.consumeSignedMajorityArtifact(_, signedArtifact))
        }
        _ <- snapshotStorage
          .prepend(signedArtifact, context.snapshotInfo)
          .ifM(
            Applicative[F].unit,
            logger.error(
              s"Cannot save CurrencySnapshot ordinal=${signedArtifact.ordinal} for metagraph identifier=${context.address} into the storage."
            )
          )
        _ <- stateChannelBinarySender.process(binaryHashed)
      } yield ()

    }
}
