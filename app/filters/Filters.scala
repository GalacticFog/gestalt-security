package filters

import javax.inject.Inject

import akka.stream.Materializer
import play.api.Logger
import play.api.http.DefaultHttpFilters
import play.api.mvc.{Filter, RequestHeader, Result}
import play.filters.cors.CORSFilter

import scala.concurrent.{ExecutionContext, Future}

class LoggingFilter @Inject() (implicit val mat: Materializer, ec: ExecutionContext) extends Filter {

  val X_REQUEST_ID = "X-Request-Id"
  val X_RESPONSE_TIME = "X-Response-Time"

  val accessLog = Logger("access")

  def apply(nextFilter: RequestHeader => Future[Result])
           (requestHeader: RequestHeader): Future[Result] = {

    val startTime = System.currentTimeMillis
    val requestId = requestHeader.headers.get(X_REQUEST_ID)
    val linepre = s"req-${requestHeader.id}: method=${requestHeader.method} uri=${requestHeader.uri} remote-address=${requestHeader.remoteAddress}" +
      requestId.map(" (" + X_REQUEST_ID + ": " + _ + ")").getOrElse("")
    accessLog.debug(linepre)

    nextFilter(requestHeader).map { result =>

      val endTime = System.currentTimeMillis
      val requestTime = endTime - startTime

      accessLog.info(s"${linepre} response-code=${result.header.status} response-time=${requestTime}ms ")

      val responseHeaders = Seq(
        X_RESPONSE_TIME -> requestTime.toString
      ) ++ requestId.map(X_REQUEST_ID -> _)
      result.withHeaders(responseHeaders:_*)
    }

  }
}

class Filters @Inject() (corsFilter: CORSFilter, loggingFilter: LoggingFilter)
  extends DefaultHttpFilters(corsFilter,loggingFilter)
