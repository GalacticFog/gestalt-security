package com.galacticfog.gestalt.security.data.domain

import java.util.UUID
import com.galacticfog.gestalt.security.data.model._
import scalikejdbc._

trait AccountStoreMappingService {
  import AccountStoreMappingService._
  def find(mapId: UUID)(implicit session: DBSession = autoSession): Option[AccountStoreMappingRepository]
}

object AccountStoreMappingService extends SQLSyntaxSupport[AccountStoreMappingRepository] {
  override val autoSession = AutoSession
}

class DefaultAccountStoreMappingServiceImpl extends SQLSyntaxSupport[AccountStoreMappingRepository] with AccountStoreMappingService {
  override val autoSession = AutoSession

  def find(mapId: UUID)(implicit session: DBSession = autoSession): Option[AccountStoreMappingRepository] = AccountStoreMappingRepository.find(mapId)
}
