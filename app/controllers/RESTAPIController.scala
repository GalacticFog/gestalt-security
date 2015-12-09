package controllers

import java.util.UUID

import app.Global
import com.galacticfog.gestalt.io.util.PatchOp
import com.galacticfog.gestalt.io.util.PatchUpdate._
import com.galacticfog.gestalt.security.api._
import com.galacticfog.gestalt.security.api.errors._
import com.galacticfog.gestalt.security.data.domain._
import com.galacticfog.gestalt.security.data.model._
import play.api.libs.json.{JsSuccess, JsError, JsValue, Json}
import play.api._
import play.api.mvc._
import scala.concurrent.ExecutionContext.Implicits.global
import play.api.Play.current
import com.galacticfog.gestalt.security.data.APIConversions._
import com.galacticfog.gestalt.security.api.json.JsonImports._
import OrgFactory.Rights._

import scala.concurrent.Future
import scala.util.{Failure, Success, Try}

case class SecurityControllerInitializationError(msg: String) extends RuntimeException(msg)

object RESTAPIController extends Controller with GestaltHeaderAuthentication {

  val services = Global.services

  ////////////////////////////////////////////////////////////////
  // Methods for extracting orgId for authentication from context
  ////////////////////////////////////////////////////////////////

  def rootFromCredentials(requestHeader: RequestHeader): Option[UUID] = {
    val keyRoot = for {
      authToken <- GestaltHeaderAuthentication.extractAuthToken(requestHeader)
      apiKey <- APICredentialFactory.findByAPIKey(authToken.username)
    } yield apiKey.orgId.asInstanceOf[UUID]
    keyRoot orElse rootOrg
  }

  def fqonToOrgUUID(fqon: String): Option[UUID] = OrgFactory.findByFQON(fqon) map {
    _.id.asInstanceOf[UUID]
  }

  def rootOrg(): Option[UUID] = OrgFactory.getRootOrg() map {
    _.id.asInstanceOf[UUID]
  }

  def getParentOrg(childOrgId: UUID): Option[UUID] = for {
    org <- OrgFactory.findByOrgId(childOrgId)
  } yield org.parent.asInstanceOf[UUID]

  def getGrantOrg(grantId: UUID): Option[UUID] = for {
    grant <- RightGrantRepository.find(grantId)
    app <- AppFactory.findByAppId(grant.appId.asInstanceOf[UUID])
  } yield app.orgId.asInstanceOf[UUID]

  def getAppOrg(appId: UUID): Option[UUID] = for {
    app <- AppFactory.findByAppId(appId)
  } yield app.orgId.asInstanceOf[UUID]

  def getAccountOrg(accountId: UUID): Option[UUID] = {
    for {
      account <- AccountFactory.findEnabled(accountId)
      dir <- DirectoryFactory.find(account.dirId.asInstanceOf[UUID])
    } yield dir.orgId
  }

  def getOrgFromDirectory(dirId: UUID): Option[UUID] = for {
    dir <- DirectoryFactory.find(dirId)
  } yield dir.orgId

  def getOrgFromAccountStoreMapping(mapId: UUID): Option[UUID] = for {
    mapping <- services.accountStoreMappingService.find(mapId)
    app <- AppFactory.find(mapping.appId.asInstanceOf[UUID])
  } yield app.orgId.asInstanceOf[UUID]

  ////////////////////////////////////////////////////////
  // Auth methods
  ////////////////////////////////////////////////////////

  def orgAuthByUUID(orgId: UUID) = AuthenticatedAction(Some(orgId)) { implicit request =>
    val accountId = request.user.identity.id.asInstanceOf[UUID]
    val groups = GroupFactory.listAccountGroups(orgId = request.user.orgId, accountId = accountId)
    val rights = RightGrantFactory.listAccountRights(appId = request.user.serviceAppId, accountId = accountId)
    val ar = GestaltAuthResponse(account = request.user.identity, groups = groups map { g => g: GestaltGroup }, rights = rights map { r => r: GestaltRightGrant }, orgId = request.user.orgId)
    Ok(Json.toJson(ar))
  }

  def orgAuthByFQON(fqon: String) = AuthenticatedAction(fqonToOrgUUID(fqon)) { implicit request =>
    val accountId = request.user.identity.id.asInstanceOf[UUID]
    val groups = GroupFactory.listAccountGroups(orgId = request.user.orgId, accountId = accountId)
    val rights = RightGrantFactory.listAccountRights(appId = request.user.serviceAppId, accountId = accountId)
    val ar = GestaltAuthResponse(account = request.user.identity, groups = groups map { g => g: GestaltGroup }, rights = rights map { r => r: GestaltRightGrant }, request.user.orgId)
    Ok(Json.toJson(ar))
  }

  def apiAuth() = AuthenticatedAction(rootFromCredentials _) { implicit request =>
    val accountId = request.user.identity.id.asInstanceOf[UUID]
    val groups = GroupFactory.listAccountGroups(orgId = request.user.orgId, accountId = accountId)
    val rights = RightGrantFactory.listAccountRights(appId = request.user.serviceAppId, accountId = accountId)
    val ar = GestaltAuthResponse(account = request.user.identity, groups = groups map { g => g: GestaltGroup }, rights = rights map { r => r: GestaltRightGrant }, request.user.orgId)
    Ok(Json.toJson(ar))
  }

  def appAuth(appId: UUID) = AuthenticatedAction(getAppOrg(appId))(parse.json) { implicit request =>
    requireAuthorization(AUTHENTICATE_ACCOUNTS)
    val app = AppFactory.findByAppId(appId)
    if (app.isEmpty) throw new UnknownAPIException(
      code = 500,
      resource = request.path,
      message = "error looking up application",
      developerMessage = "The application could not be located, but the API request was authenticated. This suggests a problem with the database; please try again or contact support."
    )
    val account = AccountFactory.authenticate(app.get.id.asInstanceOf[UUID], request.body)
    account match {
      case None => NotFound(Json.toJson(ResourceNotFoundException(
        resource = request.path,
        message = "invalid username or password",
        developerMessage = "The specified credentials do not match any in the application's account stores."
      )))
      case Some(account) =>
        val accountId = account.id.asInstanceOf[UUID]
        val rights = RightGrantFactory.listAccountRights(appId = appId, accountId = accountId)
        val groups = GroupFactory.listAccountGroups(orgId = app.get.orgId.asInstanceOf[UUID], accountId = accountId)
        Ok(Json.toJson[GestaltAuthResponse](GestaltAuthResponse(
          account,
          groups map { g => g: GestaltGroup },
          rights map { r => r: GestaltRightGrant },
          orgId = app.get.orgId.asInstanceOf[UUID]
        )))
    }
  }

  ////////////////////////////////////////////////////////
  // Get/List methods
  ////////////////////////////////////////////////////////

  def getHealth() = Action.async {
    Future {
      Try {
        rootOrg
      } match {
        case Success(orgId) if orgId.isDefined =>
          Ok("healthy")
        case Success(orgId) if orgId.isEmpty =>
          NotFound("could not find root org; check database version")
        case Failure(ex) =>
          InternalServerError("not able to connect to database")
      }
    }
  }

  def getCurrentOrg() = AuthenticatedAction(rootFromCredentials _) { implicit request =>
    OrgFactory.findByOrgId(request.user.orgId) match {
      case Some(org) => Ok(Json.toJson[GestaltOrg](org))
      case None => NotFound(Json.toJson(ResourceNotFoundException(
        resource = request.path,
        message = "could not locate current org",
        developerMessage = "Could not locate a default organization for the authenticate API user."
      )))
    }
  }

  def getOrgByFQON(fqon: String) = AuthenticatedAction(fqonToOrgUUID(fqon)) { implicit request =>
    OrgFactory.findByFQON(fqon) match {
      case Some(org) => Ok(Json.toJson[GestaltOrg](org))
      case None => defaultResourceNotFound
    }
  }

  def getOrgById(orgId: UUID) = AuthenticatedAction(Some(orgId)) { implicit request =>
    OrgFactory.findByOrgId(orgId) match {
      case Some(org) => Ok(Json.toJson[GestaltOrg](org))
      case None => defaultResourceNotFound
    }
  }

  def getAppById(appId: UUID) = AuthenticatedAction(getAppOrg(appId)) { implicit request =>
    AppFactory.findByAppId(appId) match {
      case Some(app) => Ok(Json.toJson[GestaltApp](app))
      case None => defaultResourceNotFound
    }
  }

  def getOrgAccount(orgId: UUID, accountId: UUID) = AuthenticatedAction(Some(orgId)) { implicit request =>
    AccountFactory.getAppAccount(appId = request.user.serviceAppId, accountId = accountId) match {
      case Some(account) => Ok(Json.toJson[GestaltAccount](account))
      case None => NotFound(Json.toJson(ResourceNotFoundException(
        resource = request.path,
        message = "could not locate requested account in the organization",
        developerMessage = "Could not locate the requested account in the organization. Make sure to use the account ID (not the username) and that the account is a member of the organization."
      )))
    }
  }

  def getOrgAccountByUsername(orgId: UUID, username: String) = AuthenticatedAction(Some(orgId)) { implicit request =>
    requireAuthorization(READ_DIRECTORY)
    DirectoryFactory.find(AppFactory.getDefaultAccountStore(appId = request.user.serviceAppId).fold(_.id, _.dirId).asInstanceOf[UUID]) match {
      case Some(dir) => dir.findByUsername(username) match {
        case Some(account) =>
          Ok(Json.toJson[GestaltAccount](account))
        case None => NotFound(Json.toJson(ResourceNotFoundException(
          resource = request.path,
          message = "could not locate requested account in the organization",
          developerMessage = "Could not locate the requested account in the organization in the default account store associated with the org."
        )))
      }
      case None => NotFound(Json.toJson(ResourceNotFoundException(
        resource = request.path,
        message = "could not locate the default account directory for the org",
        developerMessage = "Could not locate the default account directory for the organization."
      )))
    }
  }

  def getAppAccountByUsername(appId: UUID, username: String) = AuthenticatedAction(getAppOrg(appId)) { implicit request =>
    requireAuthorization(READ_DIRECTORY)
    DirectoryFactory.find(AppFactory.getDefaultAccountStore(appId = appId).fold(_.id, _.dirId).asInstanceOf[UUID]) match {
      case Some(dir) => dir.findByUsername(username) match {
        case Some(account) =>
          Ok(Json.toJson[GestaltAccount](account))
        case None => NotFound(Json.toJson(ResourceNotFoundException(
          resource = request.path,
          message = "could not locate requested account in the organization",
          developerMessage = "Could not locate the requested account in the organization in the default account store associated with the org."
        )))
      }
      case None => NotFound(Json.toJson(ResourceNotFoundException(
        resource = request.path,
        message = "could not locate the default account directory for the org",
        developerMessage = "Could not locate the default account directory for the organization."
      )))
    }
  }


  def getOrgGroup(orgId: UUID, groupId: UUID) = AuthenticatedAction(Some(orgId)) { implicit request =>
    GroupFactory.getAppGroupMapping(appId = request.user.serviceAppId, groupId = groupId) match {
      case Some(group) => Ok(Json.toJson[GestaltGroup](group))
      case None => NotFound(Json.toJson(ResourceNotFoundException(
        resource = request.path,
        message = "could not locate requested group in the organization",
        developerMessage = "Could not locate the requested group in the organization. Make sure to use the group ID (not the name) and that the group is a member of the organization."
      )))
    }
  }

  def getAppGroup(appId: UUID, groupId: UUID) = AuthenticatedAction(getAppOrg(appId)) { implicit request =>
    GroupFactory.getAppGroupMapping(appId = appId, groupId = groupId) match {
      case Some(group) => Ok(Json.toJson[GestaltGroup](group))
      case None => NotFound(Json.toJson(ResourceNotFoundException(
        resource = request.path,
        message = "could not locate requested group in the app",
        developerMessage = "Could not locate the requested group in the app. Make sure to use the group ID (not the name) and that the group is a member of the organization."
      )))
    }
  }

  def getAccount(accountId: UUID) = AuthenticatedAction(getAccountOrg(accountId)) { implicit request =>
    if (request.user.identity.id != accountId) requireAuthorization(READ_DIRECTORY)
    AccountFactory.findEnabled(accountId) match {
      case Some(account) => Ok(Json.toJson[GestaltAccount](account))
      case None => NotFound(Json.toJson(ResourceNotFoundException(
        resource = request.path,
        message = "could not locate requested account",
        developerMessage = "Could not locate the requested account. Make sure to use the account ID and not the username."
      )))
    }
  }

  def updateAccount(accountId: UUID) = AuthenticatedAction(getAccountOrg(accountId))(parse.json) { implicit request =>
    // user can update their own account
    val update = validateBody[GestaltAccountUpdate]
    if (request.user.identity.id != accountId) requireAuthorization(UPDATE_ACCOUNT)
    AccountFactory.findEnabled(accountId) match {
      case Some(account) =>
        val newAccount = AccountFactory.updateAccount(account, update)
        Ok(Json.toJson[GestaltAccount](newAccount))
      case None => NotFound(Json.toJson(ResourceNotFoundException(
        resource = request.path,
        message = "could not locate requested account",
        developerMessage = "Could not locate the requested account. Make sure to use the account ID and not the username."
      )))
    }
  }

  def getAccountEmail(accountId: UUID) = AuthenticatedAction(getAccountOrg(accountId)) { implicit request =>
    // user can update their own account
    if (request.user.identity.id != accountId) requireAuthorization(READ_DIRECTORY)
    AccountFactory.findEnabled(accountId) match {
      case Some(account) =>
        Ok(account.email getOrElse "")
      case None => NotFound(Json.toJson(ResourceNotFoundException(
        resource = request.path,
        message = "could not locate requested account",
        developerMessage = "Could not locate the requested account. Make sure to use the account ID and not the username."
      )))
    }
  }

  def getAccountPhoneNumber(accountId: UUID) = AuthenticatedAction(getAccountOrg(accountId)) { implicit request =>
    // user can update their own account
    if (request.user.identity.id != accountId) requireAuthorization(READ_DIRECTORY)
    AccountFactory.findEnabled(accountId) match {
      case Some(account) =>
        Ok(account.phoneNumber getOrElse "")
      case None => NotFound(Json.toJson(ResourceNotFoundException(
        resource = request.path,
        message = "could not locate requested account",
        developerMessage = "Could not locate the requested account. Make sure to use the account ID and not the username."
      )))
    }
  }

  def removeAccountEmail(accountId: UUID) = AuthenticatedAction(getAccountOrg(accountId)) { implicit request =>
    // user can update their own account
    if (request.user.identity.id != accountId) requireAuthorization(UPDATE_ACCOUNT)
    AccountFactory.findEnabled(accountId) match {
      case Some(account) =>
        val newAccount = account.copy(email = None).save()
        Ok(Json.toJson[GestaltAccount](newAccount))
      case None => NotFound(Json.toJson(ResourceNotFoundException(
        resource = request.path,
        message = "could not locate requested account",
        developerMessage = "Could not locate the requested account. Make sure to use the account ID and not the username."
      )))
    }
  }

