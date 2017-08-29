package controllers

import java.util.UUID

import com.galacticfog.gestalt.security.api.errors.ConflictException
import com.galacticfog.gestalt.security.api._
import com.galacticfog.gestalt.security.data.model.UserAccountRepository
import com.galacticfog.gestalt.security.plugins.DirectoryPlugin
import controllers.GestaltHeaderAuthentication.AccountWithOrgContext
import play.api.libs.json._
import play.api.mvc.RequestHeader
import com.galacticfog.gestalt.security.api.json.JsonImports._

trait Auditer {
  def apply(event: AuditEvent)(implicit request: RequestHeader): Unit
}

trait WithAuditer {
  def auditer: Auditer
}

trait FailedEventFactory {
  def failed: AuditEvent
}

case class AuditedUserInfo(id: UUID, username: String, email: String)

trait AuditEvent {
  def userInfo: Option[AuditedUserInfo]
  def props: JsObject
  def eventType: String = this.getClass.getSimpleName
  def successful: Boolean

  def toJson: JsObject = Json.obj(
    "event-type" -> eventType,
    "successful" -> successful
  ) ++ userInfo.map(
    ui => Json.obj(
      "audit-user" -> Json.obj(
        "id" -> ui.id.toString,
        "username" -> ui.username,
        "email" -> ui.email
      )
    )
  ).getOrElse[JsObject](Json.obj()) ++ props
}

object AuditEvents {
  implicit def uar2userInfo(a: UserAccountRepository): AuditedUserInfo = AuditedUserInfo(a.id.asInstanceOf[UUID], a.username, a.email.getOrElse(""))

  implicit def ga2userInfo(a: GestaltAccount): AuditedUserInfo = AuditedUserInfo(a.id, a.username, a.email.getOrElse(""))

  case class TokenIssueAttempt(userInfo: Option[AuditedUserInfo], successful: Boolean, presentedIdentifier: String) extends AuditEvent {
    def props = Json.obj(
      "presented-identifier" -> presentedIdentifier
    )
  }
  case object TokenIssueAttempt {
    def failed(u: AuditedUserInfo, pi: String)  = TokenIssueAttempt(Some(u),false,pi)
    def failed(pi: String)  = TokenIssueAttempt(None,false,pi)
    def success(u: AuditedUserInfo, pi: String) = TokenIssueAttempt(Some(u),true ,pi)
  }

  case class InitCheck(wasAlreadyInit: Boolean) extends AuditEvent {
    val successful = true
    override def userInfo: Option[AuditedUserInfo] = None
    override def props = Json.obj(
      "was-already-init" -> wasAlreadyInit
    )
  }

  case class InitAttempt(successful: Boolean, message: String) extends AuditEvent {
    override def userInfo: Option[AuditedUserInfo] = None
    override def props = Json.obj(
      "message" -> message
    )
  }

  case class SyncAttempt(u: Option[AuditedUserInfo], syncRoot: Option[UUID], successful: Boolean) extends AuditEvent with FailedEventFactory {
    override def userInfo: Option[AuditedUserInfo] = u
    override def props = Json.obj(
      "sync-root-org" -> syncRoot
    )
    override def failed: AuditEvent = SyncAttempt(None, syncRoot, false)
  }

  case object SyncAttempt {
    def apply(syncRoot: Option[UUID] = None): SyncAttempt = SyncAttempt(None, syncRoot = syncRoot, false)
    def success(ui: AuditedUserInfo, orgId: UUID): AuditEvent = SyncAttempt(Some(ui), Some(orgId), true)
  }

  case class ListOrgsAttempt(u: Option[AuditedUserInfo], listRoot: Option[UUID], successful: Boolean) extends AuditEvent with FailedEventFactory {
    override def userInfo: Option[AuditedUserInfo] = u
    override def props = Json.obj(
      "list-root-org" -> listRoot
    )
    override def failed: AuditEvent = ListOrgsAttempt(u, listRoot, false)
  }

