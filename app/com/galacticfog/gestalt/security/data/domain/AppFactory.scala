package com.galacticfog.gestalt.security.data.domain

import com.galacticfog.gestalt.security.data.model.AppRepository
import play.api.mvc.Result
import scalikejdbc._

object AppFactory {
  def findByAppId(appId: String): Option[AppRepository] = AppRepository.find(appId)

  def listByOrgId(orgId: String): List[AppRepository] = {
    AppRepository.findAllBy(sqls"org_id=${orgId}")
  }
}
