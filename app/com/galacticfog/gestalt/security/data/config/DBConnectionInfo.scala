package com.galacticfog.gestalt.security.data.config

abstract class DBConnectionInfo(
    val host: String, 
    val port: Int, 
    val database: String, 
    val user: String, 
    val password: String) {
  val driver: String
  val url: String
}

class InMemH2JdbcInfo(host: String, port: Int, database: String, user: String, password: String)
    extends DBConnectionInfo(host, port, database, user, password) {
  val driver = "org.h2.Driver"
  val url = "jdbc:h2:mem:%s;MODE=PostgreSQL;DB_CLOSE_DELAY=-1".format(database)
}

object InMemH2JdbcInfo {
  def apply(host: String, port: Int, database: String, user: String, password: String) = 
    new InMemH2JdbcInfo(host, port, database, user, password)
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

