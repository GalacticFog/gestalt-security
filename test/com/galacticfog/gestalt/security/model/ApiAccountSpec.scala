package com.galacticfog.gestalt.security.model

import com.galacticfog.gestalt.security.data.model.APIAccountRepository
import scalikejdbc.specs2.mutable.AutoRollback
import org.specs2.mutable._
import scalikejdbc._


class ApiAccountSpec extends Specification {

  "ApiAccount" should {

    val aa = APIAccountRepository.syntax("aa")

    "find by primary keys" in new AutoRollback {
      val maybeFound = APIAccountRepository.find("MyString")
      maybeFound.isDefined should beTrue
    }
    "find by where clauses" in new AutoRollback {
      val maybeFound = APIAccountRepository.findBy(sqls.eq(aa.apiKey, "MyString"))
      maybeFound.isDefined should beTrue
    }
    "find all records" in new AutoRollback {
      val allResults = APIAccountRepository.findAll()
      allResults.size should be_>(0)
    }
    "count all records" in new AutoRollback {
      val count = APIAccountRepository.countAll()
      count should be_>(0L)
    }
    "find all by where clauses" in new AutoRollback {
      val results = APIAccountRepository.findAllBy(sqls.eq(aa.apiKey, "MyString"))
      results.size should be_>(0)
    }
    "count by where clauses" in new AutoRollback {
      val count = APIAccountRepository.countBy(sqls.eq(aa.apiKey, "MyString"))
      count should be_>(0L)
    }
    "create new record" in new AutoRollback {
      val created = APIAccountRepository.create(apiKey = "MyString", apiSecret = "MyString", defaultOrg = "MyString")
      created should not beNull
    }
    "save a record" in new AutoRollback {
      val entity = APIAccountRepository.findAll().head
      // TODO modify something
      val modified = entity
      val updated = APIAccountRepository.save(modified)
      updated should not equalTo(entity)
    }
    "destroy a record" in new AutoRollback {
      val entity = APIAccountRepository.findAll().head
      APIAccountRepository.destroy(entity)
      val shouldBeNone = APIAccountRepository.find("MyString")
      shouldBeNone.isDefined should beFalse
    }
  }

}
        