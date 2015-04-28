package com.galacticfog.gestalt.security.data

import com.galacticfog.gestalt.security.data.model.{RightGrantRepository, UserAccountRepository, AppRepository, GestaltOrgRepository}
import play.api.libs.functional.syntax._
import play.api.libs.json
import play.api.libs.json._

object JsonImports {
  implicit val orgWrites: Writes[GestaltOrgRepository] = (
  (__ \ "orgId").write[String] and
    (__ \ "orgName").write[String]
  )(unlift(GestaltOrgRepository.unapply))

  implicit val appWrites: Writes[AppRepository] = (
    (__ \ "appId").write[String] and
      (__ \ "appName").write[String] and
      (__ \ "orgId").write[String]
    )(unlift(AppRepository.unapply))

  implicit val userWrites: Writes[UserAccountRepository] = new Writes[UserAccountRepository] {
    override def writes(o: UserAccountRepository): JsValue = Json.obj(
      "username" -> o.username,
      "firstName" -> o.firstName,
      "lastName" -> o.lastName,
      "email" -> o.email
    )
  }

  implicit val rightWrites: Writes[RightGrantRepository] = (
    (JsPath \ "grantName").write[String] and
      (JsPath \ "grantValue").writeNullable[String]
    )( (rg: RightGrantRepository) => (rg.grantName,rg.grantValue))
}