  case object ListOrgsAttempt {
    def apply(listRoot: Option[UUID] = None): ListOrgsAttempt = ListOrgsAttempt(None, listRoot = listRoot, false)
    def success(ui: AuditedUserInfo, orgId: UUID): AuditEvent = ListOrgsAttempt(Some(ui), Some(orgId), true)
    def failed(ui: AuditedUserInfo, orgId: UUID): AuditEvent = ListOrgsAttempt(Some(ui), Some(orgId), false)
  }

  case class ListOrgDirectoriesAttempt(listRoot: UUID, u: Option[AuditedUserInfo] = None, successful: Boolean = false) extends AuditEvent with FailedEventFactory {
    override def userInfo: Option[AuditedUserInfo] = u
    override def props = Json.obj(
      "list-root-org" -> listRoot
    )
    override def failed: AuditEvent = this.copy(successful = false)
  }

  case class ListOrgAppsAttempt(listRoot: UUID, u: Option[AuditedUserInfo] = None, successful: Boolean = false) extends AuditEvent with FailedEventFactory {
    override def userInfo: Option[AuditedUserInfo] = u
    override def props = Json.obj(
      "list-root-org" -> listRoot
    )
    override def failed: AuditEvent = this.copy(successful = false)
  }

  abstract class GenericCreate[T <: GestaltResource](parentId: UUID, parentType: String, auditUserInfo: Option[AuditedUserInfo], resource: Option[T])(implicit writes: Writes[T]) extends AuditEvent with FailedEventFactory {
    override def userInfo: Option[AuditedUserInfo] = auditUserInfo
    override def props = Json.obj(
      "parent" -> Json.obj(
        "id" -> parentId.toString,
        "type" -> parentType
      )
    ) ++ resource.map(r => Json.obj(
      "resource" -> Json.toJson(r)
    )).getOrElse[JsObject](Json.obj())
  }

  case class CreateDirectoryAttempt(parentId: UUID, parentType: String, u: Option[AuditedUserInfo] = None, successful: Boolean = false, plugin: Option[DirectoryPlugin] = None) extends AuditEvent with FailedEventFactory {
    override def userInfo: Option[AuditedUserInfo] = u
    override def props = Json.obj(
      "parent" -> Json.obj(
        "id" -> parentId,
        "type" -> parentType
      )
    ) ++ plugin.map(p => Json.obj(
      "newDirectory" -> Json.obj(
        "id" -> p.id,
        "name" -> p.name,
        "description" -> p.description,
        "type" -> p.getClass.getName
      )
    )).getOrElse(Json.obj())
    override def failed: AuditEvent = this.copy(successful = false)
  }

  case class CreateAccountAttempt(parentId: UUID, parentType: String, u: Option[AuditedUserInfo] = None, successful: Boolean = false, newAccount: Option[GestaltAccount] = None)
    extends GenericCreate[GestaltAccount](parentId, parentType, u, newAccount) {
    override def failed: AuditEvent = this.copy(successful = false)
  }

  case class CreateGroupAttempt(parentId: UUID, parentType: String, u: Option[AuditedUserInfo] = None, successful: Boolean = false, newGroup: Option[GestaltGroup] = None)
    extends GenericCreate[GestaltGroup](parentId, parentType, u, newGroup) {
    override def failed: AuditEvent = this.copy(successful = false)
  }

  case class CreateOrgAttempt(parentId: UUID, u: Option[AuditedUserInfo] = None, newOrg: Option[GestaltOrg] = None, successful: Boolean = false) extends AuditEvent with FailedEventFactory {
    override def userInfo: Option[AuditedUserInfo] = u
    override def props = Json.obj(
      "parent" -> Json.obj(
        "id" -> parentId.toString,
        "type" -> "org"
      )
    ) ++ newOrg.map(o => Json.obj(
      "newOrg" -> Json.obj(
        "id" -> o.id,
        "name" -> o.name,
        "description" -> o.description,
        "fqon" -> o.fqon
      )
    )).getOrElse(Json.obj())
    override def failed: AuditEvent = this.copy(successful = false)
  }

