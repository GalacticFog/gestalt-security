package com.galacticfog.gestalt.security.data.domain

import com.galacticfog.gestalt.security.api.{GestaltPasswordCredential, GestaltAccountCreate}
import com.galacticfog.gestalt.security.data.model.{AppAccountAssignmentRepository, UserAccountRepository, AppRepository}
import com.galacticfog.gestalt.security.utils.SecureIdGenerator
import play.api.mvc.Result
import scalikejdbc._
import scalikejdbc.TxBoundary.Try._
import scala.util.Try

object AppFactory {
  def createUserInApp(appId: String, create: GestaltAccountCreate): Try[UserAccountRepository] = DB localTx { implicit session =>
    for {
      app <- Try(AppFactory.findByAppId(appId).get)
      newUser <- Try(UserAccountRepository.create(
        accountId = SecureIdGenerator.genId62(UserAccountFactory.USER_ID_LEN),
        username = create.username,
        email = create.email,
        firstName = create.firstName,
        lastName = create.lastName,
        secret = create.credential.asInstanceOf[GestaltPasswordCredential].password,
        salt = "",
        hashMethod = ""
      ))
      assignment <- Try(AppAccountAssignmentRepository.create(
        appId = app.appId,
        accountId = newUser.accountId)
      )
    } yield newUser
  }

  def APP_ID_LEN: Int = 24

  def findByAppName(orgId: String, appName: String): Option[AppRepository] = {
    AppRepository.findBy(sqls"org_id=${orgId} AND app_name=${appName}")
  }

  def findByAppId(appId: String): Option[AppRepository] = AppRepository.find(appId)

  def listByOrgId(orgId: String): List[AppRepository] = {
    AppRepository.findAllBy(sqls"org_id=${orgId}")
  }
}
