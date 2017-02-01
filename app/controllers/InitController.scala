package controllers

import javax.inject.Inject
import play.api.libs.json._
import play.api.mvc._
import com.galacticfog.gestalt.security.{Init, InitRequest}
import com.galacticfog.gestalt.security.api.GestaltAPIKey
import com.galacticfog.gestalt.security.api.json.JsonImports._
import com.galacticfog.gestalt.security.data.APIConversions._

class InitController @Inject() ( init: Init ) extends Controller with ControllerHelpers {

  def checkInit = Action {
    Ok(Json.obj(
      "initialized" -> init.isInit
    ))
  }

  def initialize() = Action(parse.json) { implicit request =>
    val ir = validateBody[InitRequest]
    val keys = init.doInit(ir)
    renderTry[Seq[GestaltAPIKey]](Ok)( keys map {_.map {k => k: GestaltAPIKey} } )
  }

}
