package org.tessellation.dag.l1.cli

import cats.syntax.contravariantSemigroupal._

import scala.concurrent.duration.{DurationDouble, DurationInt}

import org.tessellation.cli.env.{KeyAlias, Password, StorePath}
import org.tessellation.dag.block.config.BlockValidatorConfig
import org.tessellation.dag.l1.config.TipsConfig
import org.tessellation.dag.l1.config.types.{AppConfig, DBConfig}
import org.tessellation.dag.l1.domain.consensus.block.config.ConsensusConfig
import org.tessellation.sdk.cli.CliMethod
import org.tessellation.sdk.config.AppEnvironment
import org.tessellation.sdk.config.types._

import com.monovore.decline.Opts
import eu.timepit.refined.auto.autoRefineV

object method {

  sealed trait Run extends CliMethod {
    val dbConfig: DBConfig

    val appConfig: AppConfig = AppConfig(
      environment = environment,
      http = httpConfig,
      db = dbConfig,
      gossip = GossipConfig(
        storage = RumorStorageConfig(
          activeRetention = 2.seconds,
          seenRetention = 2.minutes
        ),
        daemon = GossipDaemonConfig(
          fanout = 2,
          interval = 0.2.seconds,
          maxConcurrentHandlers = 20
        )
      ),
      blockValidator = BlockValidatorConfig(
        requiredUniqueSigners = 3
      ),
      consensus = ConsensusConfig(
        peersCount = 2,
        tipsCount = 2,
        timeout = 5.seconds
      ),
      tips = TipsConfig(
        minimumTipsCount = 2,
        maximumTipsCount = 10,
        maximumTipUsages = 2
      ),
      healthCheck = HealthCheckConfig(
        removeUnresponsiveParallelPeersAfter = 10.seconds,
        ping = PingHealthCheckConfig(
          concurrentChecks = 3,
          defaultCheckTimeout = 10.seconds,
          defaultCheckAttempts = 3,
          ensureCheckInterval = 10.seconds
        )
      )
    )
  }

  case class RunInitialValidator(
    keyStore: StorePath,
    alias: KeyAlias,
    password: Password,
    environment: AppEnvironment,
    httpConfig: HttpConfig,
    dbConfig: DBConfig
  ) extends Run

  object RunInitialValidator {

    val opts = Opts.subcommand("run-initial-validator", "Run initial validator mode") {
      (StorePath.opts, KeyAlias.opts, Password.opts, AppEnvironment.opts, http.opts, db.opts)
        .mapN(RunInitialValidator(_, _, _, _, _, _))
    }
  }

  case class RunValidator(
    keyStore: StorePath,
    alias: KeyAlias,
    password: Password,
    environment: AppEnvironment,
    httpConfig: HttpConfig,
    dbConfig: DBConfig
  ) extends Run

  object RunValidator {

    val opts = Opts.subcommand("run-validator", "Run validator mode") {
      (StorePath.opts, KeyAlias.opts, Password.opts, AppEnvironment.opts, http.opts, db.opts)
        .mapN(RunValidator(_, _, _, _, _, _))
    }
  }

  val opts: Opts[Run] =
    RunInitialValidator.opts.orElse(RunValidator.opts)
}