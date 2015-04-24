package controllers

import com.galacticfog.gestalt.security.data.JsonImports._
import com.galacticfog.gestalt.security.data.config.ScalikePostgresDBConnection
import com.galacticfog.gestalt.security.data.domain.GestaltOrgFactory
import play.api.libs.json.Json
import play.api.{Logger => log}
import org.apache.shiro.SecurityUtils
import org.apache.shiro.config.IniSecurityManagerFactory
import play.api._
import play.api.mvc._
import org.apache.shiro.mgt.SecurityManager
import scala.concurrent.ExecutionContext.Implicits.global
import play.api.Play.current

import scala.util.{Failure, Success, Try}
import scala.collection.JavaConverters._

case class SecurityControllerInitializationError(msg: String) extends RuntimeException(msg)

object Res {
  val missingShiroManagerType = "application config is missing field \"shiro.managerType\""
  val invalidShiroManagerType = "application config has invalid \"shiro.managerType\": \"%s\""
  val caughtException = "caught exception creating Ini SecurityManager: %s"
}

object RESTAPIController extends Controller with GestaltHeaderAuthentication {

  val db = securityDB

  def initShiro() = {

    def securityMgr: SecurityManager = Play.current.configuration.getString("shiro.managerType") match {
      case Some("INI") => {
        val mgrIni = Play.current.configuration.getString("shiro.iniFile") getOrElse "file:conf/shiro.ini"
        Try {
          val factory = new IniSecurityManagerFactory(mgrIni)
          factory.getInstance()
        } match {
          case Success(sm) => sm
          case Failure(t) => {
            log.error("Caught exception initializing Shiro SecurityManager from IniSecurityManagerFactory", t)
            throw new SecurityControllerInitializationError(Res.caughtException.format(t.getMessage))
          }
        }
      }
      case Some(invalid) => throw new SecurityControllerInitializationError(Res.missingShiroManagerType.format(invalid))
      case None => throw new SecurityControllerInitializationError(Res.missingShiroManagerType)
    }

    SecurityUtils.setSecurityManager(securityMgr)

    //    val sampleRealm = new PlayRealm()
    //    val securityManager = new PlaySecurityManager()
    //    securityManager.setRealm(sampleRealm)
    //
    // Turn off session storage for better "stateless" management.
    // https://shiro.apache.org/session-management.html#SessionManagement-StatelessApplications%2528Sessionless%2529
    //    val subjectDAO = securityManager.getSubjectDAO.asInstanceOf[DefaultSubjectDAO]
    //    val sessionStorageEvaluator = subjectDAO.getSessionStorageEvaluator.asInstanceOf[DefaultSessionStorageEvaluator]
    //
    //    sessionStorageEvaluator.setSessionStorageEnabled(false)
    //
    //    org.apache.shiro.SecurityUtils.setSecurityManager(securityManager)

  }

  def getCurrentOrg() = requireAPIKeyAuthentication { apiAccount => implicit request =>
    GestaltOrgFactory.findByOrgId(apiAccount.defaultOrg) match {
      case Some(org) => Ok(Json.toJson(org))
      case None => BadRequest("could not locate current org")
    }
  }

  def listOrgApps(orgId: String) = play.mvc.Results.TODO

  def getAppById(appId: String) = play.mvc.Results.TODO

  def appLogin(appId: String) = play.mvc.Results.TODO

  private def securityDB = {
    val connection = current.configuration.getObject("database") match {
      case None =>
        throw new RuntimeException("FATAL: Database configuration not found.")
      case Some(config) => {
        val configMap = config.unwrapped.asScala.toMap
        displayStartupSettings(configMap)
        ScalikePostgresDBConnection(
          host = configMap("host").toString,
          database = configMap("dbname").toString,
          port = configMap("port").toString.toInt,
          user = configMap("user").toString,
          password = configMap("password").toString,
          timeoutMs = configMap("timeoutMs").toString.toLong)
      }
    }
    println("CONNECTION : " + connection)
    connection
  }

  private def displayStartupSettings(config: Map[String, Object]) {
    log.debug("DATABASE SETTINGS:")
    for ((k,v) <- config) {
      if (k != "password")
        log.debug("%s = '%s'".format(k, v.toString))
    }
  }

}