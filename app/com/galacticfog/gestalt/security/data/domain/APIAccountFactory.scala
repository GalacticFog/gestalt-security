package com.galacticfog.gestalt.security.data.domain

import com.galacticfog.gestalt.security.data.model.APIAccountRepository
import play.api.Logger
import scalikejdbc._

object APIAccountFactory {

  def findByAPIKey(apiKey: String): Option[APIAccountRepository] = APIAccountRepository.findBy(sqls"api_key=${apiKey}")

}
