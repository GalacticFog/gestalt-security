package com.galacticfog.gestalt.security.data.model

import scalikejdbc.specs2.mutable.AutoRollback
import org.specs2.mutable._
import scalikejdbc._


class RightGrantRepositorySpec extends Specification {

  "RightGrantRepository" should {

    val rgr = RightGrantRepository.syntax("rgr")

    "find by primary keys" in new AutoRollback {
      val maybeFound = RightGrantRepository.find("MyString")
      maybeFound.isDefined should beTrue
    }
    "find by where clauses" in new AutoRollback {
      val maybeFound = RightGrantRepository.findBy(sqls.eq(rgr.grantId, "MyString"))
      maybeFound.isDefined should beTrue
    }
    "find all records" in new AutoRollback {
      val allResults = RightGrantRepository.findAll()
      allResults.size should be_>(0)
    }
    "count all records" in new AutoRollback {
      val count = RightGrantRepository.countAll()
      count should be_>(0L)
    }
    "find all by where clauses" in new AutoRollback {
      val results = RightGrantRepository.findAllBy(sqls.eq(rgr.grantId, "MyString"))
      results.size should be_>(0)
    }
    "count by where clauses" in new AutoRollback {
      val count = RightGrantRepository.countBy(sqls.eq(rgr.grantId, "MyString"))
      count should be_>(0L)
    }
    "create new record" in new AutoRollback {
      val created = RightGrantRepository.create(grantId = "MyString", appId = "MyString", grantName = "MyString")
      created should not beNull
    }
    "save a record" in new AutoRollback {
      val entity = RightGrantRepository.findAll().head
      // TODO modify something
      val modified = entity
      val updated = RightGrantRepository.save(modified)
      updated should not equalTo(entity)
    }
    "destroy a record" in new AutoRollback {
      val entity = RightGrantRepository.findAll().head
      RightGrantRepository.destroy(entity)
      val shouldBeNone = RightGrantRepository.find("MyString")
      shouldBeNone.isDefined should beFalse
    }
  }

}
        