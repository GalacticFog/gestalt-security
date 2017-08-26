package controllers

import java.util.UUID

import com.galacticfog.gestalt.security.api.GestaltAccount
import com.galacticfog.gestalt.security.data.model.UserAccountRepository
import play.api.libs.json.{JsNull, JsObject, JsValue, Json}
import play.api.mvc.RequestHeader

trait Auditer {
  def apply(event: AuditEvent)(implicit request: RequestHeader): Unit
}

trait FailedEventFactory {
  def apply(orgId: Option[UUID]): AuditEvent
}

case class AuditedUserInfo(id: UUID, username: String, email: String)

trait AuditEvent {
  def userInfo: Option[AuditedUserInfo]
  def props: Map[String,String]

  def toJson: JsObject = Json.obj(
    "event-type" -> this.getClass.getSimpleName
  ) ++ userInfo.map(
    ui => Json.obj(
      "user" -> Json.obj(
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
    def props = Map(
      "successful" -> successful.toString,
      "presented-identifier" -> presentedIdentifier
    )
  }
  case object TokenIssueAttempt {
    def failed(u: AuditedUserInfo, pi: String)  = TokenIssueAttempt(Some(u),false,pi)
    def failed(pi: String)  = TokenIssueAttempt(None,false,pi)
    def success(u: AuditedUserInfo, pi: String) = TokenIssueAttempt(Some(u),true ,pi)
  }

  case class InitCheck(wasAlreadyInit: Boolean) extends AuditEvent {
    override def userInfo: Option[AuditedUserInfo] = None
    override def props: Map[String, String] = Map(
      "was-already-init" -> wasAlreadyInit.toString
    )
  }

  case class InitAttempt(successful: Boolean, message: String) extends AuditEvent {
    override def userInfo: Option[AuditedUserInfo] = None
    override def props: Map[String, String] = Map(
      "successful" -> successful.toString,
      "message" -> message
    )
  }

  case class SyncAttempt(u: Option[AuditedUserInfo], rootOrgId: Option[UUID], successful: Boolean) extends AuditEvent {
    override def userInfo: Option[AuditedUserInfo] = u
    override def props: Map[String, String] = Map(
      "root-org-id" -> rootOrgId.map(_.toString).getOrElse[String]("n/a"),
      "successful" -> successful.toString
    )
  }

  case object SyncAttempt extends FailedEventFactory {
    override def apply(orgId: Option[UUID]): AuditEvent = SyncAttempt(None, orgId, false)
    def success(ui: AuditedUserInfo, orgId: UUID): AuditEvent = SyncAttempt(Some(ui), Some(orgId), true)
  }


}

