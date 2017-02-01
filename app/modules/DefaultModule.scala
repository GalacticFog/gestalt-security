package modules

import com.galacticfog.gestalt.security.data.domain.{AccountStoreMappingService, DefaultAccountStoreMappingServiceImpl}
import com.google.inject.AbstractModule

class DefaultModule extends AbstractModule {
  override def configure(): Unit = {
    bind(classOf[AccountStoreMappingService]).to(classOf[DefaultAccountStoreMappingServiceImpl])
  }
}
