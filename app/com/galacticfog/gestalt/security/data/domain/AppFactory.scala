package com.galacticfog.gestalt.security.data.domain

import com.galacticfog.gestalt.security.data.model.AppRepository
import play.api.mvc.Result
import scalikejdbc._

object AppFactory {

  def APP_ID_LEN: Int = 24

  def findByAppName(orgId: String, appName: String): Option[AppRepository] = {
    AppRepository.findBy(sqls"org_id=${orgId} AND app_name=${appName}")
  }

  def findByAppId(appId: String): Option[AppRepository] = AppRepository.find(appId)

  def listByOrgId(orgId: String): List[AppRepository] = {
    AppRepository.findAllBy(sqls"org_id=${orgId}")
  }
}
