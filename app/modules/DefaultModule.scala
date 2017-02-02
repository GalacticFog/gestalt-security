package modules

import com.galacticfog.gestalt.security.SecurityConfig
import com.galacticfog.gestalt.security.data.domain.{AccountStoreMappingService, DefaultAccountStoreMappingServiceImpl}
import com.google.inject.{AbstractModule, Provides}
import org.joda.time.Duration

import scala.util.Properties.envOrNone
import scala.util.Try

class DefaultModule extends AbstractModule {
  override def configure(): Unit = {
    bind(classOf[AccountStoreMappingService]).to(classOf[DefaultAccountStoreMappingServiceImpl])
  }

  @Provides
  def providesSecurityConfig(): SecurityConfig = {

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

    SecurityConfig(
      tokenLifetime = tokenLifetime,
      methodOverrideParameter = methodOverrideParameter,
      database = database,
      rateLimiting = rateLimiting
    )
  }
}

