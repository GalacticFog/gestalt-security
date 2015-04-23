package controllers

import play.api.mvc.RequestHeader

/**
 * Created by cgbaker on 4/23/15.
 */
trait GestaltDigestAuthentication {

  /**
   * Retrieve the connected user email.
   */
  def authToken(request: RequestHeader) = request.headers.get("WWW-Authenticate")

  def currentApiAccount(implicit request: RequestHeader) : Option[User] = authToken(request).flatMap { User.findByEmail(_) }

}
