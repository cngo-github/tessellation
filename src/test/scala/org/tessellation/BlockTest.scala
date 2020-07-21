package org.tessellation

import cats.kernel.Monoid
import org.scalacheck.Properties
import org.tessellation.schema.{Block, Signature, Snapshot, Transaction}
import org.scalacheck.Prop.{forAll, _}

object BlockTest extends Properties("BlockMonoidTest") {
    property("Monoid.empty") = {
        Monoid[Block].empty.data.fibers ?= Seq.empty
    }

    property("Monoid.combine") = {
        val tx1 = Transaction(1L)
        val tx2 = Transaction(2L)
        val tx3 = Transaction(3L)
        val tx4 = Transaction(4L)

        val block1 = Block(List(tx1, tx2))
        val block2 = Block(List(tx3, tx4))

        val merged = Monoid[Block].combine(block1, block2)
        val unified = Block(List(tx1, tx2, tx3, tx4))

        merged.data.fibers diff unified.data.fibers ?= Seq.empty
    }
}