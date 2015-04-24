import controllers.RESTAPIController
import play.api._
import play.api.mvc._

// Note: this is in the default package.
object Global extends GlobalSettings with GlobalWithMethodOverriding {
  /**
   * Indicates the query parameter name used to override the HTTP method
   * @return a non-empty string indicating the query parameter. Popular choice is "_method"
   */
  override def overrideParameter: String = "_method"

  override def onStart(app: Application): Unit = {
    RESTAPIController.initShiro()
    InitialData.insert()
  }

}

/**
 * Initial set of data to be imported
 * in the sample application.
 */
object InitialData {

  def insert() = {
//
//    if(User.findAll.isEmpty) {
//
//      Seq(
//        User("admin@example.com", "admin")
//      ).foreach(User.create)
//    }
//
  }

}
