package org.tessellation.node.shared.domain.statechannel

import cats.data.{NonEmptySet, ValidatedNec}
import cats.effect.kernel.Async
import cats.syntax.eq._
import cats.syntax.flatMap._
import cats.syntax.functor._
import cats.syntax.option._
import cats.syntax.validated._

import org.tessellation.currency.schema.currency.SnapshotFee
import org.tessellation.ext.cats.syntax.validated._
import org.tessellation.json.JsonSerializer
import org.tessellation.node.shared.domain.seedlist.SeedlistEntry
import org.tessellation.node.shared.domain.statechannel.StateChannelValidator.StateChannelValidationErrorOr
import org.tessellation.schema.SnapshotOrdinal
import org.tessellation.schema.address.Address
import org.tessellation.schema.balance.Balance
import org.tessellation.schema.peer.PeerId
import org.tessellation.security.Hasher
import org.tessellation.security.hash.Hash
import org.tessellation.security.signature.SignedValidator.SignedValidationError
import org.tessellation.security.signature.{Signed, SignedValidator}
import org.tessellation.statechannel.{StateChannelOutput, StateChannelSnapshotBinary}

import derevo.cats.{eqv, show}
import derevo.circe.magnolia.{decoder, encoder}
import derevo.derive
import eu.timepit.refined.auto._
import eu.timepit.refined.types.numeric.{NonNegInt, PosLong}

trait StateChannelValidator[F[_]] {

  def validate(
    stateChannelOutput: StateChannelOutput,
    snapshotOrdinal: SnapshotOrdinal,
    staked: Balance
  )(implicit hasher: Hasher[F]): F[StateChannelValidationErrorOr[StateChannelOutput]]
  def validateHistorical(
    stateChannelOutput: StateChannelOutput,
    snapshotOrdinal: SnapshotOrdinal,
    staked: Balance
  )(
    implicit hasher: Hasher[F]
  ): F[StateChannelValidationErrorOr[StateChannelOutput]]

}

object StateChannelValidator {

  def make[F[_]: Async: JsonSerializer](
    signedValidator: SignedValidator[F],
    l0Seedlist: Option[Set[SeedlistEntry]],
    stateChannelAllowanceLists: Option[Map[Address, NonEmptySet[PeerId]]],
    maxBinarySizeInBytes: PosLong,
    feeCalculator: FeeCalculator[F]
  ): StateChannelValidator[F] = new StateChannelValidator[F] {

    def validate(
      stateChannelOutput: StateChannelOutput,
      globalOrdinal: SnapshotOrdinal,
      staked: Balance
    )(implicit hasher: Hasher[F]): F[StateChannelValidationErrorOr[StateChannelOutput]] =
      validateHistorical(stateChannelOutput, globalOrdinal, staked).map(
        _.product(validateAllowedSignatures(stateChannelOutput)).as(stateChannelOutput)
      )

    def validateHistorical(
      stateChannelOutput: StateChannelOutput,
      globalOrdinal: SnapshotOrdinal,
      staked: Balance
    )(implicit hasher: Hasher[F]): F[StateChannelValidationErrorOr[StateChannelOutput]] =
      for {
        signaturesV <- signedValidator
          .validateSignatures(stateChannelOutput.snapshotBinary)
          .map(_.errorMap[StateChannelValidationError](InvalidSigned))
        snapshotSizeV <- validateSnapshotSize(stateChannelOutput.snapshotBinary)
        snapshotFeeV <- validateSnapshotFee(stateChannelOutput.snapshotBinary, globalOrdinal, staked)
        genesisAddressV = validateStateChannelGenesisAddress(stateChannelOutput.address, stateChannelOutput.snapshotBinary)
      } yield
        signaturesV
          .product(snapshotSizeV)
          .product(snapshotFeeV)
          .product(genesisAddressV)
          .as(stateChannelOutput)

    private def calculateSnapshotSizeInBytes(signedSC: Signed[StateChannelSnapshotBinary]): F[NonNegInt] =
      JsonSerializer[F].serialize(signedSC).map { binary =>
        NonNegInt.unsafeFrom(binary.size)
      }

    private def validateSnapshotSize(
      signedSC: Signed[StateChannelSnapshotBinary]
    ): F[StateChannelValidationErrorOr[Signed[StateChannelSnapshotBinary]]] =
      calculateSnapshotSizeInBytes(signedSC).map { actualSize =>
        val isWithinLimit = actualSize <= maxBinarySizeInBytes

        if (isWithinLimit)
          signedSC.validNec
        else
          BinaryExceedsMaxAllowedSize(maxBinarySizeInBytes, actualSize).invalidNec
      }

    private def validateSnapshotFee(
      signedSC: Signed[StateChannelSnapshotBinary],
      globalOrdinal: SnapshotOrdinal,
      staked: Balance
    ): F[StateChannelValidationErrorOr[Signed[StateChannelSnapshotBinary]]] =
      calculateSnapshotSizeInBytes(signedSC).map { bytesSize =>
        NonNegInt.unsafeFrom(
          (BigDecimal(bytesSize) / BigDecimal(1024))
            .setScale(0, BigDecimal.RoundingMode.UP)
            .toInt
        )
      }.flatMap { sizeKb =>
        feeCalculator.calculateRecommendedFee(globalOrdinal.some)(staked, sizeKb).map { minFee =>
          val isSufficientFee = minFee.value <= signedSC.fee.value

          if (isSufficientFee)
            signedSC.validNec
          else
            BinaryFeeNotSufficient(signedSC.fee, minFee, sizeKb, globalOrdinal).invalidNec
        }
      }

    private def validateAllowedSignatures(stateChannelOutput: StateChannelOutput) =
      validateSignaturesWithSeedlist(stateChannelOutput.snapshotBinary)
        .andThen(_ => validateStateChannelAddress(stateChannelOutput.address))
        .andThen(_ => validateStateChannelAllowanceList(stateChannelOutput.address, stateChannelOutput.snapshotBinary))

    private def validateSignaturesWithSeedlist(
      signed: Signed[StateChannelSnapshotBinary]
    ): StateChannelValidationErrorOr[Signed[StateChannelSnapshotBinary]] =
      signedValidator
        .validateSignaturesWithSeedlist(l0Seedlist.map(_.map(_.peerId)), signed)
        .errorMap(SignersNotInSeedlist)

    private def validateStateChannelAddress(address: Address): StateChannelValidationErrorOr[Address] =
      if (stateChannelAllowanceLists.forall(_.contains(address)))
        address.validNec
      else
        StateChannelAddressNotAllowed(address).invalidNec

    private def validateStateChannelAllowanceList(
      address: Address,
      signedSC: Signed[StateChannelSnapshotBinary]
    ): StateChannelValidationErrorOr[Signed[StateChannelSnapshotBinary]] =
      stateChannelAllowanceLists match {
        case None => signedSC.validNec
        case Some(signersPerAddress) =>
          signersPerAddress
            .get(address)
            .flatMap { peers =>
              signedSC.proofs
                .map(_.id.toPeerId)
                .toSortedSet
                .find(peers.contains)
            }
            .as(signedSC)
            .toValidNec(NoSignerFromStateChannelAllowanceList)
      }

    private def validateStateChannelGenesisAddress(
      address: Address,
      signedSC: Signed[StateChannelSnapshotBinary]
    ): StateChannelValidationErrorOr[Signed[StateChannelSnapshotBinary]] =
      if (signedSC.value.lastSnapshotHash === Hash.empty && signedSC.value.toAddress =!= address)
        StateChannellGenesisAddressNotGeneratedFromData(address).invalidNec
      else
        signedSC.validNec

  }

  @derive(eqv, show, decoder, encoder)
  sealed trait StateChannelValidationError
  case class InvalidSigned(error: SignedValidationError) extends StateChannelValidationError
  case object NotSignedExclusivelyByStateChannelOwner extends StateChannelValidationError
  case class BinaryExceedsMaxAllowedSize(maxSize: Long, was: Int) extends StateChannelValidationError
  case class BinaryFeeNotSufficient(actual: SnapshotFee, minimum: SnapshotFee, sizeKb: Int, ordinal: SnapshotOrdinal)
      extends StateChannelValidationError
  case class SignersNotInSeedlist(error: SignedValidationError) extends StateChannelValidationError
  case class StateChannelAddressNotAllowed(address: Address) extends StateChannelValidationError
  case object NoSignerFromStateChannelAllowanceList extends StateChannelValidationError
  case class StateChannellGenesisAddressNotGeneratedFromData(address: Address) extends StateChannelValidationError

  type StateChannelValidationErrorOr[A] = ValidatedNec[StateChannelValidationError, A]

}
