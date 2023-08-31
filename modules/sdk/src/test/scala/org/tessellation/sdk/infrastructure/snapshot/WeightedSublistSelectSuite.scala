package org.tessellation.sdk.infrastructure.snapshot

import cats.data.NonEmptyList
import cats.syntax.eq._
import cats.syntax.list._
import cats.syntax.option._

import org.tessellation.schema.generators.peerIdGen
import org.tessellation.schema.peer.PeerId

import eu.timepit.refined.auto._
import org.scalacheck.Gen
import weaver.SimpleIOSuite
import weaver.scalacheck.Checkers

object WeightedSublistSelectSuite extends SimpleIOSuite with Checkers {

  private val genCandidate = for {
    peerId <- peerIdGen
    weight <- Gen.chooseNum[Double](-1.0, 1.0)
  } yield peerId -> weight

  private val genCandidatesMap = for {
    numberOfEntries <- Gen.chooseNum(30, 35)
    candidates <- Gen.mapOfN(numberOfEntries, genCandidate)
  } yield candidates

  test("No candidates means none are selected.") {
    val expected = none[NonEmptyList[PeerId]]

    WeightedSublistSelect.make
      .getSublist(Map.empty[PeerId, Double], 5)
      .map(expect.eql(expected, _))
  }

  test("the sample size is larger than the number of candidates, return all candidates") {
    forall(genCandidatesMap) { candidatesDistribution =>
      WeightedSublistSelect.make.getSublist(candidatesDistribution, 36).map { actual =>
        expect.all(
          36 > candidatesDistribution.size,
          candidatesDistribution.filter(_._2 > 0.0).keySet.toList.toNel === actual
        )
      }
    }
  }

  test("successfully sub-list the candidates") {
    forall(genCandidatesMap) { candidatesDistribution =>
      for {
        actual <- WeightedSublistSelect.make.getSublist(candidatesDistribution, 2)
        expected = candidatesDistribution.filter(_._2 > 0.0).keySet.zipWithIndex.toMap
        actualExpectation = actual.map(_.map(expected.contains)).flatMap(_.find(_ != true))
      } yield
        expect.all(
          2 < candidatesDistribution.size,
          none[Boolean] === actualExpectation
        )
    }
  }

}
