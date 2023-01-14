/** Rosetta Build Once. Integrate Your Blockchain Everywhere.
  *
  * The version of the OpenAPI document: 1.4.12 Contact: team@openapitools.org
  *
  * NOTE: This class is auto generated by OpenAPI Generator (https://openapi-generator.tech). https://openapi-generator.tech
  */

package org.tessellation.rosetta.server.model

import org.tessellation.rosetta.server.model.dag.metadataSchema.GenericMetadata

case class CallRequest(
  networkIdentifier: NetworkIdentifier,
  /* Method is some network-specific procedure call. This method could map to a network-specific RPC endpoint, a method in an SDK generated from a smart contract, or some hybrid of the two. The implementation must define all available methods in the Allow object. However, it is up to the caller to determine which parameters to provide when invoking `/call`. */
  method: String,
  /* Parameters is some network-specific argument for a method. It is up to the caller to determine which parameters to provide when invoking `/call`. */
  parameters: GenericMetadata
)
