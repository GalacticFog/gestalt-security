package controllers

import play.api._
import play.api.libs.json._
import play.api.mvc._

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future
import scala.util.{Failure, Success, Try}

case class InitRequest(username: Option[String] = None,
                       password: Option[String] = None,
                       force: Option[Boolean] = None,
                       fixPermissions: Option[Boolean] = None)

case object InitRequest {
  implicit val initRequestFormat = Json.format[InitRequest]
}

object InitController extends Controller with ControllerHelpers {

  var initialized: Boolean = Try {
    false
  } recover {
    case t: Throwable =>
      Logger.warn("error determining initialization",t)
      false
  } get

  def checkInit() = Action {
    Ok(Json.obj(
      "initialized" -> initialized
    ))
  }

  def initialize() = Action(parse.json) { implicit request =>
    val ir = validateBody[InitRequest]
    ???
  }

}
