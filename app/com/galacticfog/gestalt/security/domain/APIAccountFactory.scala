package com.galacticfog.gestalt.security.domain

import com.galacticfog.gestalt.security.model.APIAccountRepository
import scalikejdbc._

/**
 * Created by cgbaker on 4/23/15.
 */
object APIAccountFactory {

  def findByAPIKey(apiKey: String): Option[APIAccountRepository] = {
    APIAccountRepository.findBy(sqls"api_key=${apiKey}")
  }

}
