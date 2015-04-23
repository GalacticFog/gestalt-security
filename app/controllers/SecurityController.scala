package controllers

import org.apache.shiro.SecurityUtils
import org.apache.shiro.config.IniSecurityManagerFactory
import play.api._
import play.api.libs.json.Json
import play.api.mvc._
import org.apache.shiro.mgt.SecurityManager

import scala.util.{Failure, Success, Try}

case class SecurityControllerInitializationError(msg: String) extends RuntimeException(msg)

object Res {
  val missingShiroManagerType = "application config is missing field \"shiro.managerType\""
  val invalidShiroManagerType = "application config has invalid \"shiro.managerType\": \"%s\""
  val caughtException = "caught exception creating Ini SecurityManager: %s"
}

object SecurityController extends Controller {

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
            Logger.error("Caught exception initializing Shiro SecurityManager from IniSecurityManagerFactory", t)
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

    // Turn off session storage for better "stateless" management.
    // https://shiro.apache.org/session-management.html#SessionManagement-StatelessApplications%2528Sessionless%2529
    //    val subjectDAO = securityManager.getSubjectDAO.asInstanceOf[DefaultSubjectDAO]
    //    val sessionStorageEvaluator = subjectDAO.getSessionStorageEvaluator.asInstanceOf[DefaultSessionStorageEvaluator]

    //    sessionStorageEvaluator.setSessionStorageEnabled(false)

    //    org.apache.shiro.SecurityUtils.setSecurityManager(securityManager)

  }


  def getCurrentOrg() = play.mvc.Results.TODO

//  def getCurrentOrg() = Action.async {
//    val subject = SecurityUtils.getSubject
//    if (subject.isAuthenticated) Ok("Subject is authenticated")
//    else Unauthorized("Subject is not authenticated")
//  }

  def listOrgApps(orgId: String) = play.mvc.Results.TODO

  def getAppById(appId: String) = play.mvc.Results.TODO

  def appLogin(appId: String) = play.mvc.Results.TODO
}