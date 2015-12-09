package com.galacticfog.gestalt.security.data.domain

import java.util.UUID
import com.galacticfog.gestalt.io.util.PatchOp
import com.galacticfog.gestalt.security.api.errors.BadRequestException
import com.galacticfog.gestalt.security.data.model._
import scalikejdbc._

trait AccountStoreMappingService {
  import AccountStoreMappingService._

  def updateMapping(map: AccountStoreMappingRepository, patch: Seq[PatchOp])(implicit session: DBSession = autoSession): AccountStoreMappingRepository

  def find(mapId: UUID)(implicit session: DBSession = autoSession): Option[AccountStoreMappingRepository]
}

object AccountStoreMappingService extends SQLSyntaxSupport[AccountStoreMappingRepository] {
  override val autoSession = AutoSession
}

class DefaultAccountStoreMappingServiceImpl extends SQLSyntaxSupport[AccountStoreMappingRepository] with AccountStoreMappingService {
  override val autoSession = AutoSession

  def find(mapId: UUID)(implicit session: DBSession = autoSession): Option[AccountStoreMappingRepository] = AccountStoreMappingRepository.find(mapId)

  override def updateMapping(map: AccountStoreMappingRepository, patch: Seq[PatchOp])(implicit session: DBSession = autoSession): AccountStoreMappingRepository = {
    val newMap = patch.foldLeft(map)((m, p) => {
      p match {
        case PatchOp(op,"/name",value) if op.toLowerCase == "add" || op.toLowerCase == "replace" =>
          m.copy(name = Some(value.as[String]))
        case PatchOp("remove","/name",value) =>
          m.copy(name = None)
        case PatchOp(op,"/description",value) if op.toLowerCase == "add" || op.toLowerCase == "replace" =>
          m.copy(description = Some(value.as[String]))
        case PatchOp("remove","/description",value) =>
          m.copy(description = None)
        case PatchOp("remove","/isDefaultAccountStore",value) =>
          m.copy(defaultAccountStore = None)
        case PatchOp("remove","/isDefaultGroupStore",value) =>
          m.copy(defaultGroupStore = None)
        case _ => throw new BadRequestException(
          resource = "",
          message = "bad PATCH payload for updating account store",
          developerMessage = "The PATCH payload for updating the account store mapping had invalid fields."
        )
      }
    })
    newMap.save()
  }
}
