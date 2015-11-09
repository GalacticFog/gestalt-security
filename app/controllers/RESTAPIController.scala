package controllers

import java.util.UUID

import com.galacticfog.gestalt.security.api._
import com.galacticfog.gestalt.security.api.errors._
import com.galacticfog.gestalt.security.data.domain._
import com.galacticfog.gestalt.security.data.model._
import org.postgresql.util.PGobject
import play.api.libs.json.{JsSuccess, JsError, JsValue, Json}
import play.api._
import play.api.mvc._
import scala.concurrent.ExecutionContext.Implicits.global
import play.api.Play.current
import com.galacticfog.gestalt.security.data.APIConversions._
import com.galacticfog.gestalt.security.api.json.JsonImports._

import scala.concurrent.Future
import scala.util.{Try, Failure}

case class SecurityControllerInitializationError(msg: String) extends RuntimeException(msg)

object RESTAPIController extends Controller with GestaltHeaderAuthentication {

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

  def fqonToOrgUUID(fqon: String): Option[UUID] = OrgFactory.findByFQON(fqon) map {_.id.asInstanceOf[UUID]}

  def rootOrg(): Option[UUID] = OrgFactory.getRootOrg() map {_.id.asInstanceOf[UUID]}

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

  def getOrgFromAccountStoreMapping(mapId: UUID): Option[UUID] = for {
    mapping <- AccountStoreMappingRepository.find(mapId)
    app <- GestaltAppRepository.find(mapping.appId)
  } yield app.orgId.asInstanceOf[UUID]

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