  def removeAccountPhoneNumber(accountId: UUID) = AuthenticatedAction(getAccountOrg(accountId)) { implicit request =>
    // user can update their own account
    if (request.user.identity.id != accountId) requireAuthorization(UPDATE_ACCOUNT)
    AccountFactory.findEnabled(accountId) match {
      case Some(account) =>
        val newAccount = account.copy(phoneNumber = None).save()
        Ok(Json.toJson[GestaltAccount](newAccount))
      case None => NotFound(Json.toJson(ResourceNotFoundException(
        resource = request.path,
        message = "could not locate requested account",
        developerMessage = "Could not locate the requested account. Make sure to use the account ID and not the username."
      )))
    }
  }

  def getAccountStores(appId: UUID) = AuthenticatedAction(getAppOrg(appId)) { implicit request =>
    Ok(Json.toJson(AppFactory.listAccountStoreMappings(appId) map { mapping => mapping: GestaltAccountStoreMapping }))
  }

  def getOrgAccountStores(orgId: UUID) = AuthenticatedAction(Some(orgId)) { implicit request =>
    Ok(Json.toJson(AppFactory.listAccountStoreMappings(appId = request.user.serviceAppId) map { mapping => mapping: GestaltAccountStoreMapping }))
  }

  def getOrgAccountRight(orgId: UUID, accountId: UUID, grantName: String) = AuthenticatedAction(Some(orgId)) { implicit request =>
    requireAuthorization(LIST_APP_GRANTS)
    AccountFactory.getAppAccountGrant(request.user.serviceAppId, accountId, grantName) match {
      case Some(grant) => Ok(Json.toJson[GestaltRightGrant](grant))
      case None => NotFound(Json.toJson(ResourceNotFoundException(
        resource = request.path,
        message = "could not locate requested right",
        developerMessage = "Could not locate a right grant with the given name, for that account in that org."
      )))
    }
  }

  def getOrgAccountRightByUsername(orgId: UUID, username: String, grantName: String) = AuthenticatedAction(Some(orgId)) { implicit request =>
    requireAuthorization(LIST_APP_GRANTS)
    DirectoryFactory.find(AppFactory.getDefaultAccountStore(appId = request.user.serviceAppId).fold(_.id, _.dirId).asInstanceOf[UUID]) match {
      case Some(dir) => dir.findByUsername(username) match {
        case Some(account) =>
          AccountFactory.getAppAccountGrant(request.user.serviceAppId, account.id.asInstanceOf[UUID], grantName) match {
            case Some(grant) => Ok(Json.toJson[GestaltRightGrant](grant))
            case None => NotFound(Json.toJson(ResourceNotFoundException(
              resource = request.path,
              message = "could not locate requested right",
              developerMessage = "Could not locate a right grant with the given name, for that account in that org."
            )))
          }
        case None => defaultResourceNotFound
      }
      case None => defaultResourceNotFound
    }
  }

  def getAppAccountRight(appId: UUID, accountId: UUID, grantName: String) = AuthenticatedAction(getAppOrg(appId)) { implicit request =>
    requireAuthorization(LIST_APP_GRANTS)
    AccountFactory.getAppAccountGrant(appId, accountId, grantName) match {
      case Some(grant) => Ok(Json.toJson[GestaltRightGrant](grant))
      case None => NotFound(Json.toJson(ResourceNotFoundException(
        resource = request.path,
        message = "could not locate requested right",
        developerMessage = "Could not locate a right grant with the given name, for that account in that app."
      )))
    }
  }

  def getAppAccountRightByUsername(appId: UUID, username: String, grantName: String) = AuthenticatedAction(getAppOrg(appId)) { implicit request =>
    requireAuthorization(LIST_APP_GRANTS)
    DirectoryFactory.find(AppFactory.getDefaultAccountStore(appId = appId).fold(_.id, _.dirId).asInstanceOf[UUID]) match {
      case Some(dir) => dir.findByUsername(username) match {
        case Some(account) =>
          AccountFactory.getAppAccountGrant(appId, account.id.asInstanceOf[UUID], grantName) match {
            case Some(grant) => Ok(Json.toJson[GestaltRightGrant](grant))
            case None => NotFound(Json.toJson(ResourceNotFoundException(
              resource = request.path,
              message = "could not locate requested right",
              developerMessage = "Could not locate a right grant with the given name, for that account in that app."
            )))
          }
        case None => defaultResourceNotFound
      }
      case None => defaultResourceNotFound
    }
  }

  def getDirectory(dirId: UUID) = AuthenticatedAction(getOrgFromDirectory(dirId)) { implicit request =>
    DirectoryFactory.find(dirId) match {
      case Some(dir) =>
        Ok(Json.toJson[GestaltDirectory](dir))
      case None => NotFound(Json.toJson(ResourceNotFoundException(
        resource = request.path,
        message = "could not locate requested directory",
        developerMessage = "Could not locate the requested directory. Make sure to use the directory ID."
      )))
    }
  }

  def getDirAccountByUsername(dirId: UUID, username: String) = AuthenticatedAction(getOrgFromDirectory(dirId)) { implicit request =>
    requireAuthorization(READ_DIRECTORY)
    DirectoryFactory.find(dirId) match {
      case Some(dir) =>
        AccountFactory.directoryLookup(dirId, username) match {
          case Some(account) => Ok(Json.toJson[GestaltAccount](account))
          case None => NotFound(Json.toJson(ResourceNotFoundException(
            resource = request.path,
            message = "could not locate requested account in the directory",
            developerMessage = "Could not locate the requested account in the directory. Make sure to use the account username."
          )))
        }
      case None => NotFound(Json.toJson(ResourceNotFoundException(
        resource = request.path,
        message = "could not locate requested directory",
        developerMessage = "Could not locate the requested directory. Make sure to use the directory ID."
      )))
    }
  }

  def getDirGroupByName(dirId: UUID, groupName: String) = AuthenticatedAction(getOrgFromDirectory(dirId)) { implicit request =>
    requireAuthorization(READ_DIRECTORY)
    DirectoryFactory.find(dirId) match {
      case Some(dir) =>
        GroupFactory.directoryLookup(dirId, groupName) match {
          case Some(group) => Ok(Json.toJson[GestaltGroup](group))
          case None => NotFound(Json.toJson(ResourceNotFoundException(
            resource = request.path,
            message = "could not locate requested group in the directory",
            developerMessage = "Could not locate the requested group in the directory. Make sure to use the group name."
          )))
        }
      case None => NotFound(Json.toJson(ResourceNotFoundException(
        resource = request.path,
        message = "could not locate requested directory",
        developerMessage = "Could not locate the requested directory. Make sure to use the directory ID."
      )))
    }
  }

  def getAccountStoreMapping(mapId: UUID) = AuthenticatedAction(getOrgFromAccountStoreMapping(mapId)) { implicit request =>
    services.accountStoreMappingService.find(mapId) match {
      case Some(asm) => Ok(Json.toJson[GestaltAccountStoreMapping](asm))
      case None => NotFound(Json.toJson(ResourceNotFoundException(
        resource = request.path,
        message = "could not locate requested account store mapping",
        developerMessage = "Could not locate the requested account store mapping."
      )))
    }
  }

