package controllers

import javax.inject.Inject

import play.api.libs.json._
import play.api.mvc._
import com.galacticfog.gestalt.security.{Init, InitRequest}
import com.galacticfog.gestalt.security.api.GestaltAPIKey
import com.galacticfog.gestalt.security.api.errors.UnknownAPIException
import com.galacticfog.gestalt.security.api.json.JsonImports._
import com.galacticfog.gestalt.security.data.APIConversions._
import controllers.AuditEvents.{FailedBadRequest, InitAttempt, InitCheck}

import scala.util.{Failure, Success}

class InitController @Inject() ( init: Init, auditer: Auditer ) extends Controller with ControllerHelpers {

  def checkInit = Action { implicit request =>
    init.isInit match {
      case Success(isinit) =>
        auditer(InitCheck(isinit))
        Ok(Json.obj(
          "initialized" -> isinit
        ))
      case Failure(e) => handleError(request, e)
    }
  }

  def initialize() = Action(parse.json) { implicit request =>
    val ir = validateBody[InitRequest]
    val keys = init.doInit(ir)
    keys match {
      case Success(keys) => auditer(InitAttempt(true, "admin keys: " + keys.map(_.apiKey).mkString(",")))
      case Failure(ex) => auditer(FailedBadRequest(InitAttempt(false, ex.getMessage)))
    }
    renderTry[Seq[GestaltAPIKey]](Ok)( keys map {_.map {k => k: GestaltAPIKey} } )
  }

}
