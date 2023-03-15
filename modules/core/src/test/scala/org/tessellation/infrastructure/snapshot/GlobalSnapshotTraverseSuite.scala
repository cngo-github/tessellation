package org.tessellation.dag.snapshot

import java.security.KeyPair

import cats.data.{NonEmptyList, NonEmptySet}
import cats.effect.std.Random
import cats.effect.{IO, Resource}
import cats.syntax.applicative._
import cats.syntax.eq._
import cats.syntax.foldable._
import cats.syntax.traverse._

import scala.collection.immutable.{SortedMap, SortedSet}

import org.tessellation.domain.rewards.Rewards
import org.tessellation.domain.statechannel.StateChannelValidator
import org.tessellation.ext.cats.effect.ResourceIO
import org.tessellation.ext.cats.syntax.next._
import org.tessellation.infrastructure.snapshot.{
  GlobalSnapshotConsensusFunctions,
  GlobalSnapshotStateChannelEventsProcessor,
  GlobalSnapshotTraverse
}
import org.tessellation.kryo.KryoSerializer
import org.tessellation.schema._
import org.tessellation.schema.address.Address
import org.tessellation.schema.balance.{Amount, Balance}
import org.tessellation.schema.block.DAGBlock
import org.tessellation.schema.epoch.EpochProgress
import org.tessellation.schema.height.{Height, SubHeight}
import org.tessellation.schema.peer.PeerId
import org.tessellation.schema.transaction.{DAGTransaction, TransactionReference}
import org.tessellation.sdk.config.AppEnvironment
import org.tessellation.sdk.domain.snapshot.storage.SnapshotStorage
import org.tessellation.sdk.domain.transaction.{TransactionChainValidator, TransactionValidator}
import org.tessellation.sdk.infrastructure.block.processing.{BlockAcceptanceLogic, BlockAcceptanceManager, BlockValidator}
import org.tessellation.sdk.infrastructure.metrics.Metrics
import org.tessellation.security.hash.{Hash, ProofsHash}
import org.tessellation.security.hex.Hex
import org.tessellation.security.key.ops.PublicKeyOps
import org.tessellation.security.signature.{Signed, SignedValidator}
import org.tessellation.security.{Hashed, KeyPairGenerator, SecurityProvider}
import org.tessellation.shared.sharedKryoRegistrar
import org.tessellation.syntax.sortedCollection._
import org.tessellation.tools.TransactionGenerator._
import org.tessellation.tools.{DAGBlockGenerator, TransactionGenerator}

import eu.timepit.refined.auto._
import eu.timepit.refined.types.numeric.{NonNegLong, PosInt}
import org.scalacheck.Gen
import weaver._
import weaver.scalacheck.Checkers
// import org.tessellation.sdk.infrastructure.snapshot.storage.SnapshotStorage

object GlobalSnapshotTraverseSuite extends MutableIOSuite with Checkers {
  type GenKeyPairFn = () => KeyPair

  type Res = (KryoSerializer[IO], SecurityProvider[IO], Metrics[IO], Random[IO])

  override def sharedResource: Resource[IO, Res] = for {
    kryo <- KryoSerializer.forAsync[IO](sharedKryoRegistrar)
    sp <- SecurityProvider.forAsync[IO]
    metrics <- Metrics.forAsync[IO](Seq.empty)
    random <- Random.scalaUtilRandom[IO].asResource
  } yield (kryo, sp, metrics, random)

  val balances: Map[Address, Balance] = Map(Address("DAG8Yy2enxizZdWoipKKZg6VXwk7rY2Z54mJqUdC") -> Balance(NonNegLong(10L)))

  def mkSnapshots(dags: List[List[BlockAsActiveTip[DAGBlock]]], initBalances: Map[Address, Balance])(
    implicit K: KryoSerializer[IO],
    S: SecurityProvider[IO]
  ): IO[(Hashed[GlobalSnapshot], NonEmptyList[Hashed[IncrementalGlobalSnapshot]])] =
    KeyPairGenerator.makeKeyPair[IO].flatMap { keyPair =>
      Signed
        .forAsyncKryo[IO, GlobalSnapshot](GlobalSnapshot.mkGenesis(initBalances, EpochProgress.MinValue), keyPair)
        .flatMap(_.toHashed)
        .flatMap { genesis =>
          IncrementalGlobalSnapshot.fromGlobalSnapshot(genesis).flatMap { incremental =>
            mkSnapshot(genesis.hash, incremental, keyPair, SortedSet.empty).flatMap { snap1 =>
              dags
                .foldLeftM(NonEmptyList.of(snap1)) {
                  case (snapshots, blocksChunk) =>
                    mkSnapshot(snapshots.head.hash, snapshots.head.signed.value, keyPair, blocksChunk.toSortedSet).map(snapshots.prepend)
                }
                .map(incrementals => (genesis, incrementals.reverse))
            }
          }
        }
    }

  def mkSnapshot(lastHash: Hash, reference: IncrementalGlobalSnapshot, keyPair: KeyPair, blocks: SortedSet[BlockAsActiveTip[DAGBlock]])(
    implicit K: KryoSerializer[IO],
    S: SecurityProvider[IO]
  ) =
    for {
      activeTips <- reference.activeTips
      snapshot = IncrementalGlobalSnapshot(
        reference.ordinal.next,
        Height.MinValue,
        SubHeight.MinValue,
        lastHash,
        blocks.toSortedSet,
        SortedMap.empty,
        SortedSet.empty,
        reference.epochProgress,
        NonEmptyList.of(PeerId(Hex("peer1"))),
        reference.tips.copy(remainedActive = activeTips),
        reference.stateProof
      )
      signed <- Signed.forAsyncKryo[IO, IncrementalGlobalSnapshot](snapshot, keyPair)
      hashed <- signed.toHashed
    } yield hashed

  type DAGS = (List[Address], Long, SortedMap[Address, Signed[DAGTransaction]], List[List[BlockAsActiveTip[DAGBlock]]])

  def mkBlocks(feeValue: NonNegLong, numberOfAddresses: Int, txnsChunksRanges: List[(Int, Int)], blocksChunksRanges: List[(Int, Int)])(
    implicit K: KryoSerializer[IO],
    S: SecurityProvider[IO],
    R: Random[IO]
  ): IO[DAGS] = for {
    keyPairs <- (1 to numberOfAddresses).toList.traverse(_ => KeyPairGenerator.makeKeyPair[IO])
    addressParams = keyPairs.map(keyPair => AddressParams(keyPair))
    addresses = keyPairs.map(_.getPublic.toAddress)
    txnsSize = if (txnsChunksRanges.nonEmpty) txnsChunksRanges.map(_._2).max.toLong else 0
    txns <- TransactionGenerator
      .infiniteTransactionStream[IO](PosInt.unsafeFrom(1), feeValue, NonEmptyList.fromListUnsafe(addressParams))
      .take(txnsSize)
      .compile
      .toList
    lastTxns = txns.groupBy(_.source).view.mapValues(_.last).toMap.toSortedMap
    transactionsChain = txnsChunksRanges
      .foldLeft[List[List[Signed[DAGTransaction]]]](Nil) { case (acc, (start, end)) => txns.slice(start, end) :: acc }
      .map(txns => NonEmptySet.fromSetUnsafe(SortedSet.from(txns)))
      .reverse
    blockSigningKeyPairs <- NonEmptyList.of("", "", "").traverse(_ => KeyPairGenerator.makeKeyPair[IO])
    dags <- DAGBlockGenerator.createDAGs(transactionsChain, initialReferences(), blockSigningKeyPairs).compile.toList
    chaunkedDags = blocksChunksRanges
      .foldLeft[List[List[BlockAsActiveTip[DAGBlock]]]](Nil) { case (acc, (start, end)) => dags.slice(start, end) :: acc }
      .reverse
  } yield (addresses, txnsSize, lastTxns, chaunkedDags)

  def gst(
    globalSnapshot: Hashed[GlobalSnapshot],
    incrementalSnapshots: List[Hashed[IncrementalGlobalSnapshot]]
  )(implicit K: KryoSerializer[IO], S: SecurityProvider[IO], M: Metrics[IO]) = {
    def loadGlobalSnapshot(hash: Hash): IO[Either[Signed[GlobalSnapshot], Signed[IncrementalGlobalSnapshot]]] =
      hash match {
        case h if h === globalSnapshot.hash => Left(globalSnapshot.signed).pure[IO]
        case _ => Right(incrementalSnapshots.map(snapshot => (snapshot.hash, snapshot)).toMap.get(hash).get.signed).pure[IO]
      }

    val globalSnapshotStorage = new SnapshotStorage[IO, IncrementalGlobalSnapshot, GlobalSnapshotInfo] {

      override def prepend(snapshot: Signed[IncrementalGlobalSnapshot], initialState: GlobalSnapshotInfo): IO[Boolean] = ???

      override def head: IO[Option[(Signed[IncrementalGlobalSnapshot], GlobalSnapshotInfo)]] = ???

      override def headSnapshot: IO[Option[Signed[IncrementalGlobalSnapshot]]] = ???

      override def get(ordinal: SnapshotOrdinal): IO[Option[Signed[IncrementalGlobalSnapshot]]] = ???

      override def get(hash: Hash): IO[Option[Signed[IncrementalGlobalSnapshot]]] = ???

    }

    val rewards = new Rewards[IO] {

      override def mintedDistribution(
        epochProgress: EpochProgress,
        facilitators: NonEmptySet[ID.Id]
      ): IO[SortedSet[transaction.RewardTransaction]] = ???

      override def feeDistribution(
        snapshotOrdinal: SnapshotOrdinal,
        transactions: SortedSet[DAGTransaction],
        facilitators: NonEmptySet[ID.Id]
      ): IO[SortedSet[transaction.RewardTransaction]] = ???

      override def getAmountByEpoch(epochProgress: EpochProgress, rewardsPerEpoch: SortedMap[EpochProgress, Amount]): Amount = ???

    }
    val signedValidator = SignedValidator.make[IO]
    val blockValidator =
      BlockValidator.make[IO, DAGTransaction, DAGBlock](
        signedValidator,
        TransactionChainValidator.make[IO, DAGTransaction],
        TransactionValidator.make[IO, DAGTransaction](signedValidator)
      )
    val blockAcceptanceManager = BlockAcceptanceManager.make(BlockAcceptanceLogic.make[IO, DAGTransaction, DAGBlock], blockValidator)
    val stateChannelValidator = StateChannelValidator.make[IO](signedValidator)
    val stateChannelProcessor = GlobalSnapshotStateChannelEventsProcessor.make[IO](stateChannelValidator)
    val globalSnapshotConsensusFunctions =
      GlobalSnapshotConsensusFunctions
        .make[IO](globalSnapshotStorage, blockAcceptanceManager, stateChannelProcessor, Amount.empty, rewards, AppEnvironment.Dev)
    GlobalSnapshotTraverse.make[IO](loadGlobalSnapshot, globalSnapshotConsensusFunctions)
  }

  test("can compute state for given incremental global snapshot") { res =>
    implicit val (kryo, sp, metrics, _) = res

    mkSnapshots(List.empty, balances).flatMap { snapshots =>
      gst(snapshots._1, snapshots._2.toList).computeState(snapshots._2.head.signed)
    }.map(state => expect.eql(GlobalSnapshotInfo(SortedMap.empty, SortedMap.empty, SortedMap.from(balances)), state))
  }

  test("computed state contains last refs and preserve total amount of balances when no fees or rewards ") { res =>
    implicit val (kryo, sp, metrics, random) = res

    forall(dagBlockChainGen()) { output: IO[DAGS] =>
      for {
        (addresses, _, lastTxns, chunkedDags) <- output
        (global, incrementals) <- mkSnapshots(
          chunkedDags,
          addresses.map(address => address -> Balance(NonNegLong(1000L))).toMap
        )
        traverser = gst(global, incrementals.toList)
        info <- traverser.computeState(incrementals.last.signed)
        totalBalance = info.balances.values.map(Balance.toAmount(_)).reduce(_.plus(_).toOption.get)
        lastTxRefs <- lastTxns.traverse(TransactionReference.of(_))
      } yield expect.eql((info.lastTxRefs, Amount(NonNegLong.unsafeFrom(addresses.size * 1000L))), (lastTxRefs, totalBalance))

    }
  }

  test("computed state contains last refs and include fees in total amount of balances") { res =>
    implicit val (kryo, sp, metrics, random) = res

    forall(dagBlockChainGen(1L)) { output: IO[DAGS] =>
      for {
        (addresses, txnsSize, lastTxns, chunkedDags) <- output
        (global, incrementals) <- mkSnapshots(
          chunkedDags,
          addresses.map(address => address -> Balance(NonNegLong(1000L))).toMap
        )
        traverser = gst(global, incrementals.toList)
        info <- traverser.computeState(incrementals.last.signed)
        totalBalance = info.balances.values.map(Balance.toAmount(_)).reduce(_.plus(_).toOption.get)
        lastTxRefs <- lastTxns.traverse(TransactionReference.of(_))
      } yield
        expect.eql((info.lastTxRefs, Amount(NonNegLong.unsafeFrom(addresses.size * 1000L - txnsSize * 1L))), (lastTxRefs, totalBalance))

    }
  }

  private def initialReferences() =
    NonEmptyList.fromListUnsafe(
      List
        .range(0, 4)
        .map { i =>
          BlockReference(Height.MinValue, ProofsHash(s"%064d".format(i)))
        }
    )

  private def dagBlockChainGen(
    feeValue: NonNegLong = 0L
  )(implicit r: Random[IO], ks: KryoSerializer[IO], sc: SecurityProvider[IO]): Gen[IO[DAGS]] = for {
    numberOfAddresses <- Gen.choose(2, 5)
    txnsChunksRanges <- Gen
      .listOf(Gen.choose(0, 50))
      .map(l => (0 :: l).distinct.sorted)
      .map(list => list.zip(list.tail))
    blocksChunksRanges <- Gen
      .const((0 to txnsChunksRanges.size).toList)
      .map(l => (0 :: l).distinct.sorted)
      .map(list => list.zip(list.tail))
  } yield mkBlocks(feeValue, numberOfAddresses, txnsChunksRanges, blocksChunksRanges)

}