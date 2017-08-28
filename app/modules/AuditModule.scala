package modules

import java.text.SimpleDateFormat
import java.time.{ZoneId, ZonedDateTime}
import java.util.{Date, SimpleTimeZone}

import com.google.inject.AbstractModule
import controllers.{AuditEvent, Auditer}
import net.codingwell.scalaguice.ScalaModule
import org.joda.time.{DateTime, DateTimeZone}
import org.joda.time.format.ISODateTimeFormat
import play.api.Logger
import play.api.http.HeaderNames
import play.api.libs.json.{JsObject, JsString, Json}
import play.api.mvc.RequestHeader

class AuditModule extends AbstractModule with ScalaModule {

  val auditLogger = Logger("audit")

  override def configure(): Unit = {
    bind[Auditer].toInstance(new Auditer {
      override def apply(event: AuditEvent)(implicit request: RequestHeader): Unit = {
        val out = Json.obj(
          "timestamp" -> new DateTime(new Date()).withZone(DateTimeZone.UTC).toString(),
          "from" -> request.remoteAddress,
          "method" -> request.method,
          "uri" -> request.uri
        ) ++ JsObject(Seq(
           request.headers.get(HeaderNames.X_FORWARDED_FOR).map("x-forwarded-for" -> JsString(_)),
           request.headers.get(HeaderNames.FORWARDED).map("forwarded" -> JsString(_))
        ).flatten) ++ event.toJson
        auditLogger.info( out.toString )
      }
    })
  }
}