  def rootOrgSync() = AuthenticatedAction(rootFromCredentials _) { implicit request =>
    // get the org tree
    val orgTree = OrgFactory.getOrgTree(request.user.orgId)
    val dirCache = DirectoryFactory.findAll map {
      dir => (dir.id, dir)
    } toMap
    val orgUsers = (orgTree flatMap {
      org => AppFactory.findServiceAppForOrg(org.id.asInstanceOf[UUID])
    } flatMap {
      app => AccountFactory.listAppUsers(app.id.asInstanceOf[UUID])
    } distinct) flatMap {
      uar => dirCache.get(uar.dirId.asInstanceOf[UUID]) map { dir =>
        GestaltAccount(
          id = uar.id.asInstanceOf[UUID],
          username = uar.username,
          firstName = uar.firstName,
          lastName = uar.lastName,
          email = uar.email getOrElse "",
          phoneNumber = uar.phoneNumber getOrElse "",
          directory = dir
        )
      }
    }
    Ok(Json.toJson(
      GestaltOrgSync(
        accounts = orgUsers,
        orgs = orgTree map { o => o: GestaltOrg }
      )
    ))
  }

  def subOrgSync(orgId: UUID) = AuthenticatedAction(Some(orgId)) { implicit request =>
    OrgFactory.find(orgId) match {
      case Some(org) =>
        val orgTree = OrgFactory.getOrgTree(org.id.asInstanceOf[UUID])
        val dirCache = DirectoryFactory.findAll map {
          dir => (dir.id, dir)
        } toMap
        val orgUsers = (orgTree flatMap {
          org => AppFactory.findServiceAppForOrg(org.id.asInstanceOf[UUID])
        } flatMap {
          app => AccountFactory.listAppUsers(app.id.asInstanceOf[UUID])
        } distinct) flatMap {
          uar => dirCache.get(uar.dirId.asInstanceOf[UUID]) map { dir =>
            GestaltAccount(
              id = uar.id.asInstanceOf[UUID],
              username = uar.username,
              firstName = uar.firstName,
              lastName = uar.lastName,
              email = uar.email getOrElse "",
              phoneNumber = uar.phoneNumber getOrElse "",
              directory = dir
            )
          }
        }
        Ok(Json.toJson(
          GestaltOrgSync(
            accounts = orgUsers,
            orgs = orgTree map { o => o: GestaltOrg }
          )
        ))
      case None => NotFound(Json.toJson(ResourceNotFoundException(
        resource = request.path,
        message = "could not locate requested org",
        developerMessage = "Could not locate the requested org. Make sure to use the org ID."
      )))
    }
  }

  def listAllOrgs() = AuthenticatedAction(rootFromCredentials _) { implicit request =>
    Ok(Json.toJson(OrgFactory.getOrgTree(request.user.orgId).map { o => o: GestaltOrg }))
  }

  def listOrgTree(orgId: UUID) = AuthenticatedAction(Some(orgId)) { implicit request =>
    OrgFactory.find(orgId) match {
      case Some(org) =>
        Ok(Json.toJson(OrgFactory.getOrgTree(org.id.asInstanceOf[UUID]).map { o => o: GestaltOrg }))
      case None => NotFound(Json.toJson(ResourceNotFoundException(
        resource = request.path,
        message = "could not locate requested org",
        developerMessage = "Could not locate the requested org. Make sure to use the org ID."
      )))
    }
  }

  def listAppAccounts(appId: UUID) = AuthenticatedAction(getAppOrg(appId)) { implicit request =>
    AppFactory.findByAppId(appId) match {
      case Some(app) => Ok(Json.toJson[Seq[GestaltAccount]](AccountFactory.listByAppId(app.id.asInstanceOf[UUID]) map { a => a: GestaltAccount }))
      case None => NotFound(Json.toJson(ResourceNotFoundException(
        resource = request.path,
        message = "could not locate requested app",
        developerMessage = "Could not locate the requested application. Make sure to use the application ID and not the application name."
      )))
    }
  }

  def listOrgAccountRights(orgId: UUID, accountId: UUID) = AuthenticatedAction(Some(orgId)) { implicit request =>
    requireAuthorization(LIST_APP_GRANTS)
    val rights = AccountFactory.listAppAccountGrants(request.user.serviceAppId, accountId)
    Ok(Json.toJson[Seq[GestaltRightGrant]](rights map { r => r: GestaltRightGrant }))
  }

  def listOrgAccountRightsByUsername(orgId: UUID, username: String) = AuthenticatedAction(Some(orgId)) { implicit request =>
    requireAuthorization(LIST_APP_GRANTS)
    requireAuthorization(READ_DIRECTORY)
    val dirId = AppFactory.getDefaultAccountStore(request.user.serviceAppId).fold(_.id, _.dirId).asInstanceOf[UUID]
    DirectoryFactory.find(dirId) match {
      case Some(dir) =>
        AccountFactory.directoryLookup(dirId, username) match {
          case Some(account) =>
            val rights = AccountFactory.listAppAccountGrants(request.user.serviceAppId, account.id.asInstanceOf[UUID])
            Ok(Json.toJson[Seq[GestaltRightGrant]](rights map { r => r: GestaltRightGrant }))
          case None => NotFound(Json.toJson(ResourceNotFoundException(
            resource = request.path,
            message = "could not locate requested account in the directory",
            developerMessage = "Could not locate the requested account in the directory. Make sure to use the account username."
          )))
        }
      case None => defaultResourceNotFound
    }
  }

  def listAppAccountRights(appId: UUID, accountId: UUID) = AuthenticatedAction(getAppOrg(appId)) { implicit request =>
    val rights = AccountFactory.listAppAccountGrants(appId, accountId)
    Ok(Json.toJson[Seq[GestaltRightGrant]](rights map { r => r: GestaltRightGrant }))
  }

  def listAppAccountRightsByUsername(appId: UUID, username: String) = AuthenticatedAction(getAppOrg(appId)) { implicit request =>
    requireAuthorization(LIST_APP_GRANTS)
    requireAuthorization(READ_DIRECTORY)
    val dirId = AppFactory.getDefaultAccountStore(request.user.serviceAppId).fold(_.id, _.dirId).asInstanceOf[UUID]
    DirectoryFactory.find(dirId) match {
      case Some(dir) =>
        AccountFactory.directoryLookup(dirId, username) match {
          case Some(account) =>
            val rights = AccountFactory.listAppAccountGrants(request.user.serviceAppId, account.id.asInstanceOf[UUID])
            Ok(Json.toJson[Seq[GestaltRightGrant]](rights map { r => r: GestaltRightGrant }))
          case None => NotFound(Json.toJson(ResourceNotFoundException(
            resource = request.path,
            message = "could not locate requested account in the directory",
            developerMessage = "Could not locate the requested account in the directory. Make sure to use the account username."
          )))
        }
      case None => defaultResourceNotFound
    }
  }

  def getRightGrant(rightId: UUID) = AuthenticatedAction(getGrantOrg(rightId)) { implicit request =>
    RightGrantRepository.find(rightId) match {
      case Some(grant) => Ok(Json.toJson[GestaltRightGrant](grant: GestaltRightGrant))
      case None => NotFound(Json.toJson(ResourceNotFoundException(
        resource = request.path,
        message = "right grant not found",
        developerMessage = "There is no right grant with the provided ID."
      )))
    }
  }

  def listOrgDirectories(orgId: UUID) = AuthenticatedAction(Some(orgId)) { implicit request =>
    Ok(Json.toJson[Seq[GestaltDirectory]](DirectoryFactory.listByOrgId(orgId) map { d => d: GestaltDirectory }))
  }

  def listOrgApps(orgId: UUID) = AuthenticatedAction(Some(orgId)) { implicit request =>
    Ok(Json.toJson[Seq[GestaltApp]](AppFactory.listByOrgId(orgId) map { a => a: GestaltApp }))
  }

  def listDirAccounts(dirId: UUID) = AuthenticatedAction(getOrgFromDirectory(dirId)) { implicit request =>
    requireAuthorization(READ_DIRECTORY)
    DirectoryFactory.find(dirId) match {
      case Some(dir) => Ok(
        Json.toJson(
          AccountFactory.listByDirectoryId(dirId) map {
            a => a: GestaltAccount
          }
        )
      )
      case None => NotFound(Json.toJson(ResourceNotFoundException(
        resource = request.path,
        message = "could not locate requested directory",
        developerMessage = "Could not locate the requested directory. Make sure to use the directory ID."
      )))
    }
  }

  def listDirGroups(dirId: UUID) = AuthenticatedAction(getOrgFromDirectory(dirId)) { implicit request =>
    requireAuthorization(READ_DIRECTORY)
    DirectoryFactory.find(dirId) match {
      case Some(dir) => Ok(
        Json.toJson(
          GroupFactory.listByDirectoryId(dirId) map {
            g => g: GestaltGroup
          }
        )
      )
      case None => NotFound(Json.toJson(ResourceNotFoundException(
        resource = request.path,
        message = "could not locate requested directory",
        developerMessage = "Could not locate the requested directory. Make sure to use the directory ID."
      )))
    }
  }

  def listOrgAccounts(orgId: UUID) = AuthenticatedAction(Some(orgId)) { implicit request =>
    Ok(Json.toJson(
      AccountFactory.listByAppId(request.user.serviceAppId).map { a => a: GestaltAccount }
    ))
  }

  def listOrgGroupMappings(orgId: UUID) = AuthenticatedAction(Some(orgId)) { implicit request =>
    Ok(Json.toJson[Seq[GestaltGroup]](
      GroupFactory.listAppGroupMappings(appId = request.user.serviceAppId).map { g => g: GestaltGroup }
    ))
  }

  def listAccountGroups(accountId: UUID) = AuthenticatedAction(getAccountOrg(accountId)) { implicit request =>
    AccountFactory.findEnabled(accountId) match {
      case Some(account) => Ok(Json.toJson[Seq[GestaltGroup]](
        GroupFactory.listAccountGroups(orgId = request.user.orgId, accountId = accountId).map { g => g: GestaltGroup }
      ))
      case None => NotFound(Json.toJson(ResourceNotFoundException(
        resource = request.path,
        message = "could not locate requested account",
        developerMessage = "Could not locate the requested account. Make sure to use the account ID and not the account username."
      )))
    }
  }

  ////////////////////////////////////////////////////////
  // Create methods
  ////////////////////////////////////////////////////////

  def createOrg(parentOrgId: UUID) = AuthenticatedAction(Some(parentOrgId))(parse.json) { implicit request =>
    requireAuthorization(CREATE_ORG)
    val account = request.user.identity
    val accountId = account.id.asInstanceOf[UUID]
    OrgFactory.findByOrgId(parentOrgId) match {
      case Some(parentOrg) =>
        val newOrg = OrgFactory.createSubOrgWithAdmin(parentOrg = parentOrg, request = request)
        Created(Json.toJson[GestaltOrg](newOrg))
      case None => NotFound(Json.toJson(ResourceNotFoundException(
        resource = request.path,
        message = "parent org does not exist",
        developerMessage = "Could not create sub-org because the parent org does not exist. Make sure to use the organization ID and not the organization name."
      )))
    }
  }

  def createAccountInOrg(parentOrgId: UUID) = AuthenticatedAction(Some(parentOrgId))(parse.json) { implicit request =>
    requireAuthorization(CREATE_ACCOUNT)
    val create = validateBody[GestaltAccountCreateWithRights]
    val serviceAppId = request.user.serviceAppId
    OrgFactory.findByOrgId(parentOrgId) match {
      case Some(parentOrg) =>
        val newAccount = AppFactory.createAccountInApp(appId = serviceAppId, create)
        Created(Json.toJson[GestaltAccount](newAccount))
      case None => NotFound(Json.toJson(ResourceNotFoundException(
        resource = request.path,
        message = "parent org does not exist",
        developerMessage = "Could not create account in org because the specified org does not exist. Make sure to use the organization ID and not the organization name."
      )))
    }
  }

  def createOrgGroup(parentOrgId: UUID) = AuthenticatedAction(Some(parentOrgId))(parse.json) { implicit request =>
    requireAuthorization(CREATE_GROUP)
    val create = validateBody[GestaltGroupCreateWithRights]
    val serviceAppId = request.user.serviceAppId
    OrgFactory.findByOrgId(parentOrgId) match {
      case Some(parentOrg) =>
        val newGroup = AppFactory.createGroupInApp(appId = serviceAppId, create)
        Created(Json.toJson[GestaltGroup](newGroup))
      case None => NotFound(Json.toJson(ResourceNotFoundException(
        resource = request.path,
        message = "parent org does not exist",
        developerMessage = "Could not create group in org because the specified org does not exist. Make sure to use the organization ID and not the organization name."
      )))
    }
  }

  def createOrgAccountStore(orgId: UUID) = AuthenticatedAction(Some(orgId))(parse.json) { implicit request =>
    requireAuthorization(CREATE_ACCOUNT_STORE)
    val create = validateBody[GestaltOrgAccountStoreMappingCreate]
    val serviceAppId = request.user.serviceAppId
    OrgFactory.findByOrgId(orgId) match {
      case Some(parentOrg) =>
        val newMapping = AppFactory.createOrgAccountStoreMapping(appId = serviceAppId, create)
        Created(Json.toJson[GestaltAccountStoreMapping](newMapping))
      case None => NotFound(Json.toJson(ResourceNotFoundException(
        resource = request.path,
        message = "parent org does not exist",
        developerMessage = "Could not create account store mapping in org because the specified org does not exist. Make sure to use the organization ID and not the organization name."
      )))
    }
  }

  def createOrgApp(orgId: UUID) = AuthenticatedAction(Some(orgId)).async(parse.json) { implicit request =>
    requireAuthorization(CREATE_APP)
    val create = validateBody[GestaltAppCreate]
    OrgFactory.findByOrgId(orgId) match {
      case Some(org) =>
        Future {
          val newApp = try {
            AppFactory.create(orgId = orgId, name = create.name, isServiceOrg = false)
          } catch {
            case _: Throwable => throw new CreateConflictException(
              resource = request.path,
              message = "error creating new app",
              developerMessage = "Error creating new app. Most likely a conflict with an existing app of the same name in the org."
            )
          }
          Created(Json.toJson[GestaltApp](newApp))
        }
      case None => Future {
        NotFound(Json.toJson(ResourceNotFoundException(
          resource = request.path,
          message = "org does not exist",
          developerMessage = "Cannot create application because the specified org does not exist."
        )))
      }
    }
  }

  def createOrgDirectory(orgId: UUID) = AuthenticatedAction(Some(orgId))(parse.json) { implicit request =>
    requireAuthorization(CREATE_DIRECTORY)
    val create = validateBody[GestaltDirectoryCreate]
    OrgFactory.findByOrgId(orgId) match {
      case Some(parentOrg) =>
        val newDir = DirectoryFactory.createDirectory(orgId = parentOrg.id.asInstanceOf[UUID], create)
        Created(Json.toJson[GestaltDirectory](newDir))
      case None => NotFound(Json.toJson(ResourceNotFoundException(
        resource = request.path,
        message = "parent org does not exist",
        developerMessage = "Could not create directory in org because the specified org does not exist. Make sure to use the organization ID and not the organization name."
      )))
    }
  }

  def createAppAccount(appId: UUID) = AuthenticatedAction(getAppOrg(appId))(parse.json) { implicit request =>
    requireAuthorization(CREATE_ACCOUNT)
    val create = validateBody[GestaltAccountCreateWithRights]
    val serviceAppId = request.user.serviceAppId
    AppFactory.findByAppId(appId) match {
      case Some(app) =>
        val newAccount = AppFactory.createAccountInApp(appId = appId, create)
        Created(Json.toJson[GestaltAccount](newAccount))
      case None => NotFound(Json.toJson(ResourceNotFoundException(
        resource = request.path,
        message = "app does not exist",
        developerMessage = "Could not create account in app because the app does not exist."
      )))
    }
  }

  def createAppGroup(appId: UUID) = AuthenticatedAction(getAppOrg(appId))(parse.json) { implicit request =>
    requireAuthorization(CREATE_GROUP)
    val create = validateBody[GestaltGroupCreateWithRights]
    AppFactory.findByAppId(appId) match {
      case Some(app) =>
        val newGroup = AppFactory.createGroupInApp(appId = appId, create)
        Created(Json.toJson[GestaltGroup](newGroup))
      case None => NotFound(Json.toJson(ResourceNotFoundException(
        resource = request.path,
        message = "app does not exist",
        developerMessage = "Could not create account in app because the app does not exist."
      )))
    }
  }

  def createDirAccount(dirId: UUID) = AuthenticatedAction(getOrgFromDirectory(dirId))(parse.json) { implicit request =>
    requireAuthorization(CREATE_ACCOUNT)
    val create = validateBody[GestaltAccountCreate]
    DirectoryFactory.find(dirId) match {
      case Some(dir) =>
        val newAccount = DirectoryFactory.createAccountInDir(dirId = dirId, create)
        Created(Json.toJson[GestaltAccount](newAccount))
      case None => NotFound(Json.toJson(ResourceNotFoundException(
        resource = request.path,
        message = "directory does not exist",
        developerMessage = "Could not create account in directory because the directory does not exist."
      )))
    }
  }

  def createDirGroup(dirId: UUID) = AuthenticatedAction(getOrgFromDirectory(dirId))(parse.json) { implicit request =>
    requireAuthorization(CREATE_GROUP)
    val create = validateBody[GestaltGroupCreate]
    DirectoryFactory.find(dirId) match {
      case Some(dir) =>
        val newGroup = DirectoryFactory.createGroupInDir(dirId = dirId, create)
        Created(Json.toJson[GestaltGroup](newGroup))
      case None => NotFound(Json.toJson(ResourceNotFoundException(
        resource = request.path,
        message = "app does not exist",
        developerMessage = "Could not create group in directory because the directory does not exist."
      )))
    }
  }

  def createAccountStoreMapping() = (new AuthenticatedActionBuilderAgainstRequest {
    override def genOrgId[B](request: Request[B]): Option[UUID] = request.body match {
      case json: JsValue => for {
        appId <- (json \ "appId").asOpt[UUID]
        app <- AppFactory.findByAppId(appId)
      } yield app.orgId.asInstanceOf[UUID]
      case _ => None
    }
  }).apply(parse.json) { implicit request =>
    requireAuthorization(CREATE_ACCOUNT_STORE)
    val create = validateBody[GestaltAccountStoreMappingCreate]
    val appId = create.appId
    AppFactory.findByAppId(appId) match {
      case Some(app) =>
        val newMapping = AppFactory.createAccountStoreMapping(appId = appId, create)
        Created(Json.toJson[GestaltAccountStoreMapping](newMapping))
      case None => NotFound(Json.toJson(ResourceNotFoundException(
        resource = request.path,
        message = "app does not exist",
        developerMessage = "Could not create account store mapping because the specified app does not exist."
      )))
    }
  }

  def createOrgAccountRight(orgId: UUID, accountId: UUID) = AuthenticatedAction(Some(orgId))(parse.json) { implicit request =>
    requireAuthorization(CREATE_ORG_GRANT)
    val grant = validateBody[GestaltGrantCreate]
    val newGrants = RightGrantFactory.addRightsToAccount(request.user.serviceAppId, accountId, Seq(grant))
    Ok(Json.toJson[GestaltRightGrant](newGrants.head))
  }

  def createOrgGroupRight(orgId: UUID, groupId: UUID) = AuthenticatedAction(Some(orgId))(parse.json) { implicit request =>
    requireAuthorization(CREATE_ORG_GRANT)
    val grant = validateBody[GestaltGrantCreate]
    val newGrants = RightGrantFactory.addRightsToGroup(request.user.serviceAppId, groupId, Seq(grant))
    Ok(Json.toJson[GestaltRightGrant](newGrants.head))
  }

  def createAppGroupRight(appId: UUID, groupId: UUID) = AuthenticatedAction(getAppOrg(appId))(parse.json) { implicit request =>
    requireAuthorization(CREATE_APP_GRANT)
    val grant = validateBody[GestaltGrantCreate]
    val newGrants = RightGrantFactory.addRightsToGroup(appId, groupId, Seq(grant))
    Ok(Json.toJson[GestaltRightGrant](newGrants.head))
  }

  def createAppAccountRight(appId: UUID, accountId: UUID) = AuthenticatedAction(getAppOrg(appId))(parse.json) { implicit request =>
    requireAuthorization(CREATE_APP_GRANT)
    val grant = validateBody[GestaltGrantCreate]
    val newGrants = RightGrantFactory.addRightsToAccount(appId, accountId, Seq(grant))
    Ok(Json.toJson[GestaltRightGrant](newGrants.head))
  }

  ////////////////////////////////////////////////////////
  // Update methods
  ////////////////////////////////////////////////////////

  def updateAccountStoreMapping(mapId: UUID) = AuthenticatedAction(getOrgFromAccountStoreMapping(mapId))(parse.json) { implicit request =>
    requireAuthorization(UPDATE_ACCOUNT_STORE)
    val patch = validateBody[Seq[PatchOp]]
    services.accountStoreMappingService.find(mapId) match {
      case None => defaultResourceNotFound
      case Some(map) =>
        val updated: AccountStoreMappingRepository = services.accountStoreMappingService.updateMapping( map, patch )
        Ok(Json.toJson[GestaltAccountStoreMapping](updated))
    }
  }

  def updateAppAccountRight(appId: UUID, accountId: UUID, grantName: String) = AuthenticatedAction(getAppOrg(appId))(parse.json) { implicit request =>
    requireAuthorization(UPDATE_APP_GRANT)
    val patch = validateBody[Seq[PatchOp]]
    val grant = AccountFactory.updateAppAccountGrant(appId, accountId, grantName, patch)
    Ok(Json.toJson[GestaltRightGrant](grant: GestaltRightGrant))
  }

  def updateAppAccountRightByUsername(appId: UUID, username: String, grantName: String) = AuthenticatedAction(getAppOrg(appId))(parse.json) { implicit request =>
    requireAuthorization(UPDATE_APP_GRANT)
    requireAuthorization(READ_DIRECTORY)
    val patch = validateBody[Seq[PatchOp]]
    val dirId = AppFactory.getDefaultAccountStore(appId).fold(_.id, _.dirId).asInstanceOf[UUID]
    DirectoryFactory.find(dirId) match {
      case Some(dir) =>
        AccountFactory.directoryLookup(dirId, username) match {
          case Some(account) =>
            val grant = AccountFactory.updateAppAccountGrant(appId, account.id.asInstanceOf[UUID], grantName, patch)
            Ok(Json.toJson[GestaltRightGrant](grant: GestaltRightGrant))
          case None => NotFound(Json.toJson(ResourceNotFoundException(
            resource = request.path,
            message = "could not locate requested account in the directory",
            developerMessage = "Could not locate the requested account in the directory. Make sure to use the account username."
          )))
        }
      case None => defaultResourceNotFound
    }
  }

