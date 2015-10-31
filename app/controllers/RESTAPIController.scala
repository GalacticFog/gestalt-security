package controllers

import java.util.UUID

import com.galacticfog.gestalt.security.api._
import com.galacticfog.gestalt.security.api.errors._
import com.galacticfog.gestalt.security.data.domain._
import com.galacticfog.gestalt.security.data.model._
import com.galacticfog.gestalt.security.utils.SecureIdGenerator
import play.api.libs.json.{JsError, JsValue, Json}
import play.api.{Logger => log}
import play.api._
import play.api.mvc._
import scalikejdbc.DB
import scala.concurrent.ExecutionContext.Implicits.global
import play.api.Play.current
import com.galacticfog.gestalt.security.data.APIConversions._
import com.galacticfog.gestalt.security.api.json.JsonImports._

import scala.concurrent.Future
import scala.util.{Try, Failure, Success}

case class SecurityControllerInitializationError(msg: String) extends RuntimeException(msg)

object RESTAPIController extends Controller with GestaltHeaderAuthentication {

  val CREATE_ORG = "createOrg"
  val DELETE_ORG = "deleteOrg"
  val CREATE_ACCOUNT = "createAccount"
  val DELETE_ACCOUNT = "deleteAccount"
  val CREATE_DIRECTORY = "createDirectory"
  val DELETE_DIRECTORY = "deleteDirectory"
  val CREATE_APP = "createApp"
  val DELETE_APP = "deleteApp"
  val READ_DIRECTORY = "readDirectory"

  val NEW_ORG_OWNER_RIGHTS = Seq(
    CREATE_ORG,
    DELETE_ORG,
    CREATE_ACCOUNT,
    DELETE_ACCOUNT,
    CREATE_DIRECTORY,
    DELETE_DIRECTORY,
    CREATE_APP,
    DELETE_APP,
    READ_DIRECTORY
  )

  ////////////////////////////////////////////////////////////////
  // Methods for extracting orgId for authentication from context
  ////////////////////////////////////////////////////////////////

  def fqonToOrgUUID(fqon: String): Option[UUID] = OrgFactory.findByFQON(fqon) map {_.id.asInstanceOf[UUID]}

  def getParentOrg(childOrgId: UUID): Option[UUID] = for {
    org <- OrgFactory.findByOrgId(childOrgId)
  } yield org.parent.asInstanceOf[UUID]

  def getAppOrg(appId: UUID): Option[UUID] = for {
    app <- AppFactory.findByAppId(appId)
  } yield app.orgId.asInstanceOf[UUID]

  def getAccountOrg(accountId: UUID): Option[UUID] = {
    for {
      account <- UserAccountRepository.find(accountId)
      dir <- GestaltDirectoryRepository.find(account.dirId)
    } yield dir.orgId.asInstanceOf[UUID]
  }

  def getOrgFromDirectory(dirId: UUID): Option[UUID] = for {
    dir <- GestaltDirectoryRepository.find(dirId)
  } yield dir.orgId.asInstanceOf[UUID]

  def getOrgFromAccountStoreMapping(mapId: UUID): Option[UUID] = ???

  ////////////////////////////////////////////////////////
  // Auth methods
  ////////////////////////////////////////////////////////

  def orgAuthByUUID(orgId: UUID) = AuthenticatedAction(Some(orgId)) { implicit request =>
    val accountId = request.user.identity.id.asInstanceOf[UUID]
    val groups = GroupFactory.listAccountGroups(orgId = request.user.orgId, accountId = accountId)
    val rights = RightGrantFactory.listRights(appId = request.user.serviceAppId, accountId = accountId)
    val ar = GestaltAuthResponse(account = request.user.identity, groups = groups map {g => g:GestaltGroup}, rights = rights map {r => r:GestaltRightGrant}, orgId = request.user.orgId)
    Ok(Json.toJson(ar))
  }

