package org.tessellation.sdk.sandbox

import org.tessellation.schema.trust.TrustValueRefined

import scala.util.Random

object ChuongPeerSelection extends App {
  case class PeerId(id: String)

  type PeerMap = Map[PeerId, Double]

  val peerMap: PeerMap = Map(
    PeerId("Alice") -> -0.5,
    PeerId("Bob") -> 0.75,
    PeerId("Charlie") -> 0.2,
    PeerId("Daniel") -> 0.9,
    PeerId("Emily") -> -0.33
  )

  def choosePeer_v1(peerCandidatesTrustScores: PeerMap): PeerId = {
    val random = new scala.util.Random(System.currentTimeMillis())

    val shiftedTrustScores = peerCandidatesTrustScores.view
      .mapValues(_ + 1.0)
      .toSeq
      .sortBy(_._2)
      .toMap

    val trustSum = peerCandidatesTrustScores.foldLeft(0.0) {
      case (acc, (_, trust)) =>
        acc + trust
    }

    val probability = random.between(0.0, trustSum)
    // <- Random[F].betweenInt(0, (trustSum * 1000).toInt).map(_ / 1000)

    val trustScoresOnly = shiftedTrustScores.toSeq.map { case (_, trust) => trust }

    val accumalatedTrustScores = trustScoresOnly.drop(1).scan(trustScoresOnly.head) {
      case (prev, curr) => prev + curr
    }

    val selectedPeerId = shiftedTrustScores.zipWithIndex.map {
      case ((peerId, trust), idx) =>
        val subtotal = accumalatedTrustScores.apply(idx) + trust

        if (probability.toDouble < subtotal)
          Some(peerId)
        else None
    }.filter(_.isDefined).head.get

    selectedPeerId
  }

  def choosePeer_v2(peerMap: PeerMap): PeerId = {
    // Setup for random picker, similar to Random[F]
    val random = new scala.util.Random(System.currentTimeMillis())

    println(
      peerMap.view
        .mapValues(_ + 1.0) // need this before the sort
        .toList
        .sortBy(_._2)
    )

    // sort the list from smallest to largest and iterate through the peer trust map
    // to shift the trust value and denote the subtotals at each peer
    val shiftedTrustScores = peerMap.view
      .mapValues(_ + 1.0) // need this before the sort
      .toList
      .sortBy(_._2)
      .foldLeft(List[(PeerId, Double, Double)]()) {
        case (acc, (peerId, trust)) =>
          val subtotal = if (acc.isEmpty) 0 else acc.head._3
          (peerId, trust, trust + subtotal) :: acc
      }

    println(shiftedTrustScores)

    // list is constructed backwards, so total sum is at the head element
    val trustSum = shiftedTrustScores.head._3

    val probability = random.between(0.0, trustSum)

    println(trustSum)
    println(probability)

    shiftedTrustScores.filter { case (_, _, subtotal) => subtotal > probability }.last._1

  }

  println(choosePeer_v2(peerMap))

  val k = 10.0 // This is how much a weight matters to combating loss of precision of toInt
  val weightOffset = 2.0
  def grantTickets(weight: Double) = ((weight + weightOffset) * k).toInt

  val tickets = peerMap.view.mapValues(grantTickets).toSeq
  val upperBounds = tickets
    .foldLeft((0, List.empty[(PeerId, Int)])) {
      case ((accTicketCount, accPeerTrust), (peerId, numberOfTickets)) =>
        val upperBound = accTicketCount + numberOfTickets
        (upperBound, (peerId, upperBound) :: accPeerTrust)
    }
    ._2
  val totalTickets = upperBounds.head._2
  val winningTicket = Random.between(0, totalTickets)
  val winner = upperBounds.findLast { case (_, upperBound) => winningTicket < upperBound }.map { case (id, _) => id }

}
