package org.tessellation.sdk.infrastructure.snapshot

import cats.effect.IO
import cats.syntax.applicative._
import cats.syntax.option._

import org.tessellation.schema.peer.PeerId
import org.tessellation.schema.trust.TrustScores
import org.tessellation.sdk.infrastructure.consensus.PeerDeclarations
import org.tessellation.sdk.infrastructure.consensus.declaration.Proposal
import org.tessellation.security.hash.Hash
import org.tessellation.security.hex.Hex

import eu.timepit.refined.auto._
import eu.timepit.refined.types.numeric.PosDouble
import weaver.SimpleIOSuite
import weaver.scalacheck.Checkers

object ProposalSelectSuite extends SimpleIOSuite with Checkers {

  val getTrustScores: IO[TrustScores] = TrustScores(
    Map(
      PeerId(Hex("a")) -> -1.0,
      PeerId(Hex("b")) -> 0.5,
      PeerId(Hex("c")) -> 1.0,
      PeerId(Hex("d")) -> -0.5,
      PeerId(Hex("e")) -> 0.75,
      PeerId(Hex("f")) -> 0.2
    )
  ).pure

  test("scored declarations does not include non-positive peer IDs") {
    val declarations: Map[PeerId, PeerDeclarations] = Map(
      PeerId(Hex("a")) -> PeerDeclarations(
        none,
        Proposal(Hash("hashA"), Hash("facilitatorHashA")).some,
        none
      ),
      PeerId(Hex("b")) -> PeerDeclarations(
        none,
        Proposal(Hash("hashB"), Hash("facilitatorHashB")).some,
        none
      ),
      PeerId(Hex("c")) -> PeerDeclarations(
        none,
        Proposal(Hash("hashC"), Hash("facilitatorHashC")).some,
        none
      ),
      PeerId(Hex("d")) -> PeerDeclarations(
        none,
        Proposal(Hash("hashC"), Hash("facilitatorHashC")).some,
        none
      )
    )

    val select = ProposalSelect.make(getTrustScores)

    val expected: List[(Hash, PosDouble)] = List(
      (Hash("hashC"), 1.0),
      (Hash("hashB"), 0.5)
    )

    select.score(declarations).map { actual =>
      expect.eql(true, expected.diff(actual).isEmpty)
    }
  }

  test("scored declarations includes peer IDs not in the trust storage") {
    val declarations: Map[PeerId, PeerDeclarations] = Map(
      PeerId(Hex("a")) -> PeerDeclarations(
        none,
        Proposal(Hash("hashA"), Hash("facilitatorHashA")).some,
        none
      ),
      PeerId(Hex("b")) -> PeerDeclarations(
        none,
        Proposal(Hash("hashB"), Hash("facilitatorHashB")).some,
        none
      ),
      PeerId(Hex("c")) -> PeerDeclarations(
        none,
        Proposal(Hash("hashC"), Hash("facilitatorHashC")).some,
        none
      ),
      PeerId(Hex("g")) -> PeerDeclarations(
        none,
        Proposal(Hash("hashG"), Hash("facilitatorHashG")).some,
        none
      )
    )

    val select = ProposalSelect.make(getTrustScores)

    val expected: List[(Hash, PosDouble)] = List(
      (Hash("hashC"), 1.0),
      (Hash("hashB"), 0.5),
      (Hash("hashG"), 1e-4)
    )

    select.score(declarations).map { actual =>
      expect.eql(true, expected.diff(actual).isEmpty)
    }
  }

  test("scored declarations adds scores for the same hash") {
    val declarations: Map[PeerId, PeerDeclarations] = Map(
      PeerId(Hex("e")) -> PeerDeclarations(
        none,
        Proposal(Hash("hashC"), Hash("facilitatorHashC")).some,
        none
      ),
      PeerId(Hex("b")) -> PeerDeclarations(
        none,
        Proposal(Hash("hashB"), Hash("facilitatorHashB")).some,
        none
      ),
      PeerId(Hex("c")) -> PeerDeclarations(
        none,
        Proposal(Hash("hashC"), Hash("facilitatorHashC")).some,
        none
      ),
      PeerId(Hex("f")) -> PeerDeclarations(
        none,
        Proposal(Hash("hashC"), Hash("facilitatorHashC")).some,
        none
      )
    )

    val select = ProposalSelect.make(getTrustScores)

    val expected: List[(Hash, PosDouble)] = List(
      (Hash("hashC"), 1.95),
      (Hash("hashB"), 0.5)
    )

    select.score(declarations).map { actual =>
      expect.eql(true, expected.diff(actual).isEmpty)
    }
  }
}
