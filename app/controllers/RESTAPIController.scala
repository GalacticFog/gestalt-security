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

  val NEW_ORG_OWNER_RIGHTS = Seq(
    CREATE_ORG,
    DELETE_ORG,
    CREATE_ACCOUNT,
    DELETE_ACCOUNT
  )

  ////////////////////////////////////////////////////////
  // Org methods
  ////////////////////////////////////////////////////////

  def fqonToOrgUUID(fqon: String): Option[UUID] = OrgFactory.findByFQON(fqon) map {_.id.asInstanceOf[UUID]}

  def getParentOrg(childOrgId: UUID): Option[UUID] = {OrgFactory.findByOrgId(childOrgId)}.flatMap{_.parent}.map{_.asInstanceOf[UUID]}

  def getOrgByFQON(fqon: String) = AuthenticatedAction(fqonToOrgUUID(fqon)) { implicit request =>
    OrgFactory.findByFQON(fqon) match {
      case Some(org) => Ok(Json.toJson[GestaltOrg](org))
      case None => throw new ResourceNotFoundException(
        resource = request.path,
        message = "could not locate current org",
        developerMessage = "Could not locate a default organization for the authenticate API user."
      )
    }
  }

  def orgAuthByUUID(orgId: UUID) = AuthenticatedAction(Some(orgId)) { implicit request =>
    val accountId = request.user.identity.id.asInstanceOf[UUID]
    val groups = GroupFactory.listAccountGroups(orgId = request.user.orgId, accountId = accountId)
    val rights = RightGrantFactory.listRights(appId = request.user.serviceAppId, accountId = accountId)
    val ar = GestaltAuthResponse(account = request.user.identity, groups = groups map {g => g:GestaltGroup}, rights = rights map {r => r:GestaltRightGrant}, orgId = request.user.orgId)
    Ok(Json.toJson(ar))
  }

  def orgAuth(fqon: String) = AuthenticatedAction(fqonToOrgUUID(fqon)) { implicit request =>
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

  def createOrg(parentOrgId: UUID) = AuthenticatedAction(Some(parentOrgId)).async(parse.json) { implicit request =>
    val account = request.user.identity
    val accountId = account.id.asInstanceOf[UUID]
    val rights = RightGrantFactory.listRights(appId = request.user.serviceAppId, accountId = request.user.identity.id.asInstanceOf[UUID])
    OrgFactory.findByOrgId(parentOrgId) match {
      case Some(parentOrg) if rights.exists(_.grantName == CREATE_ORG) =>
        // TODO: this should be wrapped in a transaction
        Future {
          val t = for {
            create <- Try {
              request.body.as[GestaltOrgCreate]
            }
            // create org
            newOrg <- OrgFactory.create(parentOrgId = parentOrgId, name = create.orgName, fqon = create.orgName + "." + parentOrg.fqon)
                      .recoverWith{case _ => Failure(CreateConflictException(resource = request.path,message="error creating new sub org",developerMessage = "Error creating new sub org. Most likely a conflict with an existing org of the same name."))}
            newOrgId = newOrg.id.asInstanceOf[UUID]
            // create system app
            newApp <- AppFactory.create(orgId = newOrgId, name = newOrgId + "-system-app", isServiceOrg = true)
            newAppId = newApp.id.asInstanceOf[UUID]
            // create admins group
            newGroup <- GroupFactory.create(name = newOrgId + "-users-group", dirId = account.dirId.asInstanceOf[UUID])
            newGroupId = newGroup.id.asInstanceOf[UUID]
            // put user in group
            newGroupAssign <- Try {
              GroupMembershipRepository.create(accountId = accountId, groupId = newGroupId)
            }
            // give admin rights to new account
            newRights <- RightGrantFactory.addRightsToAccount(appId = newAppId, accountId = accountId, rights = NEW_ORG_OWNER_RIGHTS)
            // map group to new system app
            newMapping <- AppFactory.mapGroupToApp(appId = newAppId, groupId = newGroupId, defaultAccountStore = true)
          } yield Created(Json.toJson[GestaltOrg](newOrg))
          t.get
        }
      case Some(_) => throw new ForbiddenAPIException(
        message = "Forbidden",
        developerMessage = "Forbidden. API credentials did not correspond to the parent organization or the account did not have sufficient permissions."
      )
      case None => throw new ResourceNotFoundException(
        resource = request.path,
        message = "parent org does not exist",
        developerMessage = "Could not create sub-org because the parent org does not exist. Make sure to use the organization ID and not the organizatio name."
      )
    }
  }

  def createAccountInOrg(parentOrgId: UUID) = AuthenticatedAction(Some(parentOrgId)).async(parse.json) { implicit request =>
    val account = request.user.identity
    val serviceAppId = request.user.serviceAppId
    val rights = RightGrantFactory.listRights(appId = serviceAppId, accountId = request.user.identity.id.asInstanceOf[UUID])
    OrgFactory.findByOrgId(parentOrgId) match {
      case Some(parentOrg) if rights.exists(_.grantName == CREATE_ACCOUNT) =>
        // TODO: this should be wrapped in a transaction
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
        developerMessage = "Could not create sub-org because the parent org does not exist. Make sure to use the organization ID and not the organizatio name."
      )
    }
  }

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
          developerMessage = "It is not permissable to delete the root organization. Check that you specified the intended org id."
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

  def createOrgDirectory(orgId: UUID) = AuthenticatedAction(None)(parse.json) { implicit request =>
    ???
  }

  def listOrgDirectories(orgId: UUID) = AuthenticatedAction(None) { implicit request =>
    Ok(Json.toJson[Seq[GestaltDirectory]](DirectoryFactory.listByOrgId(orgId) map { d => d: GestaltDirectory }))
  }

  def listOrgApps(orgId: UUID) = AuthenticatedAction(None) { implicit request =>
    Ok(Json.toJson[Seq[GestaltApp]](AppFactory.listByOrgId(orgId) map { a => a: GestaltApp }))
  }

  ////////////////////////////////////////////////////////
  // App methods
  ////////////////////////////////////////////////////////

  def createOrgApp(orgId: UUID) = AuthenticatedAction(None)(parse.json) { implicit request: Request[JsValue] =>
    (request.body \ "appName").asOpt[String] match {
      case None => throw new BadRequestException(
        resource = request.path,
        message = "payload did not include application name",
        developerMessage = "JSON payload did not include application name \"appName\""
      )
      case Some(appName) => {
        AppFactory.findByAppName(orgId,appName) match {
          case Success(app) => throw new CreateConflictException(
            resource = request.path,
            message = "app already exists in org",
            developerMessage = "An application with the specified name already exists in the specified organization. Select a different application name."
          )
          case Failure(e) => {
              ???
              // Created(Json.toJson[GestaltApp](GestaltAppRepository.create(appId = SecureIdGenerator.genId62(AppFactory.APP_ID_LEN), appName = appName, orgId = orgId)))
          }
        }
      }
    }
  }

  def getAppById(appId: UUID) = AuthenticatedAction(None) { implicit request =>
    AppFactory.findByAppId(appId) match {
      case Some(app) => Ok(Json.toJson[GestaltApp](app))
      case None => throw new ResourceNotFoundException(
        resource = request.path,
        message = "could not locate requested app",
        developerMessage = "Could not locate the requested application. Make sure to use the application ID and not the application name."
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

  def getAccountGrant(appId: UUID, username: String, grantName: String) = AuthenticatedAction(None) { implicit request =>
    AccountFactory.getAppGrant(appId,username,grantName) match {
      case Success(right) => Ok(Json.toJson[GestaltRightGrant](right))
      case Failure(e) => throw e
    }
  }

  def deleteRightGrant(appId: UUID, username: String, grantName: String) = AuthenticatedAction(None) { implicit request =>
    AccountFactory.deleteAppGrant(appId,username,grantName) match {
      case Success(wasDeleted) => Ok(Json.toJson(DeleteResult(wasDeleted)))
      case Failure(e) => throw e
    }
  }

  def updateRightGrant(appId: UUID, username: String, grantName: String) = AuthenticatedAction(None)(parse.json) { implicit request: Request[JsValue] =>
    AccountFactory.updateAppGrant(appId,username,grantName,request.body) match {
      case Success(grant) => Ok(Json.toJson[GestaltRightGrant](grant))
      case Failure(e) => throw e
    }
  }

  def createAppAccount(appId: UUID) = AuthenticatedAction(None)(parse.json) {  implicit request =>
    ???
  }

  def appAuth(appId: UUID) = AuthenticatedAction(None)(parse.json) {  implicit request: Request[JsValue] =>
    val attempt = for {
      app <- AppFactory.findByAppId(appId)
      appId = app.id.asInstanceOf[UUID]
      account <- AccountFactory.authenticate(app.id.asInstanceOf[UUID], request.body)
      accountId = account.id.asInstanceOf[UUID]
      rights = RightGrantFactory.listRights(appId = appId, accountId = accountId)
      groups = Seq[UserGroupRepository]()
    } yield (account,rights,groups,app)
    attempt match {
      case None => throw new ForbiddenAPIException(
        message = "failed to authenticate application account",
        developerMessage = "Specified credentials did not authenticate an account on the specified application. Ensure that the application ID is correct and that the credentials correspond to an assigned account."
      )
      case Some((acc,rights,groups,app)) => Ok(Json.toJson[GestaltAuthResponse](GestaltAuthResponse(acc, groups map {g => g:GestaltGroup}, rights map {r => r:GestaltRightGrant}, orgId = app.orgId.asInstanceOf[UUID])))
    }
  }

  def getAccountStores(appId: UUID) = AuthenticatedAction(None)(parse.json) {  implicit request =>
    ???
  }

  ////////////////////////////////////////////////////////
  // Directory methods
  ////////////////////////////////////////////////////////

  def getDirectory(dirId: UUID) = AuthenticatedAction(None)(parse.json) {  implicit request =>
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

  def listDirAccounts(dirId: UUID) = AuthenticatedAction(None)(parse.json) {  implicit request: Request[JsValue] =>
    ???
  }


  def getDirAccount(dirId: UUID, username: String) = AuthenticatedAction(None)(parse.json) {  implicit request =>
    ???
  }

  ////////////////////////////////////////////////////////
  // Account store mapping methods
  ////////////////////////////////////////////////////////

  def createAccountStoreMapping() = AuthenticatedAction(None)(parse.json) {  implicit request =>
    ???
  }

  def getAccountStoreMapping(mapId: UUID) = AuthenticatedAction(None)(parse.json) {  implicit request =>
    ???
  }

  def updateAccountStoreMapping(mapId: UUID) = AuthenticatedAction(None)(parse.json) {  implicit request =>
    ???
  }

  def deleteAccountStoreMapping(mapId: UUID) = AuthenticatedAction(None)(parse.json) {  implicit request =>
    ???
  }

  def listOrgAccounts(orgId: UUID) = play.mvc.Results.TODO

  def listOrgGroups(orgId: UUID) = play.mvc.Results.TODO

  def createGroupInOrg(orgId: UUID) = play.mvc.Results.TODO
}