package com.galacticfog.gestalt.security.model

import scalikejdbc.specs2.mutable.AutoRollback
import org.specs2.mutable._
import scalikejdbc._


class OrgSpec extends Specification {

  "Org" should {

    val o = OrgRepository.syntax("o")

    "find by primary keys" in new AutoRollback {
      val maybeFound = OrgRepository.find("MyString")
      maybeFound.isDefined should beTrue
    }
    "find by where clauses" in new AutoRollback {
      val maybeFound = OrgRepository.findBy(sqls.eq(o.orgId, "MyString"))
      maybeFound.isDefined should beTrue
    }
    "find all records" in new AutoRollback {
      val allResults = OrgRepository.findAll()
      allResults.size should be_>(0)
    }
    "count all records" in new AutoRollback {
      val count = OrgRepository.countAll()
      count should be_>(0L)
    }
    "find all by where clauses" in new AutoRollback {
      val results = OrgRepository.findAllBy(sqls.eq(o.orgId, "MyString"))
      results.size should be_>(0)
    }
    "count by where clauses" in new AutoRollback {
      val count = OrgRepository.countBy(sqls.eq(o.orgId, "MyString"))
      count should be_>(0L)
    }
    "create new record" in new AutoRollback {
      val created = OrgRepository.create(orgId = "MyString")
      created should not beNull
    }
    "save a record" in new AutoRollback {
      val entity = OrgRepository.findAll().head
      // TODO modify something
      val modified = entity
      val updated = OrgRepository.save(modified)
      updated should not equalTo(entity)
    }
    "destroy a record" in new AutoRollback {
      val entity = OrgRepository.findAll().head
      OrgRepository.destroy(entity)
      val shouldBeNone = OrgRepository.find("MyString")
      shouldBeNone.isDefined should beFalse
    }
  }

}
        