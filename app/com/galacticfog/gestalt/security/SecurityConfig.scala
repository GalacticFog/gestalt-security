package com.galacticfog.gestalt.security

import javax.inject.{Inject, Singleton}

import org.joda.time.Duration
import scala.util.Try
import scala.util.Properties.envOrNone

@Singleton
class SecurityConfig @Inject()() {

  import SecurityConfig._

  val tokenLifetime = (for {
    envString <- envOrNone("OAUTH_TOKEN_LIFETIME")
    lifetime <- Try { Duration.parse(envString) }.toOption
  } yield lifetime) getOrElse DEFAULT_TOKEN_LIFETIME

  val methodOverrideParameter = envOrNone("METHOD_OVERRIDE_PARAM") getOrElse DEFAULT_METHOD_OVERRIDE_PARAM

  val database = DatabaseConfig(
    host = envOrThrow("DATABASE_HOSTNAME"),
    username = envOrThrow("DATABASE_USERNAME"),
    password = envOrThrow("DATABASE_PASSWORD"),
    dbname = envOrThrow("DATABASE_NAME"),
    port = getEnvOptInt("DATABASE_PORT").getOrElse(5432),
    timeout = getEnvOptInt("DATABASE_TIMEOUT_MS").getOrElse(5000)
  )

  val rateLimiting = AuthAttemptConfig(
    periodInMinutes = getEnvOptInt("OAUTH_RATE_LIMITING_PERIOD") getOrElse AuthAttemptConfig.DEFAULT_RATE_LIMITING_PERIOD_IN_MINUTES,
    attemptsPerPeriod = getEnvOptInt("OAUTH_RATE_LIMITING_AMOUNT") getOrElse AuthAttemptConfig.DEFAULT_MAX_ATTEMPTS_PER_MINUTE
  )

}

object SecurityConfig {

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
