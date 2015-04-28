package com.galacticfog.gestalt.security.data.model

import scalikejdbc.specs2.mutable.AutoRollback
import org.specs2.mutable._
import scalikejdbc._


class AppUserStoreRepositorySpec extends Specification {

  "AppUserStoreRepository" should {

    val ausr = AppUserStoreRepository.syntax("ausr")

    "find by primary keys" in new AutoRollback {
      val maybeFound = AppUserStoreRepository.find("MyString", "MyString")
      maybeFound.isDefined should beTrue
    }
    "find by where clauses" in new AutoRollback {
      val maybeFound = AppUserStoreRepository.findBy(sqls.eq(ausr.appId, "MyString"))
      maybeFound.isDefined should beTrue
    }
    "find all records" in new AutoRollback {
      val allResults = AppUserStoreRepository.findAll()
      allResults.size should be_>(0)
    }
    "count all records" in new AutoRollback {
      val count = AppUserStoreRepository.countAll()
      count should be_>(0L)
    }
    "find all by where clauses" in new AutoRollback {
      val results = AppUserStoreRepository.findAllBy(sqls.eq(ausr.appId, "MyString"))
      results.size should be_>(0)
    }
    "count by where clauses" in new AutoRollback {
      val count = AppUserStoreRepository.countBy(sqls.eq(ausr.appId, "MyString"))
      count should be_>(0L)
    }
    "create new record" in new AutoRollback {
      val created = AppUserStoreRepository.create(appId = "MyString", groupId = "MyString")
      created should not beNull
    }
    "save a record" in new AutoRollback {
      val entity = AppUserStoreRepository.findAll().head
      // TODO modify something
      val modified = entity
      val updated = AppUserStoreRepository.save(modified)
      updated should not equalTo(entity)
    }
    "destroy a record" in new AutoRollback {
      val entity = AppUserStoreRepository.findAll().head
      AppUserStoreRepository.destroy(entity)
      val shouldBeNone = AppUserStoreRepository.find("MyString", "MyString")
      shouldBeNone.isDefined should beFalse
    }
  }

}
        