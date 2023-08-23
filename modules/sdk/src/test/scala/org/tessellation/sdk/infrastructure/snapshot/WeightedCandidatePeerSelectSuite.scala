package org.tessellation.sdk.infrastructure.snapshot

import cats.syntax.option._

import org.tessellation.schema.generators.peerIdGen

import eu.timepit.refined.auto._
import org.scalacheck.Gen
import weaver.SimpleIOSuite
import weaver.scalacheck.Checkers

object WeightedCandidatePeerSelectSuite extends SimpleIOSuite with Checkers {
  private val genCandidate = for {
    peerId <- peerIdGen
    weight <- Gen.chooseNum[Double](-1.0, 1.0)
  } yield peerId -> weight

  private val genCandidatesMap = for {
    numberOfEntries <- Gen.chooseNum(1, 10)
    candidates <- Gen.mapOfN(numberOfEntries, genCandidate)
  } yield candidates

  test("no candidates means none are selected") {
    val selector = WeightedCandidatePeerSelect.make

    selector.select(Map.empty, 1).map(maybeSelectedPeerId => expect.eql(none, maybeSelectedPeerId))
  }

  test("peer ids are chosen based on its weight") {
    forall(genCandidatesMap) { candidates =>
      val selector = WeightedCandidatePeerSelect.make

      selector.select(candidates, 1).map { maybeSelectedPeerIds =>
        maybeSelectedPeerIds.map { selectedPeerIds =>
          expect.all(selectedPeerIds.size == 1, candidates.contains(selectedPeerIds.head))
        }.getOrElse(failure("The test failed."))
      }
    }
  }
}
