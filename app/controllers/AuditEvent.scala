package controllers

import java.util.UUID

import com.galacticfog.gestalt.security.api.errors.ConflictException
import com.galacticfog.gestalt.security.api.{GestaltAccount, GestaltBasicCredentials, GestaltBearerCredentials}
import com.galacticfog.gestalt.security.data.model.UserAccountRepository
import com.galacticfog.gestalt.security.plugins.DirectoryPlugin
import play.api.libs.json._
import play.api.mvc.RequestHeader

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
  def props: Map[String,JsValue]
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
  ).getOrElse[JsObject](Json.obj()) ++ Json.toJson(props).as[JsObject]
}

object AuditEvents {
  implicit def uar2userInfo(a: UserAccountRepository): AuditedUserInfo = AuditedUserInfo(a.id.asInstanceOf[UUID], a.username, a.email.getOrElse(""))

  implicit def ga2userInfo(a: GestaltAccount): AuditedUserInfo = AuditedUserInfo(a.id, a.username, a.email.getOrElse(""))

  case class TokenIssueAttempt(userInfo: Option[AuditedUserInfo], successful: Boolean, presentedIdentifier: String) extends AuditEvent {
    def props = Map[String,JsValue](
      "presented-identifier" -> JsString(presentedIdentifier)
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
    override def props: Map[String, JsValue] = Map(
      "was-already-init" -> JsBoolean(wasAlreadyInit)
    )
  }

  case class InitAttempt(successful: Boolean, message: String) extends AuditEvent {
    override def userInfo: Option[AuditedUserInfo] = None
    override def props: Map[String, JsValue] = Map(
      "message" -> JsString(message)
    )
  }

  case class SyncAttempt(u: Option[AuditedUserInfo], syncRoot: Option[UUID], successful: Boolean) extends AuditEvent with FailedEventFactory {
    override def userInfo: Option[AuditedUserInfo] = u
    override def props: Map[String, JsValue] = Map(
      "sync-root-org" -> syncRoot.map(id => JsString(id.toString)).getOrElse[JsValue](JsNull)
    )
    override def failed: AuditEvent = SyncAttempt(None, syncRoot, false)
  }

  case object SyncAttempt {
    def apply(syncRoot: Option[UUID] = None): SyncAttempt = SyncAttempt(None, syncRoot = syncRoot, false)
    def success(ui: AuditedUserInfo, orgId: UUID): AuditEvent = SyncAttempt(Some(ui), Some(orgId), true)
  }

  case class ListOrgsAttempt(u: Option[AuditedUserInfo], listRoot: Option[UUID], successful: Boolean) extends AuditEvent with FailedEventFactory {
    override def userInfo: Option[AuditedUserInfo] = u
    override def props: Map[String, JsValue] = Map(
      "list-root-org" -> listRoot.map(id => JsString(id.toString)).getOrElse[JsValue](JsNull)
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
    override def props: Map[String, JsValue] = Map(
      "list-root-org" -> JsString(listRoot.toString)
    )
    override def failed: AuditEvent = this.copy(successful = false)
  }

  case class CreateDirectoryAttempt(parentId: UUID, parentType: String, u: Option[AuditedUserInfo] = None, successful: Boolean = false, plugin: Option[DirectoryPlugin] = None) extends AuditEvent with FailedEventFactory {
    override def userInfo: Option[AuditedUserInfo] = u
    override def props: Map[String, JsValue] = Map(
      "parent" -> Json.obj(
        "id" -> parentId.toString,
        "type" -> parentType
      )
    ) ++ plugin.map(p => Map[String,JsValue](
      "plugin" -> Json.obj(
        "id" -> p.id,
        "name" -> p.name,
        "type" -> p.getClass.getName
      )
    )).getOrElse(Map.empty)
    override def failed: AuditEvent = this.copy(successful = false)
  }

  case class GetCurrentOrgAttempt(userInfo: Option[AuditedUserInfo] = None, successful: Boolean = false) extends AuditEvent with FailedEventFactory {
    override def props: Map[String, JsValue] = Map(
      "successful" -> JsBoolean(successful)
    )
    override def failed: AuditEvent = this.copy(successful = false)
  }

  case class LookupOrgAttempt(fqon: String, userInfo: Option[AuditedUserInfo], rh: RequestHeader) extends AuditEvent {
    val successful: Boolean = true
    override def props: Map[String, JsValue] = Map(
      "fqon" -> JsString(fqon)
    )
  }

  case class Failed401(event: AuditEvent, rh: RequestHeader) extends AuditEvent {
    val successful: Boolean = false
    override def userInfo: Option[AuditedUserInfo] = None
    override def eventType: String = event.eventType
    override def props: Map[String, JsValue] = Map(
      "failure-reason" -> JsString("401-unauthorized"),
      "presented-identifier" -> (GestaltHeaderAuthentication.extractAuthToken(rh) match {
        case Some(GestaltBearerCredentials(_)) => JsString("token")
        case Some(GestaltBasicCredentials(username, _)) => JsString(username)
        case None => JsNull
      })
    ) ++ event.props
  }

  case class FailedWrongOrg(event: AuditEvent, rh: RequestHeader) extends AuditEvent {
    val successful: Boolean = false
    override def userInfo: Option[AuditedUserInfo] = None
    override def eventType: String = event.eventType
    override def props: Map[String, JsValue] = Map(
      "failure-reason" -> JsString("403-authed-against-wrong-org")
    ) ++ event.props
  }

  case class FailedConflict(event: AuditEvent, rh: RequestHeader, msg: String) extends AuditEvent {
    val successful: Boolean = false
    override def userInfo: Option[AuditedUserInfo] = None
    override def eventType: String = event.eventType
    override def props: Map[String, JsValue] = Map(
      "failure-reason" -> JsString("409-conflict"),
      "message" -> JsString(msg)
    ) ++ event.props
  }

  case class FailedNotFound(event: AuditEvent) extends AuditEvent {
    val successful: Boolean = false
    override def userInfo: Option[AuditedUserInfo] = event.userInfo
    override def eventType: String = event.eventType
    override def props: Map[String, JsValue] = Map(
      "failure-reason" -> JsString("404-resource-not-found")
    ) ++ event.props
  }

  case class FailedBadRequest(event: AuditEvent, msg: Option[String] = None) extends AuditEvent {
    val successful: Boolean = false
    override def userInfo: Option[AuditedUserInfo] = event.userInfo
    override def eventType: String = event.eventType
    override def props: Map[String, JsValue] = Map(
      "failure-reason" -> JsString("400-bad-request"),
      "description" -> JsString(msg.getOrElse("none"))
    ) ++ event.props
  }

  case class FailedGeneric(event: AuditEvent, ex: Throwable) extends AuditEvent {
    val successful: Boolean = false
    override def userInfo: Option[AuditedUserInfo] = event.userInfo
    override def eventType: String = event.eventType
    override def props: Map[String, JsValue] = Map(
      "failure-reason" -> JsString(ex.getClass().getName),
      "description" -> JsString(ex.getMessage)
    ) ++ event.props
  }

  def mapExceptionToFailedEvent(ex: Throwable, event: AuditEvent)(implicit request: RequestHeader): AuditEvent = {
    ex match {
      case e: ConflictException => FailedConflict(event, request, ex.getMessage)
      case _                    => FailedGeneric(event, ex)
    }
  }

}

