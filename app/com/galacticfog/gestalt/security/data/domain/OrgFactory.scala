package com.galacticfog.gestalt.security.data.domain

import java.util.UUID
import com.galacticfog.gestalt.security.api.errors.BadRequestException
import com.galacticfog.gestalt.security.data.model.GestaltOrgRepository
import play.api.Logger
import scalikejdbc._

import scala.util.Try

object OrgFactory extends SQLSyntaxSupport[GestaltOrgRepository] {

  override val autoSession = AutoSession

  def getRootOrg(): Option[GestaltOrgRepository] = {
    GestaltOrgRepository.findBy(sqls"parent is null")
  }

  def create(parentOrg: GestaltOrgRepository, name: String): Try[GestaltOrgRepository] = {
    if (name.contains('.')) throw new BadRequestException(resource = s"/orgs/${parentOrg.id}", message = "org names cannot contain periods", developerMessage = "Org names cannot contain periods.")
    val newfqon = if (parentOrg.parent.isDefined) parentOrg.fqon + "." + name else name
    Try{GestaltOrgRepository.create(
      id = UUID.randomUUID(),
      name = name,
      fqon = newfqon,
      parent = Some(parentOrg.id)
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

  def getOrgTree(rootId: UUID)(implicit session: DBSession = autoSession): Seq[GestaltOrgRepository] = {
    sql"""WITH RECURSIVE org_tree (id, name, fqon, level, parent)
      AS (
      SELECT
      id,
      name,
      fqon,
      0,
      parent
      FROM org
      WHERE id = ${rootId}

      UNION ALL
      SELECT
      org.id,
      org.name,
      org.fqon,
      org_tree.level + 1,
      org_tree.id
      FROM org, org_tree
      WHERE org.parent = org_tree.id
      )
      SELECT org_tree.id,org_tree.name,org_tree.fqon,org_tree.parent FROM org_tree
      ORDER BY level, fqon""".map(rs => GestaltOrgRepository(
        id = rs.any("id"),
        name = rs.string("name"),
        fqon = rs.string("fqon"),
        parent = rs.anyOpt("parent")
      )).list().apply()
  }

}
