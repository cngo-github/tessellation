package org.tessellation.sdk.infrastructure.snapshot

import cats.syntax.applicative._
import cats.syntax.option._

import org.tessellation.schema.generators.peerIdGen

import org.scalacheck.Gen
import weaver.SimpleIOSuite
import weaver.scalacheck.Checkers

object WeightedCandidatePeerSelectorSuite$ extends SimpleIOSuite with Checkers {
  private val genCandidate = for {
    peerId <- peerIdGen
    weight <- Gen.chooseNum[Double](-1.0, 1.0)
  } yield peerId -> weight

  private val genCandidatesMap = for {
    numberOfEntries <- Gen.chooseNum(1, 10)
    candidates <- Gen.mapOfN(numberOfEntries, genCandidate)
  } yield candidates

  test("no candidates means none are selected") {
    val selector = WeightedCandidatePeerSelector.make
    val maybeSelectedPeerId = selector.select(Map.empty)

    expect.eql(none, maybeSelectedPeerId).pure
  }

  test("peer ids are chosen based on its weight") {
    forall(genCandidatesMap) { candidates =>
      val selector = WeightedCandidatePeerSelector.make
      val maybeSelectedPeerId = selector.select(candidates)

      expect.all(maybeSelectedPeerId.isDefined, candidates.contains(maybeSelectedPeerId.get))
    }
  }
}