  def updateOrgAccountRight(orgId: UUID, accountId: UUID, grantName: String) = AuthenticatedAction(Some(orgId))(parse.json) { implicit request =>
    requireAuthorization(UPDATE_ORG_GRANT)
    val patch = validateBody[Seq[PatchOp]]
    val grant = AccountFactory.updateAppAccountGrant(request.user.serviceAppId, accountId, grantName, patch)
    Ok(Json.toJson[GestaltRightGrant](grant: GestaltRightGrant))
  }

  def updateOrgAccountRightByUsername(orgId: UUID, username: String, grantName: String) = AuthenticatedAction(Some(orgId))(parse.json) { implicit request =>
    requireAuthorization(UPDATE_ORG_GRANT)
    requireAuthorization(READ_DIRECTORY)
    val patch = validateBody[Seq[PatchOp]]
    val dirId = AppFactory.getDefaultAccountStore(request.user.serviceAppId).fold(_.id, _.dirId).asInstanceOf[UUID]
    DirectoryFactory.find(dirId) match {
      case Some(dir) =>
        AccountFactory.directoryLookup(dirId, username) match {
          case Some(account) =>
            val grant = AccountFactory.updateAppAccountGrant(request.user.serviceAppId, account.id.asInstanceOf[UUID], grantName, patch)
            Ok(Json.toJson[GestaltRightGrant](grant: GestaltRightGrant))
          case None => NotFound(Json.toJson(ResourceNotFoundException(
            resource = request.path,
            message = "could not locate requested account in the directory",
            developerMessage = "Could not locate the requested account in the directory. Make sure to use the account username."
          )))
        }
      case None => defaultResourceNotFound
    }
  }

  ////////////////////////////////////////////////////////
  // Delete methods
  ////////////////////////////////////////////////////////

  def deleteOrgById(orgId: UUID) = AuthenticatedAction(Some(orgId)).async { implicit request =>
    requireAuthorization(DELETE_ORG)
    OrgFactory.findByOrgId(orgId) match {
      case Some(org) if org.parent.isDefined =>
        Future {
          OrgFactory.delete(org)
        } map { _ => Ok(Json.toJson(DeleteResult(true))) }
      case Some(org) if org.parent.isEmpty =>
        Future {
          BadRequest(Json.toJson(BadRequestException(
            resource = request.path,
            message = "cannot delete root org",
            developerMessage = "It is not permissible to delete the root organization. Check that you specified the intended org id."
          )))
        }
      case None => Future {
        NotFound(Json.toJson(ResourceNotFoundException(
          resource = request.path,
          message = "org does not exist",
          developerMessage = "Could not delete the target org because it does not exist or the provided credentials do not have rights to see it."
        )))
      }
    }
  }

  def deleteAppById(appId: UUID) = AuthenticatedAction(getAppOrg(appId)).async { implicit request =>
    requireAuthorization(DELETE_APP)
    AppFactory.findByAppId(appId) match {
      case Some(app) if app.isServiceApp == false =>
        Future {
          AppFactory.delete(app)
        } map { _ => Ok(Json.toJson(DeleteResult(true))) }
      case Some(app) if app.isServiceApp == true =>
        Future {
          BadRequest(Json.toJson(BadRequestException(
            resource = request.path,
            message = "cannot delete service app",
            developerMessage = "It is not permissible to delete the current service app for an organization. Verify that this is the app that you want to delete and select a new service app for the organization and try again, or delete the organization."
          )))
        }
      case None => Future {
        NotFound(Json.toJson(ResourceNotFoundException(
          resource = request.path,
          message = "app does not exist",
          developerMessage = "Could not delete the target app because it does not exist or the provided credentials do not have rights to see it."
        )))
      }
    }
  }

  def disableAccount(accountId: UUID) = AuthenticatedAction(getAccountOrg(accountId)).async { implicit request =>
    requireAuthorization(DELETE_ACCOUNT)
    AccountFactory.find(accountId) match {
      case Some(account) if account.disabled == false && account.id != request.user.identity.id =>
        Future {
          DirectoryFactory.find(account.dirId.asInstanceOf[UUID]) match {
            case None => InternalServerError("could not locate directory associated with account")
            case Some(dir) =>
              dir.disableAccount(account.id.asInstanceOf[UUID])
              Ok(Json.toJson(DeleteResult(true)))
          }
        }
      case Some(account) if account.disabled == true => Future {
        BadRequest(Json.toJson(BadRequestException(
          resource = request.path,
          message = "account has already been deleted",
          developerMessage = "The account has already been deleted and cannot therefore be deleted again."
        )))
      }
      case Some(account) if account.id == request.user.identity.id => Future {
        BadRequest(Json.toJson(BadRequestException(
          resource = request.path,
          message = "cannot delete self",
          developerMessage = "The authenticated account is the same as the account targeted by the delete operation. You cannot delete yourself. Get someone else to delete you."
        )))
      }
      case None => Future {
        NotFound(Json.toJson(ResourceNotFoundException(
          resource = request.path,
          message = "account does not exist",
          developerMessage = "Could not delete the target account because it does not exist or the provided credentials do not have rights to see it."
        )))
      }
    }
  }

  def deleteOrgAccountRight(orgId: UUID, accountId: UUID, grantName: String) = AuthenticatedAction(Some(orgId)) { implicit request =>
    requireAuthorization(DELETE_ORG_GRANT)
    val wasDeleted = AccountFactory.deleteAppAccountGrant(request.user.serviceAppId, accountId, grantName)
    Ok(Json.toJson(DeleteResult(wasDeleted)))
  }

  def deleteOrgAccountRightByUsername(orgId: UUID, username: String, grantName: String) = AuthenticatedAction(Some(orgId)) { implicit request =>
    requireAuthorization(READ_DIRECTORY)
    requireAuthorization(DELETE_ORG_GRANT)
    val dirId = AppFactory.getDefaultAccountStore(request.user.serviceAppId).fold(_.id, _.dirId).asInstanceOf[UUID]
    DirectoryFactory.find(dirId) match {
      case Some(dir) =>
        AccountFactory.directoryLookup(dirId, username) match {
          case Some(account) =>
            val wasDeleted = AccountFactory.deleteAppAccountGrant(request.user.serviceAppId, account.id.asInstanceOf[UUID], grantName)
            Ok(Json.toJson(DeleteResult(wasDeleted)))
          case None => NotFound(Json.toJson(ResourceNotFoundException(
            resource = request.path,
            message = "could not locate requested account in the directory",
            developerMessage = "Could not locate the requested account in the organization's default directory. Make sure to use the account username."
          )))
        }
      case None => defaultResourceNotFound
    }
  }

  def deleteAppAccountRight(appId: UUID, accountId: UUID, grantName: String) = AuthenticatedAction(getAppOrg(appId)) { implicit request =>
    val wasDeleted = AccountFactory.deleteAppAccountGrant(appId, accountId, grantName)
    Ok(Json.toJson(DeleteResult(wasDeleted)))
  }

  def deleteAppAccountRightByUsername(appId: UUID, username: String, grantName: String) = AuthenticatedAction(getAppOrg(appId)) { implicit request =>
    requireAuthorization(DELETE_APP_GRANT)
    requireAuthorization(READ_DIRECTORY)
    val dirId = AppFactory.getDefaultAccountStore(appId).fold(_.id, _.dirId).asInstanceOf[UUID]
    DirectoryFactory.find(dirId) match {
      case Some(dir) =>
        AccountFactory.directoryLookup(dirId, username) match {
          case Some(account) =>
            val wasDeleted = AccountFactory.deleteAppAccountGrant(appId, account.id.asInstanceOf[UUID], grantName)
            Ok(Json.toJson(DeleteResult(wasDeleted)))
          case None => NotFound(Json.toJson(ResourceNotFoundException(
            resource = request.path,
            message = "could not locate requested account in the directory",
            developerMessage = "Could not locate the requested account in the application's default directory. Make sure to use the account username."
          )))
        }
      case None => defaultResourceNotFound
    }
  }

  def deleteOrgGroupRight(orgId: UUID, groupId: UUID, grantName: String) = AuthenticatedAction(Some(orgId)) { implicit request =>
    val wasDeleted = AccountFactory.deleteAppGroupGrant(request.user.serviceAppId, groupId, grantName)
    Ok(Json.toJson(DeleteResult(wasDeleted)))
  }

  def deleteAppGroupRight(appId: UUID, groupId: UUID, grantName: String) = AuthenticatedAction(getAppOrg(appId)) { implicit request =>
    val wasDeleted = AccountFactory.deleteAppGroupGrant(appId, groupId, grantName)
    Ok(Json.toJson(DeleteResult(wasDeleted)))
  }

  def listOrgGroupRights(orgId: UUID, groupId: UUID) = AuthenticatedAction(Some(orgId)) { implicit request =>
    requireAuthorization(LIST_APP_GRANTS)
    val rights = AccountFactory.listAppGroupGrants(request.user.serviceAppId, groupId)
    Ok(Json.toJson[Seq[GestaltRightGrant]](rights map { r => r: GestaltRightGrant }))
  }

  def listAppGroupRights(appId: UUID, groupId: UUID) = AuthenticatedAction(getAppOrg(appId)) { implicit request =>
    requireAuthorization(LIST_APP_GRANTS)
    val rights = AccountFactory.listAppGroupGrants(request.user.serviceAppId, groupId)
    Ok(Json.toJson[Seq[GestaltRightGrant]](rights map { r => r: GestaltRightGrant }))
  }

  def getOrgGroupRight(orgId: UUID, groupId: UUID, grantName: String) = AuthenticatedAction(Some(orgId)) { implicit request =>
    requireAuthorization(LIST_APP_GRANTS)
    AccountFactory.getAppGroupGrant(request.user.serviceAppId, groupId, grantName) match {
      case Some(grant) => Ok(Json.toJson[GestaltRightGrant](grant))
      case None => NotFound(Json.toJson(ResourceNotFoundException(
        resource = request.path,
        message = "could not locate requested right",
        developerMessage = "Could not locate a right grant with the given name, for that account in that org."
      )))
    }
  }

  def getAppGroupRight(appId: UUID, groupId: UUID, grantName: String) = AuthenticatedAction(getAppOrg(appId)) { implicit request =>
    requireAuthorization(LIST_APP_GRANTS)
    AccountFactory.getAppGroupGrant(appId, groupId, grantName) match {
      case Some(grant) => Ok(Json.toJson[GestaltRightGrant](grant: GestaltRightGrant))
      case None => NotFound(Json.toJson(ResourceNotFoundException(
        resource = request.path,
        message = "could not locate requested right",
        developerMessage = "Could not locate a right grant with the given name, for that group in that app."
      )))
    }
  }

  def listAppGroupMappings(appId: UUID) = AuthenticatedAction(getAppOrg(appId)) { implicit request =>
    Ok(Json.toJson[Seq[GestaltGroup]](
      GroupFactory.listAppGroupMappings(appId).map { g => g: GestaltGroup }
    ))
  }

  def updateOrgGroupRight(orgId: UUID, groupId: UUID, grantName: String) = AuthenticatedAction(Some(orgId))(parse.json) { implicit request =>
    val patch = validateBody[Seq[PatchOp]]
    val grant = AccountFactory.updateAppGroupGrant(request.user.serviceAppId, groupId = groupId, grantName, patch)
    Ok(Json.toJson[GestaltRightGrant](grant: GestaltRightGrant))
  }

  def updateAppGroupRight(appId: UUID, groupId: UUID, grantName: String) = AuthenticatedAction(getAppOrg(appId))(parse.json) { implicit request =>
    val patch = validateBody[Seq[PatchOp]]
    val grant = AccountFactory.updateAppGroupGrant(appId, groupId = groupId, grantName, patch)
    Ok(Json.toJson[GestaltRightGrant](grant: GestaltRightGrant))
  }

  def deleteAccountStoreMapping(mapId: UUID) = AuthenticatedAction(getOrgFromAccountStoreMapping(mapId)).async { implicit request =>
    requireAuthorization(DELETE_ACCOUNT_STORE)
    services.accountStoreMappingService.find(mapId) match {
      case Some(asm) =>
        Future {
          AccountStoreMappingRepository.destroy(asm)
        } map {
          _ => Ok(Json.toJson(DeleteResult(true)))
        }
      case None => Future {
        NotFound(Json.toJson(ResourceNotFoundException(
          resource = request.path,
          message = "account store mapping does not exist",
          developerMessage = "Could not delete the target account store mapping because it does not exist or the provided credentials do not have rights to see it."
        )))
      }
    }
  }

  private[this] def requireAuthorization[T](requiredRight: String)(implicit request: Security.AuthenticatedRequest[T, GestaltHeaderAuthentication.AccountWithOrgContext]) = {
    val rights = RightGrantFactory.listAccountRights(appId = request.user.serviceAppId, accountId = request.user.identity.id.asInstanceOf[UUID])
    if (!rights.exists(r => (requiredRight == r.grantName || r.grantName == SUPERUSER) && r.grantValue.isEmpty)) Forbidden(Json.toJson(ForbiddenAPIException(
      message = "Forbidden",
      developerMessage = "Forbidden. API credentials did not correspond to the parent organization or the account did not have sufficient permissions."
    )))
  }

  def deleteRightGrant(rightId: UUID) = AuthenticatedAction(getGrantOrg(rightId)) { implicit request =>
    requireAuthorization(DELETE_APP_GRANT)
    Ok(Json.toJson(DeleteResult(RightGrantFactory.deleteRightGrant(rightId))))
  }

  def updateRightGrant(rightId: UUID) = AuthenticatedAction(getGrantOrg(rightId))(parse.json) { implicit request =>
    requireAuthorization(UPDATE_APP_GRANT)
    RightGrantRepository.find(rightId) match {
      case Some(grant) =>
        validateBody[Seq[PatchOp]] match {
          case Seq(patch) if patch.path == "/grantValue" =>
            patch.op.toLowerCase match {
              case update if update == "add" || update == "replace" =>
                val newvalue = patch.value.as[String]
                Ok(Json.toJson[GestaltRightGrant](grant.copy(grantValue = Some(newvalue))))
              case "remove" => Ok(Json.toJson[GestaltRightGrant](
                grant.copy(grantValue = None).save()
              ))
              case _ => defaultBadPatch
            }
          case _ => defaultBadPatch
        }
      case None => defaultResourceNotFound
    }
  }

  def defaultBadPatch(implicit request: RequestHeader) = {
    BadRequest(Json.toJson(BadRequestException(
      resource = request.path,
      message = "PATCH payload contained unsupported fields",
      developerMessage = "The PATCH payload did not match the semantics of the resource"
    )))
  }

  def defaultResourceNotFound(implicit request: RequestHeader) = {
    NotFound(Json.toJson(ResourceNotFoundException(
      resource = request.path,
      message = "resource not found",
      developerMessage = "Resource not found."
    )))
  }

  def validateBody[T](implicit request: Request[JsValue], m: reflect.Manifest[T]): T = {
    request.body.validate[T] match {
      case s: JsSuccess[T] => s.get
      case e: JsError => throw new BadRequestException(
        resource = request.path,
        message = "invalid payload",
        developerMessage = s"Payload could not be parsed; was expecting JSON representation of SDK object ${m.toString}"
      )
    }
  }

}