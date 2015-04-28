package com.galacticfog.gestalt.security.data.model

import scalikejdbc.specs2.mutable.AutoRollback
import org.specs2.mutable._
import scalikejdbc._


class UserGroupRepositorySpec extends Specification {

  "UserGroupRepository" should {

    val ugr = UserGroupRepository.syntax("ugr")

    "find by primary keys" in new AutoRollback {
      val maybeFound = UserGroupRepository.find("MyString")
      maybeFound.isDefined should beTrue
    }
    "find by where clauses" in new AutoRollback {
      val maybeFound = UserGroupRepository.findBy(sqls.eq(ugr.groupId, "MyString"))
      maybeFound.isDefined should beTrue
    }
    "find all records" in new AutoRollback {
      val allResults = UserGroupRepository.findAll()
      allResults.size should be_>(0)
    }
    "count all records" in new AutoRollback {
      val count = UserGroupRepository.countAll()
      count should be_>(0L)
    }
    "find all by where clauses" in new AutoRollback {
      val results = UserGroupRepository.findAllBy(sqls.eq(ugr.groupId, "MyString"))
      results.size should be_>(0)
    }
    "count by where clauses" in new AutoRollback {
      val count = UserGroupRepository.countBy(sqls.eq(ugr.groupId, "MyString"))
      count should be_>(0L)
    }
    "create new record" in new AutoRollback {
      val created = UserGroupRepository.create(groupId = "MyString", groupName = "MyString")
      created should not beNull
    }
    "save a record" in new AutoRollback {
      val entity = UserGroupRepository.findAll().head
      // TODO modify something
      val modified = entity
      val updated = UserGroupRepository.save(modified)
      updated should not equalTo(entity)
    }
    "destroy a record" in new AutoRollback {
      val entity = UserGroupRepository.findAll().head
      UserGroupRepository.destroy(entity)
      val shouldBeNone = UserGroupRepository.find("MyString")
      shouldBeNone.isDefined should beFalse
    }
  }

}
        