package filters

import javax.inject.Inject

import akka.stream.Materializer
import play.api.Logger
import play.api.http.DefaultHttpFilters
import play.api.mvc.{Filter, RequestHeader, Result}
import play.filters.cors.CORSFilter

import scala.concurrent.{ExecutionContext, Future}

class LoggingFilter @Inject() (implicit val mat: Materializer, ec: ExecutionContext) extends Filter {

  def apply(nextFilter: RequestHeader => Future[Result])
           (requestHeader: RequestHeader): Future[Result] = {

    val startTime = System.currentTimeMillis
    Logger.info(s"req-${requestHeader.id}: ${requestHeader.method} ${requestHeader.uri}")

    nextFilter(requestHeader).map { result =>

      val endTime = System.currentTimeMillis
      val requestTime = endTime - startTime

      Logger.info(s"req-${requestHeader.id}: ${requestHeader.method} ${requestHeader.uri} returned ${result.header.status} in ${requestTime}ms ")

      result.withHeaders("Request-Time" -> requestTime.toString)
    }
  }
}

class Filters @Inject() (corsFilter: CORSFilter, loggingFilter: LoggingFilter)
  extends DefaultHttpFilters(corsFilter,loggingFilter)
