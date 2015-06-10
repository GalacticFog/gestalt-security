package com.galacticfog.gestalt.security.data.domain

import com.galacticfog.gestalt.security.api.{GestaltPasswordCredential, GestaltAccountCreate}
import com.galacticfog.gestalt.security.data.model.{RightGrantRepository, AppAccountAssignmentRepository, UserAccountRepository, AppRepository}
import com.galacticfog.gestalt.security.utils.SecureIdGenerator
import org.mindrot.jbcrypt.BCrypt
import scalikejdbc._
import scalikejdbc.TxBoundary.Try._
import scala.util.{Failure, Success, Try}

case class AppNotFoundException(appId: String) extends Throwable {
  override def getMessage(): String = {"application not found " + appId}
}

object AppFactory {

  val APP_ID_LEN: Int = 24

  def createUserInApp(appId: String, create: GestaltAccountCreate): Try[UserAccountRepository] = DB localTx { implicit session =>
    val newUser = for {
      app <- Try(AppFactory.findByAppId(appId).get)
      cred <- Try(create.credential.asInstanceOf[GestaltPasswordCredential])
      newUser <- Try(UserAccountRepository.create(
        accountId = SecureIdGenerator.genId62(UserAccountFactory.USER_ID_LEN),
        username = create.username,
        email = create.email,
        firstName = create.firstName,
        lastName = create.lastName,
        secret = BCrypt.hashpw(cred.password, BCrypt.gensalt()),
        hashMethod = "bcrypt",
        salt = ""
      ))
      assignment <- Try(AppAccountAssignmentRepository.create(
        appId = app.appId,
        accountId = newUser.accountId)
      )
    } yield newUser
    newUser foreach { user =>
      create.rights foreach {_.foreach { grant =>
        RightGrantRepository.create(
          grantId = SecureIdGenerator.genId62(RightGrantFactory.RIGHT_ID_LEN),
          appId = appId,
          groupId = None,
          accountId = Some(user.accountId),
          grantName = grant.grantName,
          grantValue = grant.grantValue)
      }}
    }
    newUser
  }

  def findByAppName(orgId: String, appName: String): Try[AppRepository] = {
    AppRepository.findBy(sqls"org_id=${orgId} AND app_name=${appName}") match {
      case Some(app) => Success(app)
      case None => Failure(new AppNotFoundException(appName))
    }
  }

  def findByAppId(appId: String): Option[AppRepository] = AppRepository.find(appId)

  def listByOrgId(orgId: String): List[AppRepository] = {
    AppRepository.findAllBy(sqls"org_id=${orgId}")
  }
}
