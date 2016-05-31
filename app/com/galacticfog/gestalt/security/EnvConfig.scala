package com.galacticfog.gestalt.security

import com.galacticfog.gestalt.security.data.config.ScalikePostgresDBConnection
import play.api.{Logger => log}

import scala.util.Try

object EnvConfig {
  val DEFAULT_ROOT_USERNAME = "root"
  val DEFAULT_ROOT_PASSWORD = "letmein"
  val DEFAULT_DB_TIMEOUT: Long = 5000
  val DEFAULT_DB_PORT: Int = 5432

  def getEnvOpt(name: String): Option[String] = {
    System.getenv(name) match {
      case null => None
      case empty if empty.trim.isEmpty => None
      case okay => Some(okay)
    }
  }

  def getEnvOpInt(name: String): Option[Int] = {
    getEnvOpt(name) flatMap { s => Try{s.toInt}.toOption }
  }

  def getEnvOptBoolean(name: String): Option[Boolean] = {
    getEnvOpt(name) flatMap { s => Try{s.toBoolean}.toOption }
  }

  private lazy val host = getEnvOpt("DATABASE_HOSTNAME")
  private lazy val username = getEnvOpt("DATABASE_USERNAME")
  private lazy val password = getEnvOpt("DATABASE_PASSWORD")
  private lazy val dbname = getEnvOpt("DATABASE_NAME")
  private lazy val port = getEnvOpt("DATABASE_PORT")
  private lazy val timeout = getEnvOpt("DATABASE_TIMEOUT_MS")

  lazy val dbConnection = {
    for {
      host <- host
      username <- username
      password <- password
      dbname <- dbname
      port <- port flatMap { s => Try{s.toInt}.toOption} orElse Some(DEFAULT_DB_PORT)
      timeout <- timeout flatMap { s => Try{s.toLong}.toOption} orElse Some(DEFAULT_DB_TIMEOUT)
    } yield ScalikePostgresDBConnection(
      host = host,
      port = port,
      database = dbname,
      username = username,
      password = password,
      timeoutMs = timeout
    )
  }

  lazy val databaseUrl = {
    "jdbc:postgresql://%s:%s/%s?user=%s&password=%s".format(
      host getOrElse "undefined",
      port getOrElse 9455,
      dbname getOrElse "undefined",
      username getOrElse "undefined",
      password map {_ => "*****"} getOrElse "undefined"
    )
  }

}
