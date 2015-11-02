package com.galacticfog.gestalt.security.data.domain

import scalikejdbc._
import java.util.UUID
import com.galacticfog.gestalt.security.data.model.GestaltDirectoryRepository

object DirectoryFactory extends SQLSyntaxSupport[GestaltDirectoryRepository] {
  def listByOrgId(orgId: UUID): List[GestaltDirectoryRepository] = {
    GestaltDirectoryRepository.findAllBy(sqls"org_id=${orgId}")
  }

}
