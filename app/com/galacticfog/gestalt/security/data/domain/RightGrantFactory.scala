package com.galacticfog.gestalt.security.data.domain

import com.galacticfog.gestalt.security.data.model.RightGrantRepository
import scalikejdbc._

object RightGrantFactory {
  def listRights(appId: String, accountId: String): List[RightGrantRepository] = {
    RightGrantRepository.findAllBy(sqls"account_id=${accountId} and app_id=${appId}")
  }

}
