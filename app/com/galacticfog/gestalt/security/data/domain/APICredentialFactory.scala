package com.galacticfog.gestalt.security.data.domain

import com.galacticfog.gestalt.security.data.model.APICredentialRepository
import play.api.Logger
import scalikejdbc._

object APICredentialFactory extends SQLSyntaxSupport[APICredentialRepository] {

  override val autoSession = AutoSession

  def findByAPIKey(apiKey: String)(implicit session: DBSession = autoSession): Option[APICredentialRepository] = APICredentialRepository.find(apiKey)
}
