package controllers

import javax.inject.Inject

import com.galacticfog.gestalt.security.{Init, SecurityConfig}
import com.galacticfog.gestalt.security.api.errors.BadRequestException
import com.galacticfog.gestalt.security.data.domain.OrgFactory
import play.api.Logger
import play.api.libs.json.Json
import play.api.routing.Router
import play.api.http._
import play.api.mvc._
import play.api.mvc.Results.BadRequest
import com.galacticfog.gestalt.security.api.json.JsonImports._

class MethodOverrideRequestHandler @Inject() ( errorHandler: HttpErrorHandler,
                                               configuration: HttpConfiguration,
                                               filters: HttpFilters,
                                               router: Router,
                                               initController: InitController,
                                               restController: RESTAPIController,
                                               config: SecurityConfig )
  extends DefaultHttpRequestHandler(router, errorHandler, configuration, filters) {

  val log = Logger(this.getClass)

  val registeredEndpoints = Set(
    "accessTokens",
    "accountStores",
    "accounts",
    "apiKeys",
    "apps",
    "assets",
    "auth",
    "directories",
    "groups",
    "health",
    "info",
    "init",
    "oauth",
    "orgs",
    "rights",
    "sync"
  )

  val overrideParameter: String = config.methodOverrideParameter

  private[this] def topLevelEndpoint(path: String): (String,String) = {
    path.split("/",3) match {
      case Array("", top)       => (top,"")
      case Array("", top, tail) => (top,"/" + tail)
      case _ => ("","")
    }
  }

  override def routeRequest(origRequest: RequestHeader): Option[Handler] = {

    val request = origRequest.getQueryString(overrideParameter).fold(origRequest) {
      overrideMethod =>
        Logger.debug("overriding method " + origRequest.method + " with " + overrideMethod)
        // need to find the appropriate handler, and wrap it so that it sees the modified request
        origRequest.copy(method = overrideMethod.toUpperCase, queryString = origRequest.queryString - overrideParameter)
    }

    log.debug(request.toString)

    (request.method, request.path) match {
      case ("GET", "/init") => Some(initController.checkInit)
      case ("POST", "/init") => Some(initController.initialize())
      case ("GET", "/health") => Some(restController.getHealth)
      case ("GET", "/info") => Some(restController.info)
      case (_,_) if !Init.isInit => Some(Action { BadRequest(Json.toJson(BadRequestException(
        request.path,
        message = "service it not initialized",
        developerMessage = "The service has not been initialized. See the documentation on how to perform initialization."
      )))})
      case (_, path) => {
        val (top,tail) = topLevelEndpoint(path)
        if (registeredEndpoints.contains(top)) super.routeRequest(request)
        else OrgFactory.findByFQON(top) match {
          case Some(org) =>
            super.routeRequest(request.copy(path = s"/orgs/${org.id}${tail}"))
          case None =>
            Logger.debug(s"top level path not mappable as fqon: ${top}")
            GestaltHeaderAuthentication.authenticateHeader(request) match {
              case Some(_) => None // default 404
              case None => Some(Action { GestaltHeaderAuthentication.onUnauthorized(request) })
            }
        }
      }
    }
  }

}
