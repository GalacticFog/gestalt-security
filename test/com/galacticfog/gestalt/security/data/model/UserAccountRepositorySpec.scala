package com.galacticfog.gestalt.security.data.model

import scalikejdbc.specs2.mutable.AutoRollback
import org.specs2.mutable._
import scalikejdbc._


class UserAccountRepositorySpec extends Specification {

  "UserAccountRepository" should {

    val uar = UserAccountRepository.syntax("uar")

    "find by primary keys" in new AutoRollback {
      val maybeFound = UserAccountRepository.find("MyString")
      maybeFound.isDefined should beTrue
    }
    "find by where clauses" in new AutoRollback {
      val maybeFound = UserAccountRepository.findBy(sqls.eq(uar.accountId, "MyString"))
      maybeFound.isDefined should beTrue
    }
    "find all records" in new AutoRollback {
      val allResults = UserAccountRepository.findAll()
      allResults.size should be_>(0)
    }
    "count all records" in new AutoRollback {
      val count = UserAccountRepository.countAll()
      count should be_>(0L)
    }
    "find all by where clauses" in new AutoRollback {
      val results = UserAccountRepository.findAllBy(sqls.eq(uar.accountId, "MyString"))
      results.size should be_>(0)
    }
    "count by where clauses" in new AutoRollback {
      val count = UserAccountRepository.countBy(sqls.eq(uar.accountId, "MyString"))
      count should be_>(0L)
    }
    "create new record" in new AutoRollback {
      val created = UserAccountRepository.create(accountId = "MyString", username = "MyString", email = "MyString", firstName = "MyString", lastName = "MyString", secret = "MyString", salt = "MyString", hashMethod = "MyString")
      created should not beNull
    }
    "save a record" in new AutoRollback {
      val entity = UserAccountRepository.findAll().head
      // TODO modify something
      val modified = entity
      val updated = UserAccountRepository.save(modified)
      updated should not equalTo(entity)
    }
    "destroy a record" in new AutoRollback {
      val entity = UserAccountRepository.findAll().head
      UserAccountRepository.destroy(entity)
      val shouldBeNone = UserAccountRepository.find("MyString")
      shouldBeNone.isDefined should beFalse
    }
  }

}
        