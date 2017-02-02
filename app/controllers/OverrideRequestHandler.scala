package controllers

import javax.inject._

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
import HttpVerbs._

@Singleton
class OverrideRequestHandler @Inject()( errorHandler: HttpErrorHandler,
                                        configuration: HttpConfiguration,
                                        filters: HttpFilters,
                                        router: Router,
                                        config: SecurityConfig,
                                        init: Init )
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

  val passthroughActions = Set(
    (GET,  "/init"),
    (POST, "/init"),
    (GET,  "/health"),
    (GET,  "/info")
  )

  val overrideParameter: String = config.methodOverrideParameter

  private[this] def topLevelEndpoint(path: String): (String,String) = {
    path.stripPrefix("/").split("/",2) match {
      case Array(top)       => (top,"")
      case Array(top, tail) => (top,"/" + tail)
      case _ => ("","")
    }
  }

  override def routeRequest(origRequest: RequestHeader): Option[Handler] = {

    // allow override of http method
    val request = origRequest.getQueryString(overrideParameter).fold(origRequest) {
      overrideMethod =>
        Logger.debug("overriding method " + origRequest.method + " with " + overrideMethod)
        // need to find the appropriate handler, and wrap it so that it sees the modified request
        origRequest.copy(method = overrideMethod.toUpperCase, queryString = origRequest.queryString - overrideParameter)
    }

    log.debug(request.toString)

    // intercept routing in case database is not init
    // also, convert /:fqon/* to /orgs/orgid/*
    (request.method, request.path) match {
      case r if passthroughActions contains r => super.routeRequest(request)
      case (_,_) if !init.isInit => Some(Action { BadRequest(Json.toJson(BadRequestException(
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
              case None    => Some(Action { GestaltHeaderAuthentication.onUnauthorized(request) })
            }
        }
      }
    }
  }

}
