package org.tessellation.infrastructure.snapshot

import cats.data.NonEmptyList
import cats.effect.std.Supervisor
import cats.effect.{IO, Ref, Resource}
import cats.syntax.applicative._
import cats.syntax.either._
import cats.syntax.list._

import scala.collection.immutable.{SortedMap, SortedSet}
import scala.reflect.runtime.universe.TypeTag

import org.tessellation.currency.schema.currency.SnapshotFee
import org.tessellation.ext.cats.syntax.next._
import org.tessellation.ext.crypto._
import org.tessellation.kryo.KryoSerializer
import org.tessellation.schema.address.Address
import org.tessellation.schema.balance.Amount
import org.tessellation.schema.epoch.EpochProgress
import org.tessellation.schema.peer.PeerId
import org.tessellation.schema.{Block, _}
import org.tessellation.sdk.domain.block.processing._
import org.tessellation.sdk.domain.fork.ForkInfo
import org.tessellation.sdk.domain.gossip.Gossip
import org.tessellation.sdk.domain.rewards.Rewards
import org.tessellation.sdk.domain.snapshot.storage.SnapshotStorage
import org.tessellation.sdk.infrastructure.consensus.trigger.EventTrigger
import org.tessellation.sdk.infrastructure.metrics.Metrics
import org.tessellation.sdk.infrastructure.snapshot.{GlobalSnapshotAcceptanceManager, GlobalSnapshotStateChannelEventsProcessor}
import org.tessellation.sdk.sdkKryoRegistrar
import org.tessellation.security.hash.Hash
import org.tessellation.security.key.ops.PublicKeyOps
import org.tessellation.security.signature.Signed
import org.tessellation.security.signature.Signed.forAsyncKryo
import org.tessellation.security.{KeyPairGenerator, SecurityProvider}
import org.tessellation.statechannel.{StateChannelOutput, StateChannelSnapshotBinary, StateChannelValidationType}
import org.tessellation.syntax.sortedCollection._

import io.circe.Encoder
import weaver.MutableIOSuite
import weaver.scalacheck.Checkers

object GlobalSnapshotConsensusFunctionsSuite extends MutableIOSuite with Checkers {

  type Res = (Supervisor[IO], KryoSerializer[IO], SecurityProvider[IO], Metrics[IO])

  def mkMockGossip[B](spreadRef: Ref[IO, List[B]]): Gossip[IO] =
    new Gossip[IO] {
      override def spread[A: TypeTag: Encoder](rumorContent: A): IO[Unit] =
        spreadRef.update(rumorContent.asInstanceOf[B] :: _)

      override def spreadCommon[A: TypeTag: Encoder](rumorContent: A): IO[Unit] =
        IO.raiseError(new Exception("spreadCommon: Unexpected call"))
    }

  def mkSignedArtifacts()(
    implicit ks: KryoSerializer[IO],
    sp: SecurityProvider[IO]
  ): IO[(Signed[GlobalSnapshotArtifact], Signed[GlobalSnapshot])] = for {
    keyPair <- KeyPairGenerator.makeKeyPair[IO]

    genesis = GlobalSnapshot.mkGenesis(Map.empty, EpochProgress.MinValue)
    signedGenesis <- Signed.forAsyncKryo[IO, GlobalSnapshot](genesis, keyPair)

    lastArtifact <- GlobalIncrementalSnapshot.fromGlobalSnapshot(signedGenesis.value)
    signedLastArtifact <- Signed.forAsyncKryo[IO, GlobalIncrementalSnapshot](lastArtifact, keyPair)
  } yield (signedLastArtifact, signedGenesis)

  override def sharedResource: Resource[IO, Res] =
    Supervisor[IO].flatMap { supervisor =>
      KryoSerializer.forAsync[IO](sdkKryoRegistrar).flatMap { ks =>
        SecurityProvider.forAsync[IO].flatMap { sp =>
          Metrics.forAsync[IO](Seq.empty).map((supervisor, ks, sp, _))
        }
      }
    }

  val gss: SnapshotStorage[IO, GlobalIncrementalSnapshot, GlobalSnapshotInfo] =
    new SnapshotStorage[IO, GlobalIncrementalSnapshot, GlobalSnapshotInfo] {

      override def prepend(snapshot: Signed[GlobalIncrementalSnapshot], state: GlobalSnapshotInfo): IO[Boolean] = ???

      override def head: IO[Option[(Signed[GlobalIncrementalSnapshot], GlobalSnapshotInfo)]] = ???

      override def headSnapshot: IO[Option[Signed[GlobalIncrementalSnapshot]]] = ???

      override def get(ordinal: SnapshotOrdinal): IO[Option[Signed[GlobalIncrementalSnapshot]]] = ???

      override def get(hash: Hash): IO[Option[Signed[GlobalIncrementalSnapshot]]] = ???

      override def getHash(ordinal: SnapshotOrdinal): F[Option[Hash]] = ???

    }

  val bam: BlockAcceptanceManager[IO] = new BlockAcceptanceManager[IO] {

    override def acceptBlocksIteratively(
      blocks: List[Signed[Block]],
      context: BlockAcceptanceContext[IO]
    ): IO[BlockAcceptanceResult] =
      BlockAcceptanceResult(
        BlockAcceptanceContextUpdate.empty,
        List.empty,
        List.empty
      ).pure[IO]

    override def acceptBlock(
      block: Signed[Block],
      context: BlockAcceptanceContext[IO]
    ): IO[Either[BlockNotAcceptedReason, (BlockAcceptanceContextUpdate, UsageCount)]] = ???

  }

  val scProcessor: GlobalSnapshotStateChannelEventsProcessor[IO] = new GlobalSnapshotStateChannelEventsProcessor[IO] {
    def process(
      ordinal: SnapshotOrdinal,
      lastGlobalSnapshotInfo: GlobalSnapshotInfo,
      events: List[StateChannelEvent],
      validationType: StateChannelValidationType
    ): IO[
      (
        SortedMap[Address, NonEmptyList[Signed[StateChannelSnapshotBinary]]],
        SortedMap[Address, CurrencySnapshotWithState],
        Set[StateChannelEvent]
      )
    ] = IO(
      (events.groupByNel(_.address).view.mapValues(_.map(_.snapshotBinary)).toSortedMap, SortedMap.empty, Set.empty)
    )

    def processCurrencySnapshots(
      lastGlobalSnapshotInfo: GlobalSnapshotContext,
      events: SortedMap[Address, NonEmptyList[Signed[StateChannelSnapshotBinary]]]
    ): IO[SortedMap[Address, NonEmptyList[CurrencySnapshotWithState]]] = ???
  }

  val collateral: Amount = Amount.empty

  val rewards: Rewards[F, GlobalSnapshotStateProof, GlobalIncrementalSnapshot, GlobalSnapshotEvent] =
    (_, _, _, _, _, _) => IO(SortedSet.empty)

  def mkGlobalSnapshotConsensusFunctions(gossip: Gossip[F])(
    implicit ks: KryoSerializer[IO],
    sp: SecurityProvider[IO],
    m: Metrics[IO]
  ): GlobalSnapshotConsensusFunctions[IO] = {
    val snapshotAcceptanceManager: GlobalSnapshotAcceptanceManager[IO] =
      GlobalSnapshotAcceptanceManager.make[IO](bam, scProcessor, collateral)

    GlobalSnapshotConsensusFunctions
      .make[IO](
        gss,
        snapshotAcceptanceManager,
        collateral,
        rewards,
        gossip
      )
  }

  test("validateArtifact - returns artifact for correct data") { res =>
    implicit val (_, ks, sp, m) = res

    Ref.of(List.empty[ForkInfo]).map(mkMockGossip).flatMap { r =>
      val gscf = mkGlobalSnapshotConsensusFunctions(r)

      val facilitators = Set.empty[PeerId]

      KeyPairGenerator.makeKeyPair[IO].flatMap { keyPair =>
        val genesis = GlobalSnapshot.mkGenesis(Map.empty, EpochProgress.MinValue)
        Signed.forAsyncKryo[IO, GlobalSnapshot](genesis, keyPair).flatMap { signedGenesis =>
          GlobalIncrementalSnapshot.fromGlobalSnapshot(signedGenesis.value).flatMap { lastArtifact =>
            Signed.forAsyncKryo[IO, GlobalIncrementalSnapshot](lastArtifact, keyPair).flatMap { signedLastArtifact =>
              mkStateChannelEvent().flatMap { scEvent =>
                gscf
                  .createProposalArtifact(
                    SnapshotOrdinal.MinValue,
                    signedLastArtifact,
                    signedGenesis.value.info,
                    EventTrigger,
                    Set(scEvent.asLeft[DAGEvent]),
                    facilitators
                  )
                  .flatMap {
                    case (artifact, _, _) =>
                      gscf.validateArtifact(signedLastArtifact, signedGenesis.value.info, EventTrigger, artifact, facilitators).map {
                        result =>
                          expect.same(result.isRight, true) && expect
                            .same(
                              result.map(_._1.stateChannelSnapshots(scEvent.address)),
                              Right(NonEmptyList.one(scEvent.snapshotBinary))
                            )
                      }
                  }
              }
            }
          }
        }
      }
    }
  }

  test("validateArtifact - returns invalid artifact error for incorrect data") { res =>
    implicit val (_, ks, sp, m) = res

    Ref.of(List.empty[ForkInfo]).map(mkMockGossip).flatMap { r =>
      val gscf = mkGlobalSnapshotConsensusFunctions(r)

      val facilitators = Set.empty[PeerId]

      KeyPairGenerator.makeKeyPair[IO].flatMap { keyPair =>
        val genesis = GlobalSnapshot.mkGenesis(Map.empty, EpochProgress.MinValue)
        Signed.forAsyncKryo[IO, GlobalSnapshot](genesis, keyPair).flatMap { signedGenesis =>
          GlobalIncrementalSnapshot.fromGlobalSnapshot(signedGenesis.value).flatMap { lastArtifact =>
            Signed.forAsyncKryo[IO, GlobalIncrementalSnapshot](lastArtifact, keyPair).flatMap { signedLastArtifact =>
              mkStateChannelEvent().flatMap { scEvent =>
                gscf
                  .createProposalArtifact(
                    SnapshotOrdinal.MinValue,
                    signedLastArtifact,
                    signedGenesis.value.info,
                    EventTrigger,
                    Set(scEvent.asLeft[DAGEvent]),
                    facilitators
                  )
                  .flatMap { proposalArtifact =>
                    gscf
                      .validateArtifact(
                        signedLastArtifact,
                        signedGenesis.value.info,
                        EventTrigger,
                        proposalArtifact._1.copy(ordinal = proposalArtifact._1.ordinal.next),
                        facilitators
                      )
                      .map { result =>
                        expect.same(result.isLeft, true)
                      }
                  }
              }
            }
          }
        }
      }
    }
  }

  test("gossip signed artifacts") { res =>
    implicit val (_, ks, sp, m) = res

    for {
      gossiped <- Ref.of(List.empty[ForkInfo])
      mockGossip = mkMockGossip(gossiped)

      gscf = mkGlobalSnapshotConsensusFunctions(mockGossip)
      (signedLastArtifact, _) <- mkSignedArtifacts()

      _ <- gscf.gossipForkInfo(mockGossip, signedLastArtifact)

      expected = signedLastArtifact.hash.map { h =>
        List(ForkInfo(signedLastArtifact.value.ordinal, h))
      }
        .getOrElse(List.empty)
      actual <- gossiped.get
    } yield expect.eql(expected, actual)
  }

  def mkStateChannelEvent()(implicit S: SecurityProvider[IO], K: KryoSerializer[IO]): IO[StateChannelEvent] = for {
    keyPair <- KeyPairGenerator.makeKeyPair[IO]
    binary = StateChannelSnapshotBinary(Hash.empty, "test".getBytes, SnapshotFee.MinValue)
    signedSC <- forAsyncKryo(binary, keyPair)
  } yield StateChannelOutput(keyPair.getPublic.toAddress, signedSC)

}