  def orgAuthByFQON(fqon: String) = AuthenticatedAction(fqonToOrgUUID(fqon)) { implicit request =>
    val accountId = request.user.identity.id.asInstanceOf[UUID]
    val groups = GroupFactory.listAccountGroups(orgId = request.user.orgId, accountId = accountId)
    val rights = RightGrantFactory.listRights(appId = request.user.serviceAppId, accountId = accountId)
    val ar = GestaltAuthResponse(account = request.user.identity, groups = groups map {g => g:GestaltGroup}, rights = rights map {r => r:GestaltRightGrant}, request.user.orgId)
    Ok(Json.toJson(ar))
  }

  def apiAuth() = AuthenticatedAction(None) { implicit request =>
    val accountId = request.user.identity.id.asInstanceOf[UUID]
    val groups = GroupFactory.listAccountGroups(orgId = request.user.orgId, accountId = accountId)
    val rights = RightGrantFactory.listRights(appId = request.user.serviceAppId, accountId = accountId)
    val ar = GestaltAuthResponse(account = request.user.identity, groups = groups map {g => g:GestaltGroup}, rights = rights map {r => r:GestaltRightGrant}, request.user.orgId)
    Ok(Json.toJson(ar))
  }

  def appAuth(appId: UUID) = AuthenticatedAction(getAppOrg(appId))(parse.json) {  implicit request: Request[JsValue] =>
    val attempt = for {
      app <- AppFactory.findByAppId(appId)
      appId = app.id.asInstanceOf[UUID]
      account <- AccountFactory.authenticate(app.id.asInstanceOf[UUID], request.body)
      accountId = account.id.asInstanceOf[UUID]
      rights = RightGrantFactory.listRights(appId = appId, accountId = accountId)
      groups = GroupFactory.listAccountGroups(orgId = app.orgId.asInstanceOf[UUID], accountId = accountId)
    } yield (account,rights,groups,app)
    attempt match {
      case None => throw new ForbiddenAPIException(
        message = "failed to authenticate application account",
        developerMessage = "Specified credentials did not authenticate an account on the specified application. Ensure that the application ID is correct and that the credentials correspond to an assigned account."
      )
      case Some((acc,rights,groups,app)) => Ok(Json.toJson[GestaltAuthResponse](GestaltAuthResponse(acc, groups map {g => g:GestaltGroup}, rights map {r => r:GestaltRightGrant}, orgId = app.orgId.asInstanceOf[UUID])))
    }
  }

  ////////////////////////////////////////////////////////
  // Get/List methods
  ////////////////////////////////////////////////////////

  def getHealth() = Action {
    Ok("healthy")
  }

  def getCurrentOrg() = AuthenticatedAction(None) { implicit request =>
    OrgFactory.findByOrgId(request.user.orgId) match {
      case Some(org) => Ok(Json.toJson[GestaltOrg](org))
      case None => throw new ResourceNotFoundException(
        resource = request.path,
        message = "could not locate current org",
        developerMessage = "Could not locate a default organization for the authenticate API user."
      )
    }
  }

  def getOrgByFQON(fqon: String) = AuthenticatedAction(fqonToOrgUUID(fqon)) { implicit request =>
    OrgFactory.findByFQON(fqon) match {
      case Some(org) => Ok(Json.toJson[GestaltOrg](org))
      case None => throw new ResourceNotFoundException(
        resource = request.path,
        message = "could not org",
        developerMessage = "Could not locate an org with the specified FQON."
      )
    }
  }

  def getOrgById(orgId: UUID) = AuthenticatedAction(Some(orgId)) { implicit request =>
    OrgFactory.findByOrgId(orgId) match {
      case Some(org) => Ok(Json.toJson[GestaltOrg](org))
      case None => throw new ResourceNotFoundException(
        resource = request.path,
        message = "could not locate requested org",
        developerMessage = "Could not locate the requested organization. Make sure to use the organization ID and not the organization name."
      )
    }
  }

  def getAppById(appId: UUID) = AuthenticatedAction(getAppOrg(appId)) { implicit request =>
    AppFactory.findByAppId(appId) match {
      case Some(app) => Ok(Json.toJson[GestaltApp](app))
      case None => throw new ResourceNotFoundException(
        resource = request.path,
        message = "could not locate requested app",
        developerMessage = "Could not locate the requested application. Make sure to use the application ID and not the application name."
      )
    }
  }

  def getOrgAccount(orgId: UUID, accountId: UUID) = AuthenticatedAction(Some(orgId)) { implicit request =>
    UserAccountRepository.find(accountId) match {
      case Some(account) => Ok(Json.toJson[GestaltAccount](account))
      case None => throw new ResourceNotFoundException(
        resource = request.path,
        message = "could not locate requested account",
        developerMessage = "Could not locate the requested account. Make sure to use the account ID and not the username."
      )
    }
  }

  def getOrgGroup(orgId: java.util.UUID, groupId: java.util.UUID) = AuthenticatedAction(Some(orgId)) { implicit request =>
    UserGroupRepository.find(groupId) match {
      case Some(group) => Ok(Json.toJson[GestaltGroup](group))
      case None => throw new ResourceNotFoundException(
        resource = request.path,
        message = "could not locate requested group",
        developerMessage = "Could not locate the requested group. Make sure to use the group ID and not the name."
      )
    }
  }

  def getAccount(accountId: UUID) = AuthenticatedAction(getAccountOrg(accountId)) { implicit request =>
    UserAccountRepository.find(accountId) match {
      case Some(account) => Ok(Json.toJson[GestaltAccount](account))
      case None => throw new ResourceNotFoundException(
        resource = request.path,
        message = "could not locate requested account",
        developerMessage = "Could not locate the requested account. Make sure to use the account ID and not the username."
      )
    }
  }

  def getAccountStores(appId: UUID) = AuthenticatedAction(getAppOrg(appId)) {  implicit request =>
    Ok(Json.toJson(AppFactory.listAccountStoreMappings(appId) map {mapping => mapping: GestaltAccountStoreMapping}))
  }

  def getOrgAccountStores(orgId: java.util.UUID) = AuthenticatedAction(Some(orgId)) { implicit request =>
    Ok(Json.toJson(AppFactory.listAccountStoreMappings(appId = request.user.serviceAppId) map {mapping => mapping: GestaltAccountStoreMapping}))
  }

  def getAccountGrant(appId: UUID, username: String, grantName: String) = AuthenticatedAction(None) { implicit request =>
    ???
    //    AccountFactory.getAppGrant(appId,username,grantName) match {
    //      case Success(right) => Ok(Json.toJson[GestaltRightGrant](right))
    //      case Failure(e) => throw e
    //    }
  }

  def getDirectory(dirId: UUID) = AuthenticatedAction(getOrgFromDirectory(dirId)) {  implicit request =>
    GestaltDirectoryRepository.find(dirId) match {
      case Some(dir) =>
        Ok(Json.toJson[GestaltDirectory](dir))
      case None => throw new ResourceNotFoundException(
        resource = request.path,
        message = "could not locate requested directory",
        developerMessage = "Could not locate the requested directory. Make sure to use the directory ID."
      )
    }
  }

  def getDirAccount(dirId: UUID, username: String) = AuthenticatedAction(getOrgFromDirectory(dirId))(parse.json) {  implicit request =>
    ???
  }

  def getAccountStoreMapping(mapId: UUID) = AuthenticatedAction(getOrgFromAccountStoreMapping(mapId)) {  implicit request =>
    ???
  }

  def listAllOrgs() = AuthenticatedAction(None) { implicit request =>
    Ok(Json.toJson(OrgFactory.getOrgTree(request.user.orgId).map{o => o: GestaltOrg}))
  }

  def listOrgTree(orgId: java.util.UUID) = AuthenticatedAction(Some(orgId)) { implicit request =>
    GestaltOrgRepository.find(orgId) match {
      case Some(org) =>
        Ok(Json.toJson(OrgFactory.getOrgTree(org.id.asInstanceOf[UUID]).map{o => o: GestaltOrg}))
      case None => throw new ResourceNotFoundException(
        resource = request.path,
        message = "could not locate requested org",
        developerMessage = "Could not locate the requested org. Make sure to use the org ID."
      )
    }
  }

  def listAppAccounts(appId: UUID) = AuthenticatedAction(None) { implicit request =>
    AppFactory.findByAppId(appId) match {
      case Some(app) => Ok(Json.toJson[Seq[GestaltAccount]](AccountFactory.listByAppId(app.id.asInstanceOf[UUID]) map { a => a: GestaltAccount }))
      case None => throw new ResourceNotFoundException(
        resource = request.path,
        message = "could not locate requested app",
        developerMessage = "Could not locate the requested application. Make sure to use the application ID and not the application name."
      )
    }
  }

  def listAccountRights(appId: UUID, username: String) = AuthenticatedAction(None) { implicit request =>
    AccountFactory.listAppGrants(appId,username) match {
      case Success(rights) => Ok(Json.toJson[Seq[GestaltRightGrant]](rights map { r => r: GestaltRightGrant}) )
      case Failure(e) => throw e
    }
  }

  def listOrgDirectories(orgId: UUID) = AuthenticatedAction(None) { implicit request =>
    Ok(Json.toJson[Seq[GestaltDirectory]](DirectoryFactory.listByOrgId(orgId) map { d => d: GestaltDirectory }))
  }

  def listOrgApps(orgId: UUID) = AuthenticatedAction(None) { implicit request =>
    Ok(Json.toJson[Seq[GestaltApp]](AppFactory.listByOrgId(orgId) map { a => a: GestaltApp }))
  }

  def listDirAccounts(dirId: UUID) = AuthenticatedAction(None)(parse.json) {  implicit request: Request[JsValue] =>
    ???
  }

  def listOrgAccounts(orgId: UUID) = AuthenticatedAction(Some(orgId)) { implicit request =>
    Ok( Json.toJson(
      AccountFactory.listByAppId(request.user.serviceAppId).map {a => a: GestaltAccount}
    ) )
  }

  // all groups in all directories contained in the account
  def listOrgGroupMappings(orgId: UUID) = AuthenticatedAction(Some(orgId)) { implicit request =>
    GestaltOrgRepository.find(orgId) match {
      case Some(org) => Ok( Json.toJson[Seq[GestaltGroup]](
        GroupFactory.listAppGroupMappings(appId = request.user.serviceAppId).map {g => g: GestaltGroup}
      ) )
      case None => throw new ResourceNotFoundException(
        resource = request.path,
        message = "could not locate requested org",
        developerMessage = "Could not locate the requested organization. Make sure to use the organization ID and not the organization name."
      )
    }
  }

  def listAccountGroups(accountId: java.util.UUID) = AuthenticatedAction(getAccountOrg(accountId)) { implicit request =>
    UserAccountRepository.find(accountId) match {
      case Some(account) => Ok( Json.toJson[Seq[GestaltGroup]](
        GroupFactory.listAccountGroups(orgId = request.user.orgId, accountId = accountId).map {g => g: GestaltGroup}
      ) )
      case None => throw new ResourceNotFoundException(
        resource = request.path,
        message = "could not locate requested account",
        developerMessage = "Could not locate the requested account. Make sure to use the account ID and not the account username."
      )
    }
  }

  ////////////////////////////////////////////////////////
  // Create methods
  ////////////////////////////////////////////////////////

  def createOrg(parentOrgId: UUID) = AuthenticatedAction(Some(parentOrgId))(parse.json) { implicit request =>
    val account = request.user.identity
    val accountId = account.id.asInstanceOf[UUID]
    val rights = RightGrantFactory.listRights(appId = request.user.serviceAppId, accountId = request.user.identity.id.asInstanceOf[UUID])
    OrgFactory.findByOrgId(parentOrgId) match {
      case Some(parentOrg) if rights.exists(_.grantName == CREATE_ORG) =>
        // TODO: this should be wrapped in a transaction
        val t = for {
          create <- Try { request.body.as[GestaltOrgCreate] }
            .recoverWith {
              case _ => Failure(BadRequestException(
                resource = request.path,
                message = "invalid payload",
                developerMessage = "Payload could not be parsed; was expecting JSON representation of GestaltOrgCreate"))
            }
          // create org
          newOrg <- OrgFactory.create(parentOrg = parentOrg, name = create.orgName)
            .recoverWith{
              case _ => Failure(CreateConflictException(
                resource = request.path,
                message="error creating new sub org",
                developerMessage = "Error creating new sub org. Most likely a conflict with an existing org of the same name."))
            }
          newOrgId = newOrg.id.asInstanceOf[UUID]
          // create system app
          newApp <- AppFactory.create(orgId = newOrgId, name = newOrgId + "-system-app", isServiceOrg = true)
          newAppId = newApp.id.asInstanceOf[UUID]
          // create admins group
          adminGroup <- GroupFactory.create(name = newOrg.fqon + "-admins", dirId = account.dirId.asInstanceOf[UUID])
          adminGroupId = adminGroup.id.asInstanceOf[UUID]
          // create users group
          usersGroup <- GroupFactory.create(name = newOrg.fqon + "-users", dirId = account.dirId.asInstanceOf[UUID])
          usersGroupId = usersGroup.id.asInstanceOf[UUID]
          // put user in group
          newGroupAssign <- Try {
            GroupMembershipRepository.create(accountId = accountId, groupId = adminGroupId)
            GroupMembershipRepository.create(accountId = accountId, groupId = usersGroupId)
          }
          // give admin rights to new account
          newRights <- RightGrantFactory.addRightsToGroup(appId = newAppId, groupId = adminGroupId, rights = NEW_ORG_OWNER_RIGHTS)
          // map group to new system app
          adminMapping <- AppFactory.mapGroupToApp(appId = newAppId, groupId = adminGroupId, defaultAccountStore = false)
          usersMapping <- AppFactory.mapGroupToApp(appId = newAppId, groupId = usersGroupId, defaultAccountStore = true)
        } yield Created(Json.toJson[GestaltOrg](newOrg))
        t.get
      case Some(_) => throw new ForbiddenAPIException(
        message = "Forbidden",
        developerMessage = "Forbidden. API credentials did not correspond to the parent organization or the account did not have sufficient permissions."
      )
      case None => throw new ResourceNotFoundException(
        resource = request.path,
        message = "parent org does not exist",
        developerMessage = "Could not create sub-org because the parent org does not exist. Make sure to use the organization ID and not the organization name."
      )
    }
  }

  def createAccountInOrg(parentOrgId: UUID) = AuthenticatedAction(Some(parentOrgId)).async(parse.json) { implicit request =>
    val account = request.user.identity
    val serviceAppId = request.user.serviceAppId
    val rights = RightGrantFactory.listRights(appId = serviceAppId, accountId = request.user.identity.id.asInstanceOf[UUID])
    OrgFactory.findByOrgId(parentOrgId) match {
      case Some(parentOrg) if rights.exists(_.grantName == CREATE_ACCOUNT) =>
        Future {
          val t = for {
            create <- Try{request.body.as[GestaltAccountCreateWithRights]}
              .recoverWith {case _ => Failure(BadRequestException(resource = request.path, message = "invalid payload", developerMessage = "Payload could not be parsed; was expecting JSON representation of GestaltAccountCreateWithRights"))}
            newAccount <- AppFactory.createAccountInApp(appId = serviceAppId, create)
          } yield Created(Json.toJson[GestaltAccount](newAccount))
          t.get
        }
      case Some(_) => throw new ForbiddenAPIException(
        message = "Forbidden",
        developerMessage = "Forbidden. API credentials did not correspond to the parent organization or the account did not have sufficient permissions."
      )
      case None => throw new ResourceNotFoundException(
        resource = request.path,
        message = "parent org does not exist",
        developerMessage = "Could not create sub-org because the parent org does not exist. Make sure to use the organization ID and not the organization name."
      )
    }
  }

  def createOrgApp(orgId: UUID) = AuthenticatedAction(Some(orgId)).async(parse.json) { implicit request =>
    val rights = RightGrantFactory.listRights(appId = request.user.serviceAppId, accountId = request.user.identity.id.asInstanceOf[UUID])
    OrgFactory.findByOrgId(orgId) match {
      case Some(org) if rights.exists(_.grantName == CREATE_APP) =>
        Future {
          val t = for {
            create <- Try{request.body.as[GestaltAppCreate]}
            newApp <- AppFactory.create(orgId = orgId, name = create.name, isServiceOrg = false)
              .recoverWith{case _ => Failure(CreateConflictException(resource = request.path,message="error creating new app",developerMessage = "Error creating new app. Most likely a conflict with an existing app of the same name in the org."))}
          } yield Created(Json.toJson[GestaltApp](newApp))
          t.get
        }
      case Some(_) => throw new ForbiddenAPIException(
        message = "Forbidden",
        developerMessage = "Forbidden. API credentials did not correspond to the parent organization or the account did not have sufficient permissions."
      )
      case None => throw new ResourceNotFoundException(
        resource = request.path,
        message = "org does not exist",
        developerMessage = "Cannot create application because the specified org does not exist."
      )
    }
  }

  def createOrgDirectory(orgId: UUID) = AuthenticatedAction(None)(parse.json) { implicit request =>
    ???
  }

  def createAppAccount(appId: UUID) = AuthenticatedAction(None)(parse.json) {  implicit request =>
    ???
  }

  def createDirAccount(dirId: UUID) = AuthenticatedAction(None)(parse.json) {  implicit request: Request[JsValue] =>
    ???
    //    val userCreate = request.body.validate[GestaltAccountCreate]
    //    userCreate.fold
    //      errors => throw new BadRequestException(
    //        resource = request.path,
    //        message = "error parsing JSON",
    //        developerMessage = "Error parsing JSON payload. Expected a GestaltAccountCreate object."
    //      ),
    //      user => {
    //       AppFactory.createUserInApp(appId, user) match {
    //         case Success(account) =>  Created( Json.toJson[GestaltAccount](account) )
    //         case Failure(ex) => throw new (ex)
    //       }
    //      }
    //    )
  }

  def createAccountStoreMapping() = AuthenticatedAction(None)(parse.json) {  implicit request =>
    ???
  }

  def createGroupInOrg(orgId: UUID) = play.mvc.Results.TODO

  ////////////////////////////////////////////////////////
  // Update methods
  ////////////////////////////////////////////////////////

  def updateAccountStoreMapping(mapId: UUID) = AuthenticatedAction(None)(parse.json) {  implicit request =>
    ???
  }

  def updateRightGrant(appId: UUID, username: String, grantName: String) = AuthenticatedAction(None)(parse.json) { implicit request: Request[JsValue] =>
    AccountFactory.updateAppGrant(appId,username,grantName,request.body) match {
      case Success(grant) => Ok(Json.toJson[GestaltRightGrant](grant))
      case Failure(e) => throw e
    }
  }

  ////////////////////////////////////////////////////////
  // Delete methods
  ////////////////////////////////////////////////////////

  def deleteOrgById(orgId: java.util.UUID) = AuthenticatedAction(Some(orgId)).async { implicit request =>
    val rights = RightGrantFactory.listRights(appId = request.user.serviceAppId, accountId = request.user.identity.id.asInstanceOf[UUID])
    OrgFactory.findByOrgId(orgId) match {
      case Some(org) if rights.exists(_.grantName == DELETE_ORG) && org.parent.isDefined =>
        Future {
          GestaltOrgRepository.destroy(org)
        } map { _ => NoContent }
      case Some(org) if org.parent.isEmpty =>
        throw new BadRequestException(
          resource = request.path,
          message = "cannot delete root org",
          developerMessage = "It is not permissible to delete the root organization. Check that you specified the intended org id."
        )
      case Some(_) => throw new ForbiddenAPIException(
        message = "Forbidden",
        developerMessage = "Forbidden. API credentials did not correspond to the parent organization or the account did not have sufficient permissions."
      )
      case None =>  throw new ResourceNotFoundException(
        resource = request.path,
        message = "org does not exist",
        developerMessage = "Could not delete the target org because it does not exist or the provided credentials do not have rights to see it"
      )
    }
  }

  def deleteAppById(appId: java.util.UUID) = AuthenticatedAction(getAppOrg(appId)).async { implicit request =>
    val rights = RightGrantFactory.listRights(appId = request.user.serviceAppId, accountId = request.user.identity.id.asInstanceOf[UUID])
    AppFactory.findByAppId(appId) match {
      case Some(app) if rights.exists(_.grantName == DELETE_APP) && app.isServiceApp == false =>
        Future {
          GestaltAppRepository.destroy(app)
        } map { _ => NoContent }
      case Some(app) if app.isServiceApp == true =>
        throw new BadRequestException(
          resource = request.path,
          message = "cannot delete service app",
          developerMessage = "It is not permissible to delete the current service app for an organization. Verify that this is the app that you want to delete and select a new service app for the organization and try again, or delete the organization."
        )
      case Some(_) => throw new ForbiddenAPIException(
        message = "Forbidden",
        developerMessage = "Forbidden. API credentials did not correspond to the parent organization or the account did not have sufficient permissions."
      )
      case None =>  throw new ResourceNotFoundException(
        resource = request.path,
        message = "app does not exist",
        developerMessage = "Could not delete the target app because it does not exist or the provided credentials do not have rights to see it"
      )
    }
  }

  def disableAccount(accountId: java.util.UUID) = AuthenticatedAction(getAccountOrg(accountId)).async { implicit request =>
    val rights = RightGrantFactory.listRights(appId = request.user.serviceAppId, accountId = request.user.identity.id.asInstanceOf[UUID])
    UserAccountRepository.find(accountId) match {
      case Some(account) if rights.exists(_.grantName == DELETE_ACCOUNT) && account.disabled == false && account.id != request.user.identity.id =>
        Future {
          UserAccountRepository.save(account.copy(disabled = true))
        } map { _ => NoContent }
      case Some(account) if account.disabled == true => throw new BadRequestException(
          resource = request.path,
          message = "account has already been deleted",
          developerMessage = "The account has already been deleted and cannot therefore be deleted again."
        )
      case Some(account) if account.id != request.user.identity.id => throw new BadRequestException(
          resource = request.path,
          message = "cannot delete self",
          developerMessage = "The authenticated account is the same as the account targetted by the delete operation. You cannot delete yourself. Get someone else to delete you."
        )
      case Some(_) => throw new ForbiddenAPIException(
        message = "Forbidden",
        developerMessage = "Forbidden. API credentials did not correspond to the parent organization or the account did not have sufficient permissions."
      )
      case None =>  throw new ResourceNotFoundException(
        resource = request.path,
        message = "org does not exist",
        developerMessage = "Could not delete the target org because it does not exist or the provided credentials do not have rights to see it"
      )
    }
  }

  def deleteRightGrant(appId: UUID, username: String, grantName: String) = AuthenticatedAction(None) { implicit request =>
    AccountFactory.deleteAppGrant(appId,username,grantName) match {
      case Success(wasDeleted) => Ok(Json.toJson(DeleteResult(wasDeleted)))
      case Failure(e) => throw e
    }
  }

  def deleteAccountStoreMapping(mapId: UUID) = AuthenticatedAction(None)(parse.json) {  implicit request =>
    ???
  }


}