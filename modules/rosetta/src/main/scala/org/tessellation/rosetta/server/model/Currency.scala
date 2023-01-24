/** Rosetta Build Once. Integrate Your Blockchain Everywhere.
  *
  * The version of the OpenAPI document: 1.4.12 Contact: team@openapitools.org
  *
  * NOTE: This class is auto generated by OpenAPI Generator (https://openapi-generator.tech). https://openapi-generator.tech
  */

package org.tessellation.rosetta.server.model

import org.tessellation.rosetta.server.model.dag.metadataSchema.GenericMetadata

case class Currency(
  /* Canonical symbol associated with a currency. */
  symbol: String,
  /* Number of decimal places in the standard unit representation of the amount. For example, BTC has 8 decimals. Note that it is not possible to represent the value of some currency in atomic units that is not base 10. */
  decimals: Int,
  /* Any additional information related to the currency itself. For example, it would be useful to populate this object with the contract address of an ERC-20 token. */
  metadata: Option[GenericMetadata]
)