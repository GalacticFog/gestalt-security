package com.galacticfog.gestalt.security.actors

import javax.inject._

import akka.actor.{Actor, ActorLogging}
import com.galacticfog.gestalt.security.SecurityConfig

import scala.concurrent.duration._

@Singleton
case class RateLimitingActor @Inject() (config: SecurityConfig) extends Actor with ActorLogging {
  import RateLimitingActor._

  import context.dispatcher
  val reset = context.system.scheduler.schedule(config.rateLimiting.periodInMinutes minutes, config.rateLimiting.periodInMinutes minutes, self, ClearHistory)

  val history = scala.collection.mutable.Map[(String,String), Int]()

  override def postStop() = reset.cancel()

  override def receive: Receive = {
    case TokenGrantRateLimitCheck(appId, username) =>
      val num = history.getOrElse((appId,username),0) + 1
      history.put( (appId,username), num )
      log.debug(s"(${appId},${username}) set to $num")
      if (num <= config.rateLimiting.attemptsPerPeriod)
        sender ! RequestAccepted
      else
        sender ! RequestDenied
    case ClearHistory =>
      log.debug("clearing requests in RateLimitingActor")
      history.clear()
    case m =>
      log.warning(s"unknown message: ${m.toString}")
  }
}

case object RateLimitingActor {

  final val ACTOR_NAME = "rateLimitingActor"

  /**
    * Check rate limiting
    * @param appId String representation (may be empty) for the corresponding appId
    * @param username Username (shouldn't be empty)
    */
  case class TokenGrantRateLimitCheck(appId: String, username: String)
  case object RequestAccepted
  case object RequestDenied

  case object ClearHistory
}
