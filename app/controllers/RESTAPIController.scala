package controllers

import com.galacticfog.gestalt.security.data.JsonImports._
import com.galacticfog.gestalt.security.data.config.ScalikePostgresDBConnection
import com.galacticfog.gestalt.security.data.domain.{RightGrantFactory, UserAccountFactory, AppFactory, GestaltOrgFactory}
import com.galacticfog.gestalt.security.data.model.{RightGrantRepository, APIAccountRepository}
import play.api.libs.json.{JsValue, Json}
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
      case None => NotFound("could not locate current org")
    }
  }

  def listOrgApps(orgId: String) = requireAPIKeyAuthentication { apiAccount => implicit request =>
    // TODO: check that I have permissions over this org
    // TODO: add permissions
    Ok(Json.toJson(AppFactory.listByOrgId(orgId)))
  }

  def getAppById(appId: String) = requireAPIKeyAuthentication { apiAccount => implicit request =>
    AppFactory.findByAppId(appId) match {
      case Some(app) => Ok(Json.toJson(app))
      case None => NotFound("could not locate current org")
    }
  }

  def appAuth(appId: String) = requireAPIKeyAuthentication(parse.json, { apiAccount => implicit request: Request[JsValue] =>
    val attempt = for {
      app <- AppFactory.findByAppId(appId)
      account <- UserAccountFactory.authenticate(app.appId, request.body)
      rights = RightGrantFactory.listRights(appId = app.appId, accountId = account.accountId)
    } yield (account,rights)
    attempt match {
      case None => Forbidden("")
      case Some((acc,rights)) => Ok(Json.obj(
        "account" -> Json.toJson(acc),
        "rights" -> Json.toJson(rights)
      ))
    }
  })

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