package com.galacticfog.gestalt.security.data.model

import scalikejdbc.specs2.mutable.AutoRollback
import org.specs2.mutable._
import scalikejdbc._


class GroupMembershipRepositorySpec extends Specification {

  "GroupMembershipRepository" should {

    val gmr = GroupMembershipRepository.syntax("gmr")

    "find by primary keys" in new AutoRollback {
      val maybeFound = GroupMembershipRepository.find("MyString", "MyString")
      maybeFound.isDefined should beTrue
    }
    "find by where clauses" in new AutoRollback {
      val maybeFound = GroupMembershipRepository.findBy(sqls.eq(gmr.accountId, "MyString"))
      maybeFound.isDefined should beTrue
    }
    "find all records" in new AutoRollback {
      val allResults = GroupMembershipRepository.findAll()
      allResults.size should be_>(0)
    }
    "count all records" in new AutoRollback {
      val count = GroupMembershipRepository.countAll()
      count should be_>(0L)
    }
    "find all by where clauses" in new AutoRollback {
      val results = GroupMembershipRepository.findAllBy(sqls.eq(gmr.accountId, "MyString"))
      results.size should be_>(0)
    }
    "count by where clauses" in new AutoRollback {
      val count = GroupMembershipRepository.countBy(sqls.eq(gmr.accountId, "MyString"))
      count should be_>(0L)
    }
    "create new record" in new AutoRollback {
      val created = GroupMembershipRepository.create(groupId = "MyString", accountId = "MyString")
      created should not beNull
    }
    "save a record" in new AutoRollback {
      val entity = GroupMembershipRepository.findAll().head
      // TODO modify something
      val modified = entity
      val updated = GroupMembershipRepository.save(modified)
      updated should not equalTo(entity)
    }
    "destroy a record" in new AutoRollback {
      val entity = GroupMembershipRepository.findAll().head
      GroupMembershipRepository.destroy(entity)
      val shouldBeNone = GroupMembershipRepository.find("MyString", "MyString")
      shouldBeNone.isDefined should beFalse
    }
  }

}
        