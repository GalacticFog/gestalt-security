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

trait AuditEventFactory[E <: AuditEvent] {
  def failed: AuditEvent
  def authed(aui: AuditedUserInfo): E
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

  case class TokenIntrospectionAttempt( userInfo: Option[AuditedUserInfo],
                                        successful: Boolean,
                                        tokenAccountId: Option[UUID],
                                        tokenActive: Option[Boolean] ) extends AuditEvent with AuditEventFactory[TokenIntrospectionAttempt] {
    def props = tokenAccountId.map(id => Json.obj(
      "token-account-id" -> id
    )).getOrElse(Json.obj()) ++ tokenActive.map(b => Json.obj(
      "token-active" -> b
    )).getOrElse(Json.obj())
    override def failed: AuditEvent = this.copy(successful = false)
    override def authed(aui: AuditedUserInfo): TokenIntrospectionAttempt = this.copy(userInfo = Some(aui))
  }

  case class AuthAttempt(userInfo: Option[AuditedUserInfo],
                         successful: Boolean,
                         context: Option[(String,UUID)],
                         authenticatedAccount: Option[AuditedUserInfo]) extends AuditEvent with AuditEventFactory[AuthAttempt] {
    def props = context.map({case (t,id) => Json.obj(
      "auth-context-type" -> t,
      "auth-context-id" -> id
    )}).getOrElse(Json.obj()) ++ authenticatedAccount.map(acct => Json.obj(
      "authenticated-account" -> Json.obj(
        "id" -> acct.id,
        "username" -> acct.username,
        "email" -> acct.email
      )
    )).getOrElse(Json.obj())
    override def failed: AuditEvent = this.copy(successful = false)
    override def authed(aui: AuditedUserInfo): AuthAttempt = this.copy(userInfo = Some(aui))
  }
  case object AuthAttempt {
    def apply(context: Option[(String,UUID)] = None): AuthAttempt = AuthAttempt(None, false, context, None)
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

  case class SyncAttempt(u: Option[AuditedUserInfo], syncRoot: Option[UUID], successful: Boolean) extends AuditEvent with AuditEventFactory[SyncAttempt] {
    override def userInfo: Option[AuditedUserInfo] = u
    override def props = Json.obj(
      "sync-root-org" -> syncRoot
    )
    override def failed: AuditEvent = SyncAttempt(None, syncRoot, false)
    override def authed(aui: AuditedUserInfo) = this.copy(u = Some(aui))
  }

  case object SyncAttempt {
    def apply(syncRoot: Option[UUID] = None): SyncAttempt = SyncAttempt(None, syncRoot = syncRoot, false)
  }

  case class ListOrgsAttempt(u: Option[AuditedUserInfo], listRoot: Option[UUID], successful: Boolean) extends AuditEvent with AuditEventFactory[ListOrgsAttempt] {
    override def userInfo: Option[AuditedUserInfo] = u
    override def props = Json.obj(
      "list-root-org" -> listRoot
    )
    override def failed: AuditEvent = ListOrgsAttempt(u, listRoot, false)
    override def authed(aui: AuditedUserInfo) = this.copy(u = Some(aui))
  }

  case object ListOrgsAttempt {
    def apply(listRoot: Option[UUID] = None): ListOrgsAttempt = ListOrgsAttempt(None, listRoot = listRoot, false)
    def success(ui: AuditedUserInfo, orgId: UUID): AuditEvent = ListOrgsAttempt(Some(ui), Some(orgId), true)
    def failed(ui: AuditedUserInfo, orgId: UUID): AuditEvent = ListOrgsAttempt(Some(ui), Some(orgId), false)
  }

  case class ListOrgDirectoriesAttempt(listRoot: UUID, u: Option[AuditedUserInfo] = None, successful: Boolean = false) extends AuditEvent with AuditEventFactory[ListOrgDirectoriesAttempt] {
    override def userInfo: Option[AuditedUserInfo] = u
    override def props = Json.obj(
      "list-root-org" -> listRoot
    )
    override def failed: AuditEvent = this.copy(successful = false)
    override def authed(aui: AuditedUserInfo) = this.copy(u = Some(aui))
  }

  case class ListOrgAppsAttempt(listRoot: UUID, u: Option[AuditedUserInfo] = None, successful: Boolean = false) extends AuditEvent with AuditEventFactory[ListOrgAppsAttempt] {
    override def userInfo: Option[AuditedUserInfo] = u
    override def props = Json.obj(
      "list-root-org" -> listRoot
    )
    override def failed: AuditEvent = this.copy(successful = false)
    override def authed(aui: AuditedUserInfo) = this.copy(u = Some(aui))
  }

  abstract class GenericCreateAttempt[T <: GestaltResource, E <: AuditEvent]( parentId: UUID,
                                                                              parentType: String,
                                                                              auditUserInfo: Option[AuditedUserInfo],
                                                                              resource: Option[T] )
                                                                            ( implicit writes: Writes[T])
    extends AuditEvent with AuditEventFactory[E] {
    override def props = Json.obj(
      "parent" -> Json.obj(
        "id" -> parentId.toString,
        "type" -> parentType
      )
    ) ++ resource.map(r => Json.obj(
      "created-resource" -> Json.toJson(r)
    )).getOrElse[JsObject](Json.obj())
  }

  case class CreateDirectoryAttempt(parentId: UUID, parentType: String, u: Option[AuditedUserInfo] = None, successful: Boolean = false, newDirectory: Option[GestaltDirectory] = None)
    extends GenericCreateAttempt[GestaltDirectory,CreateDirectoryAttempt](parentId, parentType, u, newDirectory) {
    override def userInfo: Option[AuditedUserInfo] = u
    override def failed: AuditEvent = this.copy(successful = false)
    override def authed(aui: AuditedUserInfo) = this.copy(u = Some(aui))
  }

  case class CreateAccountAttempt(parentId: UUID, parentType: String, u: Option[AuditedUserInfo] = None, successful: Boolean = false, newAccount: Option[GestaltAccount] = None)
    extends GenericCreateAttempt[GestaltAccount,CreateAccountAttempt](parentId, parentType, u, newAccount) {
    override def userInfo: Option[AuditedUserInfo] = u
    override def failed: AuditEvent = this.copy(successful = false)
    override def authed(aui: AuditedUserInfo) = this.copy(u = Some(aui))
  }

  case class CreateGroupAttempt(parentId: UUID, parentType: String, u: Option[AuditedUserInfo] = None, successful: Boolean = false, newGroup: Option[GestaltGroup] = None)
    extends GenericCreateAttempt[GestaltGroup,CreateGroupAttempt](parentId, parentType, u, newGroup) {
    override def userInfo: Option[AuditedUserInfo] = u
    override def failed: AuditEvent = this.copy(successful = false)
    override def authed(aui: AuditedUserInfo) = this.copy(u = Some(aui))
  }

  case class CreateOrgAttempt(parentId: UUID, u: Option[AuditedUserInfo] = None, successful: Boolean = false, newOrg: Option[GestaltOrg] = None)
    extends GenericCreateAttempt[GestaltOrg,CreateOrgAttempt](parentId, "org", u, newOrg) {
    override def userInfo: Option[AuditedUserInfo] = u
    override def failed: AuditEvent = this.copy(successful = false)
    override def authed(aui: AuditedUserInfo) = this.copy(u = Some(aui))
  }

  case class CreateAppAttempt(parentId: UUID, u: Option[AuditedUserInfo] = None, successful: Boolean = false, newApp: Option[GestaltApp] = None)
    extends GenericCreateAttempt[GestaltApp,CreateAppAttempt](parentId, "org", u, newApp) {
    override def userInfo: Option[AuditedUserInfo] = u
    override def failed: AuditEvent = this.copy(successful = false)
    override def authed(aui: AuditedUserInfo) = this.copy(u = Some(aui))
  }

  case class CreateAccountStoreAttempt(parentId: UUID, parentType: String, u: Option[AuditedUserInfo] = None, newAccountStore: Option[GestaltAccountStoreMapping] = None, successful: Boolean = false)
    extends GenericCreateAttempt[GestaltAccountStoreMapping,CreateAccountStoreAttempt](parentId, parentType, u, newAccountStore) {
    override def userInfo: Option[AuditedUserInfo] = u
    override def failed: AuditEvent = this.copy(successful = false)
    override def authed(aui: AuditedUserInfo) = this.copy(u = Some(aui))
  }

  case class GetCurrentOrgAttempt(u: Option[AuditedUserInfo] = None, successful: Boolean = false) extends AuditEvent with AuditEventFactory[GetCurrentOrgAttempt] {
    override def userInfo: Option[AuditedUserInfo] = u
    override def props = Json.obj()
    override def failed: AuditEvent = this.copy(successful = false)
    override def authed(aui: AuditedUserInfo) = this.copy(u = Some(aui))
  }

  case class LookupOrgAttempt(fqon: String, userInfo: Option[AuditedUserInfo], rh: RequestHeader) extends AuditEvent {
    val successful: Boolean = true
    override def props = Json.obj(
      "fqon" -> fqon
    )
  }

  case class Failed401(event: AuditEvent, rh: RequestHeader) extends AuditEvent {
    val successful: Boolean = false
    override def userInfo: Option[AuditedUserInfo] = event.userInfo
    override def eventType: String = event.eventType
    override def props = Json.obj(
      "failure-reason" -> "401-unauthorized",
      "presented-identifier" -> (GestaltHeaderAuthentication.extractAuthToken(rh) match {
        case Some(GestaltBearerCredentials(invalidToken)) => invalidToken
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
    override def userInfo: Option[AuditedUserInfo] = event.userInfo
    override def eventType: String = event.eventType
    override def props = Json.obj(
      "failure-reason" -> "403-authed-against-wrong-org"
    ) ++ event.props
  }

  case class FailedConflict(event: AuditEvent, rh: RequestHeader, msg: String) extends AuditEvent {
    val successful: Boolean = false
    override def userInfo: Option[AuditedUserInfo] = event.userInfo
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

