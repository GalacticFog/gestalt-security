package com.galacticfog.gestalt.security.data.config

import scalikejdbc._


class ScalikePostgresDBConnection(host: String, port: Int, database: String, user: String, password: String, timeoutMs: Long = 5000L)
    extends DBConnectionInfo(host, port, database, user, password) {
  val driver = "org.postgresql.Driver"
  val url = "jdbc:postgresql://%s:%d/%s".format(host, port, database)

  /* This magically 'opens' the connection */
  Class.forName(driver)
  
  val settings = ConnectionPoolSettings(
    connectionTimeoutMillis = timeoutMs// , validationQuery = "select 1 from organization;"
  )
  
  ConnectionPool.singleton(url, user, password, settings)
  println("IS-INITIALIZED : " + ConnectionPool.isInitialized())
  
}

object ScalikePostgresDBConnection {
  def apply(host: String, port: Int = 5432, database: String, user: String, password: String, timeoutMs: Long = 5000L) = {
    new ScalikePostgresDBConnection(host, port, database, user, password)
  }
}
