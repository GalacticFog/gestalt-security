package com.galacticfog.gestalt.security.actors

import akka.actor.{Actor, ActorLogging, Props}
import org.joda.time.DateTime
import scala.concurrent.duration._

case class RateLimitingActor(periodLengthMinutes: Int, attemptsPerPeriod: Int) extends Actor with ActorLogging {
  import RateLimitingActor._

  import context.dispatcher
  val reset = context.system.scheduler.schedule(periodLengthMinutes minutes, periodLengthMinutes minutes, self, ClearHistory)

  override def postStop() = reset.cancel()

  override def receive: Receive = {
    case TokenGrantRateLimitCheck(appId, username) =>
      sender ! RequestAccepted
    case ClearHistory => log.debug("clearing requests in RateLimitingActor")
    case _ => log.info("unknown message")
  }
}

case object RateLimitingActor {

  val ACTOR_NAME = "rateLimitingActor"

  /**
    * Check rate limiting
    * @param appId String representation (may be empty) for the corresponding appId
    * @param username Username (shouldn't be empty)
    */
  case class TokenGrantRateLimitCheck(appId: String, username: String)
  case object RequestAccepted
  case object RequestDenied

  case object ClearHistory

  def props(periodLengthMinutes: Int, attemptsPerPeriod: Int): Props =
    Props(RateLimitingActor(periodLengthMinutes = periodLengthMinutes, attemptsPerPeriod = attemptsPerPeriod))
}
