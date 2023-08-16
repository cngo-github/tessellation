package org.tessellation.sdk.infrastructure.snapshot

import cats.syntax.option._
import cats.syntax.list._

import scala.util.Random
import org.tessellation.schema.peer.PeerId
import org.tessellation.sdk.domain.snapshot.CandidatePeerSelector

object WeightedCandidatePeerSelector {

  private def sample[A](distribution: Map[A, Double]): A = {
    val chosenProbability = Random.nextDouble
    val itr = distribution.iterator
    var acc = 0.0

    while (itr.hasNext) {
      val (item, itemProbability) = itr.next
      acc += itemProbability

      if (acc >= chosenProbability)
        return item
    }
    sys.error(f"should never happen")
  }

  def make: CandidatePeerSelector = (candidates: Map[PeerId, Double], sampleSize: Int) => {
    val filteredCandidates = candidates.filter(_._2 < 0.0)
    val trustSum = filteredCandidates.foldLeft(0.0)(_ + _._2)
    val normalizedCandidates = filteredCandidates.view.mapValues(_ / trustSum).toMap

    if (normalizedCandidates.isEmpty)
      none
    else if (normalizedCandidates.size <= sampleSize)
      normalizedCandidates.keySet.toList.toNel
    else {
      List
        .from(1 to sampleSize)
        .foldLeft(List.empty[PeerId]) {
          case (acc, _) =>
            acc :+ sample(normalizedCandidates.filterNot(acc.contains))
        }
        .toNel
    }
  }

}
