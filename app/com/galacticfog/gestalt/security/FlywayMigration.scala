package com.galacticfog.gestalt.security

import com.galacticfog.gestalt.security.data.config.ScalikePostgresDBConnection
import org.apache.commons.dbcp2.BasicDataSource
import org.flywaydb.core.Flyway
import org.flywaydb.core.api.MigrationVersion
import play.api.{Logger => log}
import scala.collection.JavaConverters._

import scala.util.{Failure, Success, Try}

object FlywayMigration {

  def getDataSource(dbInfo: ScalikePostgresDBConnection) = {
    val ds = new BasicDataSource()
    ds.setDriverClassName(dbInfo.driver)
    ds.setUsername(dbInfo.username)
    ds.setPassword(dbInfo.password)
    ds.setUrl(dbInfo.url)
    log.info("url: " + ds.getUrl)
    ds
  }

  def currentVersion(dbInfo: ScalikePostgresDBConnection): Option[Int] = {
    val baseFlyway = new Flyway()
    val baseDS = getDataSource(dbInfo)
    baseFlyway.setDataSource(baseDS)
    baseFlyway.setLocations("classpath:db/migration/base")
    val info = baseFlyway.info()
    val cur = Option(info.current())
    if ( ! baseDS.isClosed ) try {
      baseDS.close()
    } catch {
      case e: Throwable => log.error("error closing base datasource",e)
    }
    cur  map {_.getVersion.getVersion} flatMap {v => Try{v.toInt}.toOption}
  }

  def migrate(dbInfo: ScalikePostgresDBConnection,
              rootUsername: String, rootPassword: String,
              targetVersion: Option[String] = None) =
  {
    val baseFlyway = new Flyway()
    val baseDS = getDataSource(dbInfo)
    val migLevel = Try {
      baseFlyway.setDataSource(baseDS)
      baseFlyway.setLocations("classpath:db/migration/base")
      baseFlyway.setPlaceholders(Map(
        "root_username" -> rootUsername,
        "root_password" -> rootPassword
      ).asJava)
      val info = baseFlyway.info()
      Option(info.current()) match {
        case Some(current) =>
          log.info("database at level " + current.getVersion.getVersion)
        case None =>
          log.info("database is not initialized")
      }
      log.info("pending levels: " + info.pending().length)
      targetVersion foreach {baseFlyway.setTargetAsString}
      baseFlyway.migrate()
      baseFlyway.info().current()
    }
    if ( ! baseDS.isClosed ) try {
      baseDS.close()
    } catch {
      case e: Throwable => log.error("error closing base datasource",e)
    }
    migLevel match {
      case Success(info) => log.info(s"base DB migrated to level ${info.getVersion.getVersion}")
      case Failure(ex) =>
        log.error("error migrating database",ex)
        throw ex
    }
  }

}
