package com.galacticfog.gestalt.security.test

import java.util.UUID

import com.galacticfog.gestalt.security.api.AccessTokenResponse.BEARER
import com.galacticfog.gestalt.security.api.GestaltToken.ACCESS_TOKEN
import com.galacticfog.gestalt.security.api.{GestaltOrg, _}
import com.galacticfog.gestalt.security.api.errors.{BadRequestException, ConflictException, OAuthError, UnauthorizedAPIException}
import com.galacticfog.gestalt.security.api.json.JsonImports._
import com.galacticfog.gestalt.security.data.APIConversions._
import com.galacticfog.gestalt.security.data.domain._
import com.galacticfog.gestalt.security.data.model.{APICredentialRepository, TokenRepository}
import com.galacticfog.gestalt.security.{EnvConfig, FlywayMigration, InitRequest}
import org.flywaydb.core.Flyway
import org.joda.time.DateTime
import org.specs2.matcher.{Expectable, MatchResult, Matcher}
import play.api.libs.json.Json
import play.api.libs.ws.WS
import play.api.test._

class GroupSpecs extends SpecWithSDK {

  "Org Groups" should {

    lazy val newOrgName = "new-org-for-org-group-testing"
    lazy val newOrg = await(rootOrg.createSubOrg(GestaltOrgCreate(
      name = newOrgName,
      createDefaultUserGroup = true
    )))
    lazy val newOrgApp = await(newOrg.getServiceApp())
    lazy val unmappedGrpFromRootDir = await(rootDir.createGroup(GestaltGroupCreate(
      name = "unmapped-group-in-root-dir"
    )))

    "precheck" in {
      await(rootDir.getGroupByName(unmappedGrpFromRootDir.name)) must beSome(unmappedGrpFromRootDir)
      await(GestaltGroup.getById(unmappedGrpFromRootDir.id)) must beSome(unmappedGrpFromRootDir)
      await(rootDir.listGroups()) must contain(unmappedGrpFromRootDir)
    }

    "not list an unmapped group in the org" in {
      await(newOrg.listGroups) must not contain unmappedGrpFromRootDir
    }

    "not list an unmapped group in the service app" in {
      await(newOrgApp.listGroups) must not contain unmappedGrpFromRootDir
    }

    "fail to get an unmapped group by id in the org" in {
      await(newOrg.getGroupById(unmappedGrpFromRootDir.id)) must beNone
    }

    "fail to get an unmapped group by id in the service app" in {
      await(newOrgApp.getGroupById(unmappedGrpFromRootDir.id)) must beNone
    }

    "fail to get an unmapped group by name in the org" in {
      await(newOrg.getGroupByName(unmappedGrpFromRootDir.name)) must beNone
    }

    "fail to get an unmapped group by name in the service app" in {
      await(newOrgApp.getGroupByName(unmappedGrpFromRootDir.name)) must beNone
    }

    "successfully list/get in the org/app after being mapped to the org" in {
      val mapping = await(newOrg.mapAccountStore(GestaltAccountStoreMappingCreate(
        name = "test-mapping",
        description = None,
        storeType = GROUP,
        accountStoreId = unmappedGrpFromRootDir.id,
        isDefaultAccountStore = false,
        isDefaultGroupStore = false
      )))
      await(newOrg.listAccountStores) must contain( (asm: GestaltAccountStoreMapping) => asm.id == mapping.id )
      await(newOrgApp.listAccountStores) must contain( (asm: GestaltAccountStoreMapping) => asm.id == mapping.id )
      await(newOrg.listGroups) must contain(unmappedGrpFromRootDir)
      await(newOrgApp.listGroups) must contain(unmappedGrpFromRootDir)
      await(newOrg.getGroupById(unmappedGrpFromRootDir.id)) must beSome(unmappedGrpFromRootDir)
      await(newOrgApp.getGroupById(unmappedGrpFromRootDir.id)) must beSome(unmappedGrpFromRootDir)
      await(newOrg.getGroupByName(unmappedGrpFromRootDir.name)) must beNone // not in the default group store
      await(newOrgApp.getGroupByName(unmappedGrpFromRootDir.name)) must beNone // not in the default group store
      await(mapping.delete) must beTrue
      await(GestaltAccountStoreMapping.getById(mapping.id)) must beNone
      await(newOrg.listAccountStores) must not contain( (asm: GestaltAccountStoreMapping) => asm.id == mapping.id )
      await(newOrgApp.listAccountStores) must not contain( (asm: GestaltAccountStoreMapping) => asm.id == mapping.id )
    }

    "successfully list/get in the org/app after being mapped to the service app" in {
      val mapping = await(newOrgApp.mapAccountStore(GestaltAccountStoreMappingCreate(
        name = "test-mapping",
        description = None,
        storeType = GROUP,
        accountStoreId = unmappedGrpFromRootDir.id,
        isDefaultAccountStore = false,
        isDefaultGroupStore = false
      )))
      await(newOrg.listAccountStores) must contain( (asm: GestaltAccountStoreMapping) => asm.id == mapping.id )
      await(newOrgApp.listAccountStores) must contain( (asm: GestaltAccountStoreMapping) => asm.id == mapping.id )
      await(newOrg.listGroups) must contain(unmappedGrpFromRootDir)
      await(newOrgApp.listGroups) must contain(unmappedGrpFromRootDir)
      await(newOrg.getGroupById(unmappedGrpFromRootDir.id)) must beSome(unmappedGrpFromRootDir)
      await(newOrgApp.getGroupById(unmappedGrpFromRootDir.id)) must beSome(unmappedGrpFromRootDir)
      await(newOrg.getGroupByName(unmappedGrpFromRootDir.name)) must beNone // not in the default group store
      await(newOrgApp.getGroupByName(unmappedGrpFromRootDir.name)) must beNone // not in the default group store
      await(mapping.delete) must beTrue
      await(GestaltAccountStoreMapping.getById(mapping.id)) must beNone
      await(newOrg.listAccountStores) must not contain( (asm: GestaltAccountStoreMapping) => asm.id == mapping.id )
      await(newOrgApp.listAccountStores) must not contain( (asm: GestaltAccountStoreMapping) => asm.id == mapping.id )
    }

    lazy val newOrgGrp = await(GestaltOrg.createGroup(newOrg.id, GestaltGroupCreateWithRights(
      name = "new-org-group",
      rights = Some(Seq(GestaltGrantCreate(
        grantName = "grantA"
      ), GestaltGrantCreate(
        grantName = "grantB", grantValue = Some("grantBvalue")
      )))
    )))

    "show up in org after creation" in {
      await(newOrg.getGroupById(newOrgGrp.id)) must beSome(newOrgGrp)
      await(newOrg.getGroupByName(newOrgGrp.name)) must beSome(newOrgGrp)
      await(newOrg.listGroups) must contain(newOrgGrp)
    }

    "list grants provided at creation" in {
      await(GestaltOrg.listGroupGrants(newOrg.id, newOrgGrp.id)) must containTheSameElementsAs(Seq(
        GestaltRightGrant(null, "grantA", None, newOrgApp.id),
        GestaltRightGrant(null, "grantB", Some("grantBvalue"), newOrgApp.id)
      ), (a: GestaltRightGrant,b: GestaltRightGrant) => (a.grantName == b.grantName && a.grantValue == b.grantValue && a.appId == b.appId))
    }

  }

  step({
    server.stop()
  })
}
