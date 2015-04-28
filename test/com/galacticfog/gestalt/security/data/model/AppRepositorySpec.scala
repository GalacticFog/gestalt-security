package com.galacticfog.gestalt.security.data.model

import scalikejdbc.specs2.mutable.AutoRollback
import org.specs2.mutable._
import scalikejdbc._


class AppRepositorySpec extends Specification {

  "AppRepository" should {

    val ar = AppRepository.syntax("ar")

    "find by primary keys" in new AutoRollback {
      val maybeFound = AppRepository.find("MyString")
      maybeFound.isDefined should beTrue
    }
    "find by where clauses" in new AutoRollback {
      val maybeFound = AppRepository.findBy(sqls.eq(ar.appId, "MyString"))
      maybeFound.isDefined should beTrue
    }
    "find all records" in new AutoRollback {
      val allResults = AppRepository.findAll()
      allResults.size should be_>(0)
    }
    "count all records" in new AutoRollback {
      val count = AppRepository.countAll()
      count should be_>(0L)
    }
    "find all by where clauses" in new AutoRollback {
      val results = AppRepository.findAllBy(sqls.eq(ar.appId, "MyString"))
      results.size should be_>(0)
    }
    "count by where clauses" in new AutoRollback {
      val count = AppRepository.countBy(sqls.eq(ar.appId, "MyString"))
      count should be_>(0L)
    }
    "create new record" in new AutoRollback {
      val created = AppRepository.create(appId = "MyString", appName = "MyString", orgId = "MyString")
      created should not beNull
    }
    "save a record" in new AutoRollback {
      val entity = AppRepository.findAll().head
      // TODO modify something
      val modified = entity
      val updated = AppRepository.save(modified)
      updated should not equalTo(entity)
    }
    "destroy a record" in new AutoRollback {
      val entity = AppRepository.findAll().head
      AppRepository.destroy(entity)
      val shouldBeNone = AppRepository.find("MyString")
      shouldBeNone.isDefined should beFalse
    }
  }

}
        