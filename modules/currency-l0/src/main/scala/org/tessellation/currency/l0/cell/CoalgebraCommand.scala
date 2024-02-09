package org.tessellation.currency.l0.cell

import org.tessellation.node.shared.snapshot.currency.CurrencySnapshotEvent

sealed trait CoalgebraCommand

object CoalgebraCommand {
  case class ProcessL1Block(data: CurrencySnapshotEvent) extends CoalgebraCommand
}
