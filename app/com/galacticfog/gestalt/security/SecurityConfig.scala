package com.galacticfog.gestalt.security

import javax.inject.{Inject, Singleton}

import org.joda.time.Duration
import scala.util.Try

@Singleton
class SecurityConfig @Inject()() {

  import SecurityConfig._

  val tokenLifetime = (for {
    envString <- EnvConfig.getEnvOpt("OAUTH_TOKEN_LIFETIME")
    lifetime <- Try { Duration.parse(envString) }.toOption
  } yield lifetime) getOrElse DEFAULT_TOKEN_LIFETIME

  val methodOverrideParameter = EnvConfig.getEnvOpt("METHOD_OVERRIDE_PARAM") getOrElse DEFAULT_METHOD_OVERRIDE_PARAM

}

object SecurityConfig {
  val DEFAULT_TOKEN_LIFETIME = Duration.standardHours(8)

  val DEFAULT_METHOD_OVERRIDE_PARAM = "_method"
}
