package com.galacticfog.gestalt.security.data

import com.galacticfog.gestalt.security.data.model.GestaltOrgRepository
import play.api.libs.functional.syntax._
import play.api.libs.json._

object JsonImports {
  implicit val orgWrites: Writes[GestaltOrgRepository] = (
  (__ \ "orgId").write[String] and
    (__ \ "orgName").write[String]
  )(unlift(GestaltOrgRepository.unapply))
}
