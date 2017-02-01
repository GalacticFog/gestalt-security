package modules

import com.galacticfog.gestalt.security.actors.RateLimitingActor
import com.google.inject.AbstractModule
import play.api.libs.concurrent.AkkaGuiceSupport

class RateLimitingActorModule extends AbstractModule with AkkaGuiceSupport {
  override def configure(): Unit = {
    bindActor[RateLimitingActor](RateLimitingActor.ACTOR_NAME)
  }
}
