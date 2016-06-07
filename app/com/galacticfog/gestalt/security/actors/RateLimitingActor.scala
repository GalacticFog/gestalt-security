package com.galacticfog.gestalt.security.actors

import akka.actor.{Actor, ActorLogging, Props}
import org.joda.time.DateTime
import scala.concurrent.duration._

case class RateLimitingActor(periodLengthMinutes: Int, attemptsPerPeriod: Int) extends Actor with ActorLogging {
  import RateLimitingActor._

  import context.dispatcher
  val reset = context.system.scheduler.schedule(periodLengthMinutes minutes, periodLengthMinutes minutes, self, ClearHistory)

  val history = scala.collection.mutable.Map[(String,String), Int]()

  override def postStop() = reset.cancel()

  override def receive: Receive = {
    case TokenGrantRateLimitCheck(appId, username) =>
      val num = history.getOrElse((appId,username),0) + 1
      history.put( (appId,username), num )
      log.info(s"(${appId},${username}) set to $num")
      if (num <= attemptsPerPeriod)
        sender ! RequestAccepted
      else
        sender ! RequestDenied
    case ClearHistory =>
      log.info("clearing requests in RateLimitingActor")
      history.clear()
    case m =>
      log.warning(s"unknown message: ${m.toString}")
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
