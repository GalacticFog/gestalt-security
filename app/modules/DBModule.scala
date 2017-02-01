package modules

import javax.inject._

import com.galacticfog.gestalt.security.data.config.ScalikePostgresDBConnection
import com.galacticfog.gestalt.security.{FlywayMigration, Init, SecurityConfig}
import com.google.inject.AbstractModule

class DBModule extends AbstractModule {
  override def configure(): Unit = {
    scalikejdbc.GlobalSettings.loggingSQLErrors = false
    bind(classOf[DatabaseConnection]).asEagerSingleton()
    bind(classOf[DatabaseMigration]).asEagerSingleton()
  }
}

@Singleton
class DatabaseConnection @Inject()( config: SecurityConfig ) {
  val dbConnection = ScalikePostgresDBConnection(
    host = config.database.host,
    port = config.database.port,
    database = config.database.dbname,
    username = config.database.username,
    password = config.database.password,
    timeoutMs = config.database.timeout
  )
}

@Singleton
class DatabaseMigration @Inject() ( db: DatabaseConnection, init: Init ) {
  if (init.isInit) {
    FlywayMigration.migrate(db.dbConnection, "", "")
  }
}
