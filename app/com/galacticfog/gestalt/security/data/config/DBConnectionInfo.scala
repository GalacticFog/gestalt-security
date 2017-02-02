package com.galacticfog.gestalt.security.data.config

abstract class DBConnectionInfo(val host: String,
                                val port: Int,
                                val database: String,
                                val username: String,
                                val password: String)
{
  val driver: String
  val url: String
}

class PostgresJdbcInfo(host: String, port: Int, database: String, user: String, password: String)
    extends DBConnectionInfo(host, port, database, user, password) {
  val driver = "org.postgresql.Driver"
  val url = "jdbc:postgresql://%s:%d/%s".format(host, port, database)
}

object PostgresJdbcInfo {
  def apply(host: String, port: Int = 5432, database: String, user: String, password: String) = {
    new PostgresJdbcInfo(host, port, database, user, password)
  }  
}