  def apiAuth() = AuthenticatedAction(rootFromCredentials _) { implicit request =>
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

  def getCurrentOrg() = AuthenticatedAction(rootFromCredentials _) { implicit request =>
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
    AccountFactory.getAppAccount(appId = request.user.serviceAppId, accountId = accountId) match {
      case Some(account) => Ok(Json.toJson[GestaltAccount](account))
      case None => throw new ResourceNotFoundException(
        resource = request.path,
        message = "could not locate requested account in the organization",
        developerMessage = "Could not locate the requested account in the organization. Make sure to use the account ID (not the username) and that the account is a member of the organization."
      )
    }
  }

  def getOrgGroup(orgId: java.util.UUID, groupId: java.util.UUID) = AuthenticatedAction(Some(orgId)) { implicit request =>
    GroupFactory.getAppGroupMapping(appId = request.user.serviceAppId, groupId = groupId) match {
      case Some(group) => Ok(Json.toJson[GestaltGroup](group))
      case None => throw new ResourceNotFoundException(
        resource = request.path,
        message = "could not locate requested group in the organization",
        developerMessage = "Could not locate the requested group in the organization. Make sure to use the group ID (not the name) and that the group is a member of the organization."
      )
    }
  }

  def getAccount(accountId: UUID) = AuthenticatedAction(getAccountOrg(accountId)) { implicit request =>
    requireAuthorization(OrgFactory.READ_DIRECTORY)
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

  // TODO
  def getAccountGrant(appId: UUID, accountId: UUID, grantName: String) = AuthenticatedAction(None) { implicit request =>
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

  def getDirAccountByUsername(dirId: UUID, username: String) = AuthenticatedAction(getOrgFromDirectory(dirId)) {  implicit request =>
    requireAuthorization(OrgFactory.READ_DIRECTORY)
    GestaltDirectoryRepository.find(dirId) match {
      case Some(dir) =>
        AccountFactory.directoryLookup(dirId,username) match {
          case Some(account) => Ok(Json.toJson[GestaltAccount](account))
          case None => throw new ResourceNotFoundException(
            resource = request.path,
            message = "could not locate requested account in the directory",
            developerMessage = "Could not locate the requested account in the directory. Make sure to use the account username."
          )
        }
      case None => throw new ResourceNotFoundException(
        resource = request.path,
        message = "could not locate requested directory",
        developerMessage = "Could not locate the requested directory. Make sure to use the directory ID."
      )
    }
  }

  def getDirGroupByName(dirId: UUID, groupName: String) = AuthenticatedAction(getOrgFromDirectory(dirId)) {  implicit request =>
    requireAuthorization(OrgFactory.READ_DIRECTORY)
    GestaltDirectoryRepository.find(dirId) match {
      case Some(dir) =>
        GroupFactory.directoryLookup(dirId,groupName) match {
          case Some(group) => Ok(Json.toJson[GestaltGroup](group))
          case None => throw new ResourceNotFoundException(
            resource = request.path,
            message = "could not locate requested group in the directory",
            developerMessage = "Could not locate the requested group in the directory. Make sure to use the group name."
          )
        }
      case None => throw new ResourceNotFoundException(
        resource = request.path,
        message = "could not locate requested directory",
        developerMessage = "Could not locate the requested directory. Make sure to use the directory ID."
      )
    }
  }

  def getAccountStoreMapping(mapId: UUID) = AuthenticatedAction(getOrgFromAccountStoreMapping(mapId)) { implicit request =>
    AccountStoreMappingRepository.find(mapId) match {
      case Some(asm) => Ok(Json.toJson[GestaltAccountStoreMapping](asm))
      case None => throw new ResourceNotFoundException(
        resource = request.path,
        message = "could not locate requested account store mapping",
        developerMessage = "Could not locate the requested account store mapping."
      )
    }
  }

  def rootOrgSync() = AuthenticatedAction(rootFromCredentials _) { implicit request =>
    // get the org tree
    val orgTree = OrgFactory.getOrgTree(request.user.orgId)
    val dirCache = GestaltDirectoryRepository.findAll map {
      dir => (dir.id.asInstanceOf[UUID], dir)
    } toMap
    val orgUsers = (orgTree flatMap {
      org => AppFactory.findServiceAppForOrg(org.id.asInstanceOf[UUID])
    } flatMap {
      app => AccountFactory.listAppUsers(app.id.asInstanceOf[UUID])
    } distinct) flatMap {
      uar => dirCache.get(uar.dirId.asInstanceOf[UUID]) map {dir =>
        GestaltAccount(
          id = uar.id.asInstanceOf[UUID],
          username = uar.username,
          firstName = uar.firstName,
          lastName = uar.lastName,
          email = uar.email,
          phoneNumber = uar.phoneNumber getOrElse "",
          directory = dir
        )
      }
    }
    Ok(Json.toJson(
      GestaltOrgSync(
        accounts = orgUsers,
        orgs = orgTree map {o => o: GestaltOrg}
      )
    ))
  }

  def subOrgSync(orgId: java.util.UUID) = AuthenticatedAction(Some(orgId)) { implicit request =>
    GestaltOrgRepository.find(orgId) match {
      case Some(org) =>
        val orgTree = OrgFactory.getOrgTree(org.id.asInstanceOf[UUID])
        val dirCache = GestaltDirectoryRepository.findAll map {
          dir => (dir.id.asInstanceOf[UUID], dir)
        } toMap
        val orgUsers = (orgTree flatMap {
          org => AppFactory.findServiceAppForOrg(org.id.asInstanceOf[UUID])
        } flatMap {
          app => AccountFactory.listAppUsers(app.id.asInstanceOf[UUID])
        } distinct) flatMap {
          uar => dirCache.get(uar.dirId.asInstanceOf[UUID]) map {dir =>
            GestaltAccount(
              id = uar.id.asInstanceOf[UUID],
              username = uar.username,
              firstName = uar.firstName,
              lastName = uar.lastName,
              email = uar.email,
              phoneNumber = uar.phoneNumber getOrElse "",
              directory = dir
            )
          }
        }
        Ok(Json.toJson(
          GestaltOrgSync(
            accounts = orgUsers,
            orgs = orgTree map {o => o: GestaltOrg}
          )
        ))
      case None => throw new ResourceNotFoundException(
        resource = request.path,
        message = "could not locate requested org",
        developerMessage = "Could not locate the requested org. Make sure to use the org ID."
      )
    }
  }

  def listAllOrgs() = AuthenticatedAction(rootFromCredentials _) { implicit request =>
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

  def listAppAccounts(appId: UUID) = AuthenticatedAction(getAppOrg(appId)) { implicit request =>
    AppFactory.findByAppId(appId) match {
      case Some(app) => Ok(Json.toJson[Seq[GestaltAccount]](AccountFactory.listByAppId(app.id.asInstanceOf[UUID]) map { a => a: GestaltAccount }))
      case None => throw new ResourceNotFoundException(
        resource = request.path,
        message = "could not locate requested app",
        developerMessage = "Could not locate the requested application. Make sure to use the application ID and not the application name."
      )
    }
  }

  def listAccountRights(appId: UUID, accountId: UUID) = AuthenticatedAction(getAppOrg(appId)) { implicit request =>
    val rights = AccountFactory.listAppGrants(appId,accountId)
    Ok(Json.toJson[Seq[GestaltRightGrant]](rights map { r => r: GestaltRightGrant}) )
  }

  def listOrgDirectories(orgId: UUID) = AuthenticatedAction(Some(orgId)) { implicit request =>
    Ok(Json.toJson[Seq[GestaltDirectory]](DirectoryFactory.listByOrgId(orgId) map { d => d: GestaltDirectory }))
  }

  def listOrgApps(orgId: UUID) = AuthenticatedAction(Some(orgId)) { implicit request =>
    Ok(Json.toJson[Seq[GestaltApp]](AppFactory.listByOrgId(orgId) map { a => a: GestaltApp }))
  }

  def listDirAccounts(dirId: UUID) = AuthenticatedAction(getOrgFromDirectory(dirId)) {  implicit request =>
    requireAuthorization(OrgFactory.READ_DIRECTORY)
    GestaltDirectoryRepository.find(dirId) match {
      case Some(dir) => Ok(
        Json.toJson(
          AccountFactory.listByDirectoryId(dirId) map {
            a => a: GestaltAccount
          }
        )
      )
      case None => throw new ResourceNotFoundException(
        resource = request.path,
        message = "could not locate requested directory",
        developerMessage = "Could not locate the requested directory. Make sure to use the directory ID."
      )
    }
  }

  def listDirGroups(dirId: java.util.UUID) = AuthenticatedAction(getOrgFromDirectory(dirId)) { implicit request =>
    requireAuthorization(OrgFactory.READ_DIRECTORY)
    GestaltDirectoryRepository.find(dirId) match {
      case Some(dir) => Ok(
        Json.toJson(
        GroupFactory.listByDirectoryId(dirId) map {
            g => g: GestaltGroup
          }
        )
      )
      case None => throw new ResourceNotFoundException(
        resource = request.path,
        message = "could not locate requested directory",
        developerMessage = "Could not locate the requested directory. Make sure to use the directory ID."
      )
    }
  }

  def listOrgAccounts(orgId: UUID) = AuthenticatedAction(Some(orgId)) { implicit request =>
    Ok( Json.toJson(
      AccountFactory.listByAppId(request.user.serviceAppId).map {a => a: GestaltAccount}
    ) )
  }

  def listOrgGroupMappings(orgId: UUID) = AuthenticatedAction(Some(orgId)) { implicit request =>
    Ok( Json.toJson[Seq[GestaltGroup]](
        GroupFactory.listAppGroupMappings(appId = request.user.serviceAppId).map {g => g: GestaltGroup}
    ) )
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
    requireAuthorization(OrgFactory.CREATE_ORG)
    val account = request.user.identity
    val accountId = account.id.asInstanceOf[UUID]
    OrgFactory.findByOrgId(parentOrgId) match {
      case Some(parentOrg) =>
        val newOrg = OrgFactory.createSubOrgWithAdmin(parentOrg = parentOrg, request = request)
        Created(Json.toJson[GestaltOrg](newOrg))
      case None => throw new ResourceNotFoundException(
        resource = request.path,
        message = "parent org does not exist",
        developerMessage = "Could not create sub-org because the parent org does not exist. Make sure to use the organization ID and not the organization name."
      )
    }
  }

  def createAccountInOrg(parentOrgId: UUID) = AuthenticatedAction(Some(parentOrgId))(parse.json) { implicit request =>
    requireAuthorization(OrgFactory.CREATE_ACCOUNT)
    val serviceAppId = request.user.serviceAppId
    OrgFactory.findByOrgId(parentOrgId) match {
      case Some(parentOrg) =>
        val create = request.body.validate[GestaltAccountCreateWithRights] match {
          case s: JsSuccess[GestaltAccountCreateWithRights] => s.get
          case e: JsError => throw new BadRequestException(
            resource = request.path,
            message = "invalid payload",
            developerMessage = "Payload could not be parsed; was expecting JSON representation of GestaltAccountCreateWithRights"
          )
        }
        val newAccount = AppFactory.createAccountInApp(appId = serviceAppId, create)
        Created(Json.toJson[GestaltAccount](newAccount))
      case None => throw new ResourceNotFoundException(
        resource = request.path,
        message = "parent org does not exist",
        developerMessage = "Could not create account in org because the specified org does not exist. Make sure to use the organization ID and not the organization name."
      )
    }
  }

  def createGroupInOrg(parentOrgId: UUID) = AuthenticatedAction(Some(parentOrgId))(parse.json) { implicit request =>
    requireAuthorization(OrgFactory.CREATE_GROUP)
    val serviceAppId = request.user.serviceAppId
    OrgFactory.findByOrgId(parentOrgId) match {
      case Some(parentOrg) =>
        val create = request.body.validate[GestaltGroupCreateWithRights] match {
          case s: JsSuccess[GestaltGroupCreateWithRights] => s.get
          case e: JsError => throw new BadRequestException(
            resource = request.path,
            message = "invalid payload",
            developerMessage = "Payload could not be parsed; was expecting JSON representation of GestaltGroupCreateWithRights"
          )
        }
        val newGroup = AppFactory.createGroupInApp(appId = serviceAppId, create)
        Created(Json.toJson[GestaltGroup](newGroup))
      case None => throw new ResourceNotFoundException(
        resource = request.path,
        message = "parent org does not exist",
        developerMessage = "Could not create group in org because the specified org does not exist. Make sure to use the organization ID and not the organization name."
      )
    }
  }

  def createOrgAccountStore(orgId: java.util.UUID) = AuthenticatedAction(Some(orgId))(parse.json) { implicit request =>
    requireAuthorization(OrgFactory.CREATE_ACCOUNT_STORE_MAPPING)
    val serviceAppId = request.user.serviceAppId
    OrgFactory.findByOrgId(orgId) match {
      case Some(parentOrg) =>
        val create = request.body.validate[GestaltOrgAccountStoreMappingCreate] match {
          case s: JsSuccess[GestaltOrgAccountStoreMappingCreate] => s.get
          case e: JsError => throw new BadRequestException(
            resource = request.path,
            message = "invalid payload",
            developerMessage = "Payload could not be parsed; was expecting JSON representation of GestaltOrgAccountStoreMappingCreate"
          )
        }
        val newMapping = AppFactory.createOrgAccountStoreMapping(appId = serviceAppId, create)
        Created(Json.toJson[GestaltAccountStoreMapping](newMapping))
      case None => throw new ResourceNotFoundException(
        resource = request.path,
        message = "parent org does not exist",
        developerMessage = "Could not create account store mapping in org because the specified org does not exist. Make sure to use the organization ID and not the organization name."
      )
    }
  }

  def createOrgApp(orgId: UUID) = AuthenticatedAction(Some(orgId)).async(parse.json) { implicit request =>
    requireAuthorization(OrgFactory.CREATE_APP)
    OrgFactory.findByOrgId(orgId) match {
      case Some(org) =>
        Future {
          val create = request.body.validate[GestaltAppCreate] match {
            case s: JsSuccess[GestaltAppCreate] => s.get
            case e: JsError => throw new BadRequestException(
              resource = request.path,
              message = "invalid payload",
              developerMessage = "Payload could not be parsed; was expecting JSON representation of GestaltAccountCreateWithRights."
            )
          }
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
      case None => throw new ResourceNotFoundException(
        resource = request.path,
        message = "org does not exist",
        developerMessage = "Cannot create application because the specified org does not exist."
      )
    }
  }

  def createOrgDirectory(orgId: UUID) = AuthenticatedAction(Some(orgId))(parse.json) { implicit request =>
    requireAuthorization(OrgFactory.CREATE_DIRECTORY)
    OrgFactory.findByOrgId(orgId) match {
      case Some(parentOrg) =>
        val create = request.body.validate[GestaltDirectoryCreate] match {
          case s: JsSuccess[GestaltDirectoryCreate] => s.get
          case e: JsError => throw new BadRequestException(
            resource = request.path,
            message = "invalid payload",
            developerMessage = "Payload could not be parsed; was expecting JSON representation of GestaltDirectoryCreate"
          )
        }
        val newDir = DirectoryFactory.createDirectory(orgId = parentOrg.id.asInstanceOf[UUID], create)
        Created(Json.toJson[GestaltDirectory](newDir))
      case None => throw new ResourceNotFoundException(
        resource = request.path,
        message = "parent org does not exist",
        developerMessage = "Could not create directory in org because the specified org does not exist. Make sure to use the organization ID and not the organization name."
      )
    }
  }

  def createAppAccount(appId: UUID) = AuthenticatedAction(getAppOrg(appId))(parse.json) {  implicit request =>
    requireAuthorization(OrgFactory.CREATE_ACCOUNT)
    val serviceAppId = request.user.serviceAppId
    AppFactory.findByAppId(appId) match {
      case Some(app) =>
        val create = request.body.validate[GestaltAccountCreateWithRights] match {
          case s: JsSuccess[GestaltAccountCreateWithRights] => s.get
          case e: JsError => throw new BadRequestException(
            resource = request.path,
            message = "invalid payload",
            developerMessage = "Payload could not be parsed; was expecting JSON representation of GestaltAccountCreateWithRights"
          )
        }
        val newAccount = AppFactory.createAccountInApp(appId = appId, create)
        Created(Json.toJson[GestaltAccount](newAccount))
      case None => throw new ResourceNotFoundException(
        resource = request.path,
        message = "app does not exist",
        developerMessage = "Could not create account in app because the app does not exist."
      )
    }
  }

  def createDirAccount(dirId: UUID) = AuthenticatedAction(getOrgFromDirectory(dirId))(parse.json) { implicit request =>
    requireAuthorization(OrgFactory.CREATE_ACCOUNT)
    GestaltDirectoryRepository.find(dirId) match {
      case Some(dir) =>
        val create = request.body.validate[GestaltAccountCreate] match {
          case s: JsSuccess[GestaltAccountCreate] => s.get
          case e: JsError => throw new BadRequestException(
            resource = request.path,
            message = "invalid payload",
            developerMessage = "Payload could not be parsed; was expecting JSON representation of GestaltAccountCreate"
          )
        }
        val newAccount = DirectoryFactory.createAccountInDir(dirId = dirId, create)
        Created(Json.toJson[GestaltAccount](newAccount))
      case None => throw new ResourceNotFoundException(
        resource = request.path,
        message = "directory does not exist",
        developerMessage = "Could not create account in directory because the directory does not exist."
      )
    }
  }

  def createDirGroup(dirId: java.util.UUID) = AuthenticatedAction(getOrgFromDirectory(dirId))(parse.json) { implicit request =>
    requireAuthorization(OrgFactory.CREATE_GROUP)
    GestaltDirectoryRepository.find(dirId) match {
      case Some(dir) =>
        val create = request.body.validate[GestaltGroupCreate] match {
          case s: JsSuccess[GestaltGroupCreate] => s.get
          case e: JsError => throw new BadRequestException(
            resource = request.path,
            message = "invalid payload",
            developerMessage = "Payload could not be parsed; was expecting JSON representation of GestaltGroupCreate"
          )
        }
        val newGroup = DirectoryFactory.createGroupInDir(dirId = dirId, create)
        Created(Json.toJson[GestaltGroup](newGroup))
      case None => throw new ResourceNotFoundException(
        resource = request.path,
        message = "app does not exist",
        developerMessage = "Could not create group in directory because the directory does not exist."
      )
    }
  }

  def createAccountStoreMapping() = (new AuthenticatedActionBuilderAgainstRequest {
    override def genOrgId[B](request: Request[B]): Option[UUID] = request.body match {
      case json: JsValue => for {
        appId <- (json \ "appId").asOpt[UUID]
        app <- AppFactory.findByAppId(appId)
      } yield app.orgId.asInstanceOf[UUID]
      case _ => None
    }}).apply(parse.json) { implicit request =>
    requireAuthorization(OrgFactory.CREATE_ACCOUNT_STORE_MAPPING)
    val create = request.body.validate[GestaltAccountStoreMappingCreate] match {
      case s: JsSuccess[GestaltAccountStoreMappingCreate] => s.get
      case e: JsError => throw new BadRequestException(
        resource = request.path,
        message = "invalid payload",
        developerMessage = "Payload could not be parsed; was expecting JSON representation of GestaltAccountStoreMappingCreate"
      )
    }
    val appId = create.appId
    AppFactory.findByAppId(appId) match {
      case Some(app) =>
        val newMapping = AppFactory.createAccountStoreMapping(appId = appId, create)
        Created(Json.toJson[GestaltAccountStoreMapping](newMapping))
      case None => throw new ResourceNotFoundException(
        resource = request.path,
        message = "app does not exist",
        developerMessage = "Could not create account store mapping because the specified app does not exist."
      )
    }
  }

  ////////////////////////////////////////////////////////
  // Update methods
  ////////////////////////////////////////////////////////

  // TODO
  def updateAccountStoreMapping(mapId: UUID) = AuthenticatedAction(None)(parse.json) {  implicit request =>
    ???
  }

  // TODO
  def updateRightGrant(appId: UUID, accountId: UUID, grantName: String) = AuthenticatedAction(None)(parse.json) { implicit request: Request[JsValue] =>
    ???
//    val grant = AccountFactory.updateAppGrant(appId,username,grantName,request.body)
//    Ok(Json.toJson[GestaltRightGrant](grant))
  }

  ////////////////////////////////////////////////////////
  // Delete methods
  ////////////////////////////////////////////////////////

  def deleteOrgById(orgId: java.util.UUID) = AuthenticatedAction(Some(orgId)).async { implicit request =>
    requireAuthorization(OrgFactory.DELETE_ORG)
    OrgFactory.findByOrgId(orgId) match {
      case Some(org) if org.parent.isDefined =>
        Future {
          GestaltOrgRepository.destroy(org)
        } map { _ => Ok(Json.toJson(DeleteResult(true))) }
      case Some(org) if org.parent.isEmpty =>
        throw new BadRequestException(
          resource = request.path,
          message = "cannot delete root org",
          developerMessage = "It is not permissible to delete the root organization. Check that you specified the intended org id."
        )
      case None =>  throw new ResourceNotFoundException(
        resource = request.path,
        message = "org does not exist",
        developerMessage = "Could not delete the target org because it does not exist or the provided credentials do not have rights to see it."
      )
    }
  }

  def deleteAppById(appId: java.util.UUID) = AuthenticatedAction(getAppOrg(appId)).async { implicit request =>
    requireAuthorization(OrgFactory.DELETE_APP)
    AppFactory.findByAppId(appId) match {
      case Some(app) if app.isServiceApp == false =>
        Future {
          GestaltAppRepository.destroy(app)
        } map { _ => Ok(Json.toJson(DeleteResult(true))) }
      case Some(app) if app.isServiceApp == true =>
        throw new BadRequestException(
          resource = request.path,
          message = "cannot delete service app",
          developerMessage = "It is not permissible to delete the current service app for an organization. Verify that this is the app that you want to delete and select a new service app for the organization and try again, or delete the organization."
        )
      case None =>  throw new ResourceNotFoundException(
        resource = request.path,
        message = "app does not exist",
        developerMessage = "Could not delete the target app because it does not exist or the provided credentials do not have rights to see it."
      )
    }
  }

  def disableAccount(accountId: java.util.UUID) = AuthenticatedAction(getAccountOrg(accountId)).async { implicit request =>
    requireAuthorization(OrgFactory.DELETE_ACCOUNT)
    UserAccountRepository.find(accountId) match {
      case Some(account) if account.disabled == false && account.id != request.user.identity.id =>
        Future {
          UserAccountRepository.save(account.copy(disabled = true))
        } map { acc => Ok(Json.toJson(DeleteResult(acc.disabled))) }
      case Some(account) if account.disabled == true => throw new BadRequestException(
          resource = request.path,
          message = "account has already been deleted",
          developerMessage = "The account has already been deleted and cannot therefore be deleted again."
        )
      case Some(account) if account.id != request.user.identity.id => throw new BadRequestException(
          resource = request.path,
          message = "cannot delete self",
          developerMessage = "The authenticated account is the same as the account targeted by the delete operation. You cannot delete yourself. Get someone else to delete you."
        )
      case None =>  throw new ResourceNotFoundException(
        resource = request.path,
        message = "account does not exist",
        developerMessage = "Could not delete the target account because it does not exist or the provided credentials do not have rights to see it."
      )
    }
  }

  // TODO
  def deleteRightGrant(appId: UUID, accountId: UUID, grantName: String) = AuthenticatedAction(None) { implicit request =>
    ???
//    val wasDeleted = AccountFactory.deleteAppGrant(appId,username,grantName)
//    Ok(Json.toJson(Json.toJson(DeleteResult(wasDeleted))))
  }

  def deleteAccountStoreMapping(mapId: UUID) = AuthenticatedAction(getOrgFromAccountStoreMapping(mapId)).async { implicit request =>
    requireAuthorization(OrgFactory.DELETE_ACCOUNT_STORE_MAPPING)
    AccountStoreMappingRepository.find(mapId) match {
      case Some(asm) =>
        Future{ AccountStoreMappingRepository.destroy(asm) } map {
          _ => Ok(Json.toJson(DeleteResult(true)))
        }
      case None => throw new ResourceNotFoundException(
        resource = request.path,
        message = "account store mapping does not exist",
        developerMessage = "Could not delete the target account store mapping because it does not exist or the provided credentials do not have rights to see it."
      )
    }
  }

  private[this] def requireAuthorization[T](requiredRight: String)(implicit request: Security.AuthenticatedRequest[T,GestaltHeaderAuthentication.AccountWithOrgContext]) = {
    val rights = RightGrantFactory.listRights(appId = request.user.serviceAppId, accountId = request.user.identity.id.asInstanceOf[UUID])
    if ( ! rights.exists(r => (requiredRight == r.grantName || r.grantName == OrgFactory.SUPERUSER) && r.grantValue.isEmpty) ) throw new ForbiddenAPIException(
      message = "Forbidden",
      developerMessage = "Forbidden. API credentials did not correspond to the parent organization or the account did not have sufficient permissions."
    )
  }

  // TODO
  def listOrgAccountRights(orgId: java.util.UUID, accountId: java.util.UUID) = play.mvc.Results.TODO

  // TODO
  def getOrgAccountGrant(orgId: java.util.UUID, accountId: java.util.UUID, grantName: String) = play.mvc.Results.TODO

  // TODO
  def updateOrgAccountRightGrant(orgId: java.util.UUID, accountId: java.util.UUID, grantName: String) = play.mvc.Results.TODO

  // TODO
  def deleteOrgAccountRightGrant(orgId: java.util.UUID, accountId: java.util.UUID, grantName: String) = play.mvc.Results.TODO

  // TODO
  def listOrgGroupRights(orgId: java.util.UUID, accountId: java.util.UUID) = play.mvc.Results.TODO

  // TODO
  def getOrgGroupGrant(orgId: java.util.UUID, accountId: java.util.UUID, grantName: String) = play.mvc.Results.TODO

  // TODO
  def updateOrgGroupRightGrant(orgId: java.util.UUID, accountId: java.util.UUID, grantName: String) = play.mvc.Results.TODO

  // TODO
  def deleteOrgGroupRightGrant(orgId: java.util.UUID, accountId: java.util.UUID, grantName: String) = play.mvc.Results.TODO

}