package com.galacticfog.gestalt.security.data.domain

import java.util.UUID
import com.galacticfog.gestalt.security.data.model.GestaltOrgRepository
import play.api.Logger
import scalikejdbc._

import scala.util.Try

object OrgFactory extends SQLSyntaxSupport[GestaltOrgRepository] {

  def getRootOrg(): Option[GestaltOrgRepository] = {
    GestaltOrgRepository.findBy(sqls"parent is null")
  }

  def create(parentOrgId: UUID, name: String, fqon: String): Try[GestaltOrgRepository] = {
    Try{GestaltOrgRepository.create(
      id = UUID.randomUUID(),
      name = name,
      fqon = fqon,
      parent = Some(parentOrgId)
    )}
  }

  def findByFQON(fqon: String): Option[GestaltOrgRepository] = {
    Logger.info("looking for org with fqon = " + fqon)
    GestaltOrgRepository.findBy(sqls"fqon = ${fqon}")
  }

  def findByOrgId(orgId: UUID): Option[GestaltOrgRepository] = {
    Logger.info("looking for org with org_id = " + orgId)
    GestaltOrgRepository.find(orgId)
  }

  def getChildren(parentOrgId: UUID): Seq[GestaltOrgRepository] = GestaltOrgRepository.findAllBy(sqls"parent = ${parentOrgId}")
}
