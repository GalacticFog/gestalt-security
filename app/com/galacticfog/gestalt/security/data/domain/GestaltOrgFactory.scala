package com.galacticfog.gestalt.security.data.domain

import com.galacticfog.gestalt.security.data.model.GestaltOrgRepository
import play.api.Logger
import scalikejdbc._

object GestaltOrgFactory {
  def findByOrgId(orgId: String): Option[GestaltOrgRepository] = {
    Logger.info("looking for org with org_id = " + orgId)
    GestaltOrgRepository.find(orgId)
  }
}
