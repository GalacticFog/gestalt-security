package com.galacticfog.gestalt.security

import org.joda.time.Duration
import scala.util.Try
import scala.util.Properties.envOrNone

case class SecurityConfig( tokenLifetime: Duration,
                           methodOverrideParameter: String,
                           database: SecurityConfig.DatabaseConfig,
                           rateLimiting: SecurityConfig.AuthAttemptConfig )

case object SecurityConfig {

  def envOrThrow(name: String): String = envOrNone(name) getOrElse(throw new RuntimeException(s"missing env var ${name}"))

  def getEnvOptInt(name: String): Option[Int] = scala.util.Properties.envOrNone(name) flatMap { s => Try{s.toInt}.toOption }

  def getEnvOptBoolean(name: String): Option[Boolean] = scala.util.Properties.envOrNone(name) flatMap { s => Try{s.toBoolean}.toOption }

  val DEFAULT_TOKEN_LIFETIME = Duration.standardHours(8)

  val DEFAULT_METHOD_OVERRIDE_PARAM = "_method"

  case class DatabaseConfig( host: String,
                             username: String,
                             password: String,
                             dbname: String,
                             port: Int,
                             timeout: Int )

  case class AuthAttemptConfig( periodInMinutes: Int,
                                attemptsPerPeriod: Int )

  case object AuthAttemptConfig {
    val DEFAULT_RATE_LIMITING_PERIOD_IN_MINUTES = 1
    val DEFAULT_MAX_ATTEMPTS_PER_MINUTE = 100
  }

}


