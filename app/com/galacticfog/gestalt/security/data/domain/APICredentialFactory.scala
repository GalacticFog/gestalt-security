package com.galacticfog.gestalt.security.data.domain

import com.galacticfog.gestalt.security.data.model.APICredentialRepository
import play.api.Logger
import scalikejdbc._

object APICredentialFactory {
  def findByAPIKey(apiKey: String): Option[APICredentialRepository] = APICredentialRepository.find(apiKey)
}
