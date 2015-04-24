package com.galacticfog.gestalt.security.data.model

import scalikejdbc.specs2.mutable.AutoRollback
import org.specs2.mutable._
import scalikejdbc._


class GestaltOrgRepositorySpec extends Specification {

  "GestaltOrgRepository" should {

    val gor = GestaltOrgRepository.syntax("gor")

    "find by primary keys" in new AutoRollback {
      val maybeFound = GestaltOrgRepository.find("MyString")
      maybeFound.isDefined should beTrue
    }
    "find by where clauses" in new AutoRollback {
      val maybeFound = GestaltOrgRepository.findBy(sqls.eq(gor.orgId, "MyString"))
      maybeFound.isDefined should beTrue
    }
    "find all records" in new AutoRollback {
      val allResults = GestaltOrgRepository.findAll()
      allResults.size should be_>(0)
    }
    "count all records" in new AutoRollback {
      val count = GestaltOrgRepository.countAll()
      count should be_>(0L)
    }
    "find all by where clauses" in new AutoRollback {
      val results = GestaltOrgRepository.findAllBy(sqls.eq(gor.orgId, "MyString"))
      results.size should be_>(0)
    }
    "count by where clauses" in new AutoRollback {
      val count = GestaltOrgRepository.countBy(sqls.eq(gor.orgId, "MyString"))
      count should be_>(0L)
    }
    "create new record" in new AutoRollback {
      val created = GestaltOrgRepository.create(orgId = "MyString", orgName = "MyString")
      created should not beNull
    }
    "save a record" in new AutoRollback {
      val entity = GestaltOrgRepository.findAll().head
      // TODO modify something
      val modified = entity
      val updated = GestaltOrgRepository.save(modified)
      updated should not equalTo(entity)
    }
    "destroy a record" in new AutoRollback {
      val entity = GestaltOrgRepository.findAll().head
      GestaltOrgRepository.destroy(entity)
      val shouldBeNone = GestaltOrgRepository.find("MyString")
      shouldBeNone.isDefined should beFalse
    }
  }

}
        