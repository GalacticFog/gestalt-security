package app

import com.galacticfog.gestalt.security.api.errors._
import com.galacticfog.gestalt.security.data.SecurityServices
import com.galacticfog.gestalt.security.data.config.ScalikePostgresDBConnection
import com.galacticfog.gestalt.security.data.domain.{DefaultAccountStoreMappingServiceImpl, OrgFactory}
import play.api._
import org.flywaydb.core.Flyway
import org.apache.commons.dbcp2.BasicDataSource
import play.api.Play.current
import play.api.libs.json.Json
import play.api.mvc.{Result, RequestHeader}
import scala.collection.JavaConverters._
import play.api.{Logger => log}
import scala.concurrent.Future
import scala.util.Try
import play.api.mvc.Results._



object FlywayMigration {

  def migrate(info: ScalikePostgresDBConnection, clean: Boolean,
              rootUsername: String, rootPassword: String) =
  {
    def getDataSource(info: ScalikePostgresDBConnection) = {
      val ds = new BasicDataSource()
      ds.setDriverClassName(info.driver)
      ds.setUsername(info.username)
      ds.setPassword(info.password)
      ds.setUrl(info.url)
      log.info("url: " + ds.getUrl)
      ds
    }

    val baseFlyway = new Flyway()
    val baseDS = getDataSource(info)
    val mlevel1 = Try {
      baseFlyway.setDataSource(baseDS)
      baseFlyway.setLocations("classpath:db/migration/base")
      baseFlyway.setPlaceholders(Map(
        "root_username" -> rootUsername,
        "root_password" -> rootPassword
      ).asJava)
      if (clean) {
        log.info("cleaning database")
        baseFlyway.clean()
      }
      baseFlyway.migrate()
    }
    if ( ! baseDS.isClosed ) try {
      baseDS.close()
    } catch {
      case e: Throwable => log.error("error closing base datasource",e)
    }
    log.info("Base DB migrated to level " + mlevel1)
  }

}
