package com.galacticfog.gestalt.security.data.config

import scalikejdbc._

class ScalikePostgresDBConnection(override val host: String,
                                  override val port: Int,
                                  override val database: String,
                                  override val username: String,
                                  override val password: String,
                                  val timeoutMs: Long = 5000L)
    extends DBConnectionInfo(host, port, database, username, password)
{
  val driver = "org.postgresql.Driver"
  val url = "jdbc:postgresql://%s:%d/%s".format(host, port, database)

  Class.forName(driver)
  
  val settings = ConnectionPoolSettings(
    connectionTimeoutMillis = timeoutMs// , validationQuery = "select 1 from organization;"
  )

  ConnectionPool.singleton(url, username, password, settings)
  println("IS-INITIALIZED : " + ConnectionPool.isInitialized())
}

object ScalikePostgresDBConnection {
  def apply(host: String, port: Int = 5432, database: String, username: String, password: String, timeoutMs: Long = 5000L) =
    new ScalikePostgresDBConnection(host, port, database, username, password)
}
