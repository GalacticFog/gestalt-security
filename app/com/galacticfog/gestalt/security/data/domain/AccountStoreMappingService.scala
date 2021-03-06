package com.galacticfog.gestalt.security.data.domain

import java.util.UUID
import javax.inject.{Inject, Singleton}

import com.galacticfog.gestalt.patch.PatchOp
import com.galacticfog.gestalt.security.api.errors.BadRequestException
import com.galacticfog.gestalt.security.data.model._
import scalikejdbc._

import scala.util.Try

trait AccountStoreMappingService {
  import AccountStoreMappingService._

  def updateMapping(map: AccountStoreMappingRepository, patch: Seq[PatchOp])(implicit session: DBSession = autoSession): Try[AccountStoreMappingRepository]

  def find(mapId: UUID)(implicit session: DBSession = autoSession): Option[AccountStoreMappingRepository]

  def findAllByStoreId(storeId: UUID)(implicit session: DBSession = autoSession): Seq[AccountStoreMappingRepository]
}

object AccountStoreMappingService extends SQLSyntaxSupport[AccountStoreMappingRepository] {
  override val autoSession = AutoSession
}

@Singleton
class DefaultAccountStoreMappingServiceImpl @Inject()() extends SQLSyntaxSupport[AccountStoreMappingRepository] with AccountStoreMappingService {
  override val autoSession = AutoSession

  override def find(mapId: UUID)(implicit session: DBSession = autoSession): Option[AccountStoreMappingRepository] = AccountStoreMappingRepository.find(mapId)

  override def findAllByStoreId(storeId: UUID)(implicit session: DBSession): Seq[AccountStoreMappingRepository] = AccountStoreMappingRepository.findAllBy(sqls"account_store_id = ${storeId}")

  override def updateMapping(map: AccountStoreMappingRepository, patch: Seq[PatchOp])(implicit session: DBSession = autoSession): Try[AccountStoreMappingRepository] = {
    val newMap = Try{patch.foldLeft(map)((m, p) => {
      p match {
        case PatchOp(op,"/name",Some(value)) if op.toLowerCase == "add" || op.toLowerCase == "replace" =>
          m.copy(name = Some(value.as[String]))
        case PatchOp("remove","/name",None) =>
          m.copy(name = None)
        case PatchOp(op,"/description",Some(value)) if op.toLowerCase == "add" || op.toLowerCase == "replace" =>
          m.copy(description = Some(value.as[String]))
        case PatchOp("remove","/description",None) =>
          m.copy(description = None)
        case PatchOp("remove","/isDefaultAccountStore",None) =>
          m.copy(defaultAccountStore = None)
        case PatchOp("remove","/isDefaultGroupStore",None) =>
          m.copy(defaultGroupStore = None)
        case _ => throw BadRequestException(
          resource = "",
          message = "bad PATCH payload for updating account store",
          developerMessage = "The PATCH payload for updating the account store mapping had invalid fields."
        )
      }
    })}
    newMap map (_.save())
  }

}