  case class CreateAppAttempt(parentId: UUID, u: Option[AuditedUserInfo] = None, newApp: Option[GestaltApp] = None, successful: Boolean = false) extends AuditEvent with FailedEventFactory {
    override def userInfo: Option[AuditedUserInfo] = u
    override def props = Json.obj(
      "parent" -> Json.obj(
        "id" -> parentId.toString,
        "type" -> "org"
      )
    ) ++ newApp.map(o => Json.obj(
      "newApp" -> Json.obj(
        "id" -> o.id,
        "name" -> o.name,
        "description" -> o.description
      )
    )).getOrElse(Json.obj())
    override def failed: AuditEvent = this.copy(successful = false)
  }

  case class GetCurrentOrgAttempt(userInfo: Option[AuditedUserInfo] = None, successful: Boolean = false) extends AuditEvent with FailedEventFactory {
    override def props = Json.obj(
      "successful" -> successful
    )
    override def failed: AuditEvent = this.copy(successful = false)
  }

  case class LookupOrgAttempt(fqon: String, userInfo: Option[AuditedUserInfo], rh: RequestHeader) extends AuditEvent {
    val successful: Boolean = true
    override def props = Json.obj(
      "fqon" -> fqon
    )
  }

  case class Failed401(event: AuditEvent, rh: RequestHeader) extends AuditEvent {
    val successful: Boolean = false
    override def userInfo: Option[AuditedUserInfo] = None
    override def eventType: String = event.eventType
    override def props = Json.obj(
      "failure-reason" -> "401-unauthorized",
      "presented-identifier" -> (GestaltHeaderAuthentication.extractAuthToken(rh) match {
        case Some(GestaltBearerCredentials(_)) => "token"
        case Some(GestaltBasicCredentials(username, _)) => username
        case None => ""
      })
    ) ++ event.props
  }

  case class Failed403[A](event: AuditEvent, missingRight: String, request: play.api.mvc.Security.AuthenticatedRequest[A, AccountWithOrgContext]) extends AuditEvent {
    val successful: Boolean = false
    override def userInfo: Option[AuditedUserInfo] = Some(request.user.identity)
    override def eventType: String = event.eventType
    override def props = Json.obj(
      "failure-reason" -> "403-forbidden",
      "missing-right" -> missingRight
    ) ++ event.props
  }

  case class FailedWrongOrg(event: AuditEvent, rh: RequestHeader) extends AuditEvent {
    val successful: Boolean = false
    override def userInfo: Option[AuditedUserInfo] = None
    override def eventType: String = event.eventType
    override def props = Json.obj(
      "failure-reason" -> "403-authed-against-wrong-org"
    ) ++ event.props
  }

  case class FailedConflict(event: AuditEvent, rh: RequestHeader, msg: String) extends AuditEvent {
    val successful: Boolean = false
    override def userInfo: Option[AuditedUserInfo] = None
    override def eventType: String = event.eventType
    override def props = Json.obj(
      "failure-reason" -> "409-conflict",
      "message" -> msg
    ) ++ event.props
  }

  case class FailedNotFound(event: AuditEvent) extends AuditEvent {
    val successful: Boolean = false
    override def userInfo: Option[AuditedUserInfo] = event.userInfo
    override def eventType: String = event.eventType
    override def props = Json.obj(
      "failure-reason" -> "404-resource-not-found"
    ) ++ event.props
  }

  case class FailedBadRequest(event: AuditEvent, msg: Option[String] = None) extends AuditEvent {
    val successful: Boolean = false
    override def userInfo: Option[AuditedUserInfo] = event.userInfo
    override def eventType: String = event.eventType
    override def props = Json.obj(
      "failure-reason" -> "400-bad-request",
      "description" -> msg.getOrElse[String]("none")
    ) ++ event.props
  }

  case class FailedGeneric(event: AuditEvent, ex: Throwable) extends AuditEvent {
    val successful: Boolean = false
    override def userInfo: Option[AuditedUserInfo] = event.userInfo
    override def eventType: String = event.eventType
    override def props = Json.obj(
      "failure-reason" -> ex.getClass().getName,
      "description" -> ex.getMessage
    ) ++ event.props
  }

  def mapExceptionToFailedEvent(ex: Throwable, event: AuditEvent)(implicit request: RequestHeader): AuditEvent = {
    ex match {
      case e: ConflictException => FailedConflict(event, request, ex.getMessage)
      case _                    => FailedGeneric(event, ex)
    }
  }

}

