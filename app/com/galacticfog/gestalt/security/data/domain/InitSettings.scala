package com.galacticfog.gestalt.security.data.domain

import java.util.UUID

import com.galacticfog.gestalt.security.data.model._
import scalikejdbc._

import scala.util.{Failure, Try}

object InitSettings extends SQLSyntaxSupport[InitSettingsRepository] {

  override val autoSession = AutoSession

  def getInitSettings()(implicit session: DBSession = autoSession): Try[InitSettingsRepository] = {
    Try {
      InitSettingsRepository.find(0).get
    }
  }


}
