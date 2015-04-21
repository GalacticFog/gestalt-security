import play.api._
import play.api.mvc._

trait GlobalWithMethodOverriding extends GlobalSettings {
  /**
   * Indicates the query parameter name used to override the HTTP method
   * @return a non-empty string indicating the query parameter. Popular choice is "_method"
   */
  def overrideParameter: String

  override def onRouteRequest(request: RequestHeader): Option[Handler] = {
    request.getQueryString(overrideParameter) match {
      case Some(overrideMethod) => {
        Logger.debug("overriding method " + request.method + " with " + overrideMethod)
        // need to find the appropriate handler, and wrap it so that it sees the modified request
        val modifiedRequest = request.copy(method = overrideMethod.toUpperCase, queryString = request.queryString - overrideParameter)
        super.onRouteRequest(modifiedRequest) match {
          case Some(wrapped: EssentialAction) => Some(new EssentialAction() with RequestTaggingHandler {
            override def apply(req: RequestHeader) = wrapped(modifiedRequest)
            override def tagRequest(request: RequestHeader): RequestHeader = wrapped match {
              case tagging: RequestTaggingHandler => tagging.tagRequest(request)
              case _ => request
            }
          })
          case other => other
        }
      }
      case None => super.onRouteRequest(request)
    }
  }
}
