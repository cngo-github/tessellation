package org.tessellation.schema

import java.security.Signature
import java.util.UUID

import cats.data.NonEmptyList

import org.tessellation.schema.address.AddressCache
import org.tessellation.schema.gossip._
import org.tessellation.schema.node.NodeState
import org.tessellation.schema.peer.SignRequest
import org.tessellation.schema.trust.PublicTrust
import org.tessellation.security.signature.Signed
import org.tessellation.security.signature.signature.SignatureProof

package object kryo {

  val schemaKryoRegistrar: Map[Class[_], Int] = Map(
    classOf[UUID] -> 101,
    classOf[SignatureProof] -> 102,
    classOf[Signature] -> 103,
    classOf[SignRequest] -> 104,
    classOf[NonEmptyList[_]] -> 105,
    classOf[Signed[_]] -> 106,
    classOf[AddressCache] -> 201,
    classOf[Rumor] -> 202,
    classOf[StartGossipRoundRequest] -> 203,
    classOf[StartGossipRoundResponse] -> 204,
    classOf[EndGossipRoundRequest] -> 205,
    classOf[EndGossipRoundResponse] -> 206,
    NodeState.Initial.getClass -> 207,
    NodeState.ReadyToJoin.getClass -> 208,
    NodeState.GenesisReady.getClass -> 209,
    NodeState.LoadingGenesis.getClass -> 210,
    NodeState.Ready.getClass -> 211,
    NodeState.SessionStarted.getClass -> 212,
    NodeState.Offline.getClass -> 213,
    NodeState.StartingSession.getClass -> 214,
    NodeState.Unknown.getClass -> 215,
    classOf[PublicTrust] -> 216,
    classOf[Ordinal] -> 217
  )
}