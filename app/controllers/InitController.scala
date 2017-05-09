package controllers

import javax.inject.Inject

import play.api.libs.json._
import play.api.mvc._
import com.galacticfog.gestalt.security.{Init, InitRequest}
import com.galacticfog.gestalt.security.api.GestaltAPIKey
import com.galacticfog.gestalt.security.api.errors.UnknownAPIException
import com.galacticfog.gestalt.security.api.json.JsonImports._
import com.galacticfog.gestalt.security.data.APIConversions._

import scala.util.{Failure, Success}

class InitController @Inject() ( init: Init ) extends Controller with ControllerHelpers {

  def checkInit = Action { implicit request =>
    init.isInit match {
      case Success(isinit) =>
        Ok(Json.obj(
          "initialized" -> isinit
        ))
      case Failure(e) => handleError(request, e)
    }
  }

  def initialize() = Action(parse.json) { implicit request =>
    val ir = validateBody[InitRequest]
    val keys = init.doInit(ir)
    renderTry[Seq[GestaltAPIKey]](Ok)( keys map {_.map {k => k: GestaltAPIKey} } )
  }

}
