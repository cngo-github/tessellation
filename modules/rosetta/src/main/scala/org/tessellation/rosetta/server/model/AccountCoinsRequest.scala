/** Rosetta Build Once. Integrate Your Blockchain Everywhere.
  *
  * The version of the OpenAPI document: 1.4.12 Contact: team@openapitools.org
  *
  * NOTE: This class is auto generated by OpenAPI Generator (https://openapi-generator.tech). https://openapi-generator.tech
  */

package org.tessellation.rosetta.server.model

case class AccountCoinsRequest(
  networkIdentifier: NetworkIdentifier,
  accountIdentifier: AccountIdentifier,
  /* Include state from the mempool when looking up an account's unspent coins. Note, using this functionality breaks any guarantee of idempotency. */
  includeMempool: Boolean,
  /* In some cases, the caller may not want to retrieve coins for all currencies for an AccountIdentifier. If the currencies field is populated, only coins for the specified currencies will be returned. If not populated, all unspent coins will be returned. */
  currencies: Option[List[Currency]]
)
