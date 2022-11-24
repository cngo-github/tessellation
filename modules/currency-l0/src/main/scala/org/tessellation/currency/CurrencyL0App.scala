package org.tessellation.currency

import cats.effect.{IO, Resource}
import org.tessellation.currency.cli.method._
import org.tessellation.schema.cluster.ClusterId
import org.tessellation.sdk.app.SDK

final class CurrencyL0App(
  header: String,
  clusterId: ClusterId,
  helpFlag: Boolean = true,
  version: String = "",
  tokenSymbol: String
) extends CurrencyApp(header, clusterId, helpFlag, version, tokenSymbol) {

  def run(method: Run, sdk: SDK[IO]): Resource[IO, Unit] = for {
    r <- Resource.unit
  } yield r
}
