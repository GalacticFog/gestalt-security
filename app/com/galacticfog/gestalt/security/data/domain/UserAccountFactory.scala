package com.galacticfog.gestalt.security.data.domain

import com.galacticfog.gestalt.security.data.model.UserAccountRepository
import play.api.libs.json.JsValue
import scalikejdbc._

object UserAccountFactory {
  def authenticate(appId: String, authInfo: JsValue) = {
    val authAttempt: Option[UserAccountRepository] = for {
      username <- (authInfo \ "username").asOpt[String]
      password <- (authInfo \ "password").asOpt[String]
      acc <- UserAccountRepository.findBy(sqls"username=${username}")
      if acc.secret.equals(password)
    } yield acc
    authAttempt
  }
}
