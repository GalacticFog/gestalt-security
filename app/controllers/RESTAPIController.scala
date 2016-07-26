package controllers

import java.util.UUID
import akka.util.Timeout
import com.galacticfog.gestalt.io.util.{PatchUpdate, PatchOp}
import com.galacticfog.gestalt.security.actors.RateLimitingActor
import com.galacticfog.gestalt.security.actors.RateLimitingActor.{RequestAccepted, TokenGrantRateLimitCheck}
import com.galacticfog.gestalt.security.api.AccessTokenResponse.BEARER
import com.galacticfog.gestalt.security.api.GestaltToken.ACCESS_TOKEN
import com.galacticfog.gestalt.security.{Init, BuildInfo, Global}
import com.galacticfog.gestalt.security.api._
import com.galacticfog.gestalt.security.api.errors._
import com.galacticfog.gestalt.security.data.domain._
import com.galacticfog.gestalt.security.data.model._
import play.api.libs.json._
import play.api._
import play.api.mvc._
import play.libs.Akka
import scala.concurrent.ExecutionContext.Implicits.global
import com.galacticfog.gestalt.security.data.APIConversions._
import com.galacticfog.gestalt.security.api.json.JsonImports._
import OrgFactory.Rights._
import scala.concurrent.Future
import scala.util.{Failure, Success, Try}
import PatchUpdate._
import akka.pattern.ask
import scala.concurrent.duration._

object RESTAPIController extends Controller with GestaltHeaderAuthentication with ControllerHelpers {

  val defaultTokenExpiration: Long =  Global.tokenLifetime.getStandardSeconds

  val services = Global.services

  def rateCheckActor = Akka.system().actorSelection(s"/user/${RateLimitingActor.ACTOR_NAME}")

  ////////////////////////////////////////////////////////////////
  // Utility methods
  ////////////////////////////////////////////////////////////////

  private[this] implicit def s2s: Seq[RightGrantRepository] => Seq[GestaltRightGrant] =
    _.map {g => g: GestaltRightGrant}

  private[this] def requireAuthorization[T](requiredRight: String)(implicit request: Security.AuthenticatedRequest[T, GestaltHeaderAuthentication.AccountWithOrgContext]) = {
    val rights = RightGrantFactory.listAccountRights(appId = request.user.serviceAppId, accountId = request.user.identity.id.asInstanceOf[UUID])
    if (!rights.exists(r => (requiredRight == r.grantName || r.grantName == SUPERUSER) && r.grantValue.isEmpty)) throw new ForbiddenAPIException(
      message = "Forbidden",
      developerMessage = "Forbidden. API credentials did not correspond to the parent organization or the account did not have sufficient permissions."
    )
  }

  ////////////////////////////////////////////////////////////////
  // Methods for extracting orgId for authentication from context
  ////////////////////////////////////////////////////////////////

  private[this] def resolveRoot: Option[UUID] = OrgFactory.getRootOrg() map {
    _.id.asInstanceOf[UUID]
  }

  private[this] def resolveFromCredentials(requestHeader: RequestHeader): Option[UUID] = {
    val keyRoot = for {
      authToken <- GestaltHeaderAuthentication.extractAuthToken(requestHeader) flatMap {_ match {
        case t: GestaltBasicCredentials => Some(t)
        case _ => None
      }}
      apiKey <- APICredentialFactory.findByAPIKey(authToken.username)
      orgId <- apiKey.issuedOrgId
    } yield orgId.asInstanceOf[UUID]
    keyRoot orElse resolveRoot
  }

  private[this] def resolveGrantOrg(grantId: UUID): Option[UUID] = for {
    grant <- RightGrantRepository.find(grantId)
    app <- AppFactory.findByAppId(grant.appId.asInstanceOf[UUID])
  } yield app.orgId.asInstanceOf[UUID]

  private[this] def resolveAppOrg(appId: UUID): Option[UUID] = for {
    app <- AppFactory.findByAppId(appId)
  } yield app.orgId.asInstanceOf[UUID]

  private[this] def resolveAccountOrg(accountId: UUID): Option[UUID] = {
    for {
      account <- AccountFactory.find(accountId)
      dir <- DirectoryFactory.find(account.dirId.asInstanceOf[UUID])
    } yield dir.orgId
  }

  private[this] def resolveGroupOrg(groupId: UUID): Option[UUID] = {
    for {
      group <- GroupFactory.find(groupId)
      dir <- DirectoryFactory.find(group.dirId.asInstanceOf[UUID])
    } yield dir.orgId
  }

  private[this] def resolveTokenOrg(tokenId: UUID): Option[UUID] = {
    for {
      token <- TokenFactory.findValidById(tokenId)
      issuedOrg <- token.issuedOrgId
    } yield issuedOrg.asInstanceOf[UUID]
  }

  private[this] def resolveApiKeyOrg(apiKey: UUID): Option[UUID] = {
    for {
      apiKey <- APICredentialFactory.findByAPIKey(apiKey.toString)
      issuedOrg <- apiKey.issuedOrgId
    } yield issuedOrg.asInstanceOf[UUID]
  }

  private[this] def resolveDirectoryOrg(dirId: UUID): Option[UUID] = for {
    dir <- DirectoryFactory.find(dirId)
  } yield dir.orgId

  private[this] def resolveMappingOrg(mapId: UUID): Option[UUID] = for {
    mapping <- services.accountStoreMappingService.find(mapId)
    app <- AppFactory.find(mapping.appId.asInstanceOf[UUID])
  } yield app.orgId.asInstanceOf[UUID]

  ////////////////////////////////////////////////////////
  // Auth methods
  ////////////////////////////////////////////////////////

  def orgAuth(orgId: UUID) = AuthenticatedAction(Some(orgId)) { implicit request =>
    val accountId = request.user.identity.id.asInstanceOf[UUID]
    val groups = GroupFactory.listAccountGroups(accountId = accountId)
    val rights = RightGrantFactory.listAccountRights(appId = request.user.serviceAppId, accountId = accountId)
    val ar = GestaltAuthResponse(account = request.user.identity, groups = groups map { g => (g: GestaltGroup).getLink }, rights = rights map { r => r: GestaltRightGrant }, orgId = request.user.orgId)
    Ok(Json.toJson(ar))
  }

  def globalAuth() = AuthenticatedAction(resolveFromCredentials _) { implicit request =>
    val accountId = request.user.identity.id.asInstanceOf[UUID]
    val groups = GroupFactory.listAccountGroups(accountId = accountId)
    val rights = RightGrantFactory.listAccountRights(appId = request.user.serviceAppId, accountId = accountId)
    val ar = GestaltAuthResponse(account = request.user.identity, groups = groups map { g => (g: GestaltGroup).getLink }, rights = rights map { r => r: GestaltRightGrant }, request.user.orgId)
    Ok(Json.toJson(ar))
  }

  def appAuth(appId: UUID) = AuthenticatedAction(resolveAppOrg(appId))(parse.json) { implicit request =>
    requireAuthorization(AUTHENTICATE_ACCOUNTS)
    val creds = validateBody[GestaltBasicCredsToken]
    val app = AppFactory.findByAppId(appId)
    if (app.isEmpty) throw new UnknownAPIException(
      code = 500,
      resource = request.path,
      message = "error looking up application",
      developerMessage = "The application could not be located, but the API request was authenticated. This suggests a problem with the database; please try again or contact support."
    )
    AccountFactory.authenticate(app.get.id.asInstanceOf[UUID], creds) match {
      case None => NotFound(Json.toJson(ResourceNotFoundException(
        resource = request.path,
        message = "invalid username or password",
        developerMessage = "The specified credentials do not match any in the application's account stores."
      )))
      case Some(account) =>
        val accountId = account.id.asInstanceOf[UUID]
        val rights = RightGrantFactory.listAccountRights(appId = appId, accountId = accountId)
        val groups = GroupFactory.listAccountGroups(accountId = accountId)
        Ok(Json.toJson[GestaltAuthResponse](GestaltAuthResponse(
          account,
          groups map { g => (g: GestaltGroup).getLink },
          rights map { r => r: GestaltRightGrant },
          orgId = app.get.orgId.asInstanceOf[UUID]
        )))
    }
  }

  ////////////////////////////////////////////////////////
  // Get/List methods
  ////////////////////////////////////////////////////////

  def getHealth = Action.async {
    Future {
      Try {
        resolveRoot
      } match {
        case Success(maybeRoot) if maybeRoot.isDefined =>
          Ok("healthy")
        case Success(orgId) if orgId.isEmpty =>
          InternalServerError("could not find root org; check database version")
        case Failure(_) if !Init.isInit =>
          BadRequest("server not initialized")
        case Failure(ex) =>
          InternalServerError("not able to connect to database")
      }
    }
  }

  def info = Action {
    Ok(Json.obj(
      "name" -> BuildInfo.name,
      "version" -> BuildInfo.version,
      "scalaVersion" -> BuildInfo.scalaVersion,
      "sbtVersion" -> BuildInfo.sbtVersion,
      "builtBy" -> BuildInfo.builtBy,
      "gitHash" -> BuildInfo.gitHash,
      "builtAtString" -> BuildInfo.builtAtString,
      "builtAtMillis" ->BuildInfo.builtAtMillis,
      "sdkVersion" -> GestaltSecurityClient.getVersion
    ))
  }

  def getCurrentOrg = AuthenticatedAction(resolveFromCredentials _) { implicit request =>
    OrgFactory.findByOrgId(request.user.orgId) match {
      case Some(org) => Ok(Json.toJson[GestaltOrg](org))
      case None => NotFound(Json.toJson(ResourceNotFoundException(
        resource = request.path,
        message = "could not locate current org",
        developerMessage = "Could not locate a default organization for the authenticate API user."
      )))
    }
  }

  def getSelf = AuthenticatedAction(None) { implicit request =>
    Ok(Json.toJson[GestaltAccount](request.user.identity))
  }

  def getOrgById(orgId: UUID) = AuthenticatedAction(Some(orgId)) { implicit request =>
    OrgFactory.findByOrgId(orgId) match {
      case Some(org) => Ok(Json.toJson[GestaltOrg](org))
      case None => defaultResourceNotFound
    }
  }

  def getAppById(appId: UUID) = AuthenticatedAction(resolveAppOrg(appId)) { implicit request =>
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
        developerMessage = "Could not locate the requested account in the organization." +
          " Make sure to use the account ID (not the username) and that the account is a member of the organization."
      )))
    }
  }

  def getAppAccount(appId: java.util.UUID, accountId: java.util.UUID) = AuthenticatedAction(resolveAppOrg(appId)) { implicit request =>
    AccountFactory.getAppAccount(appId = appId, accountId = accountId) match {
      case Some(account) => Ok(Json.toJson[GestaltAccount](account))
      case None => NotFound(Json.toJson(ResourceNotFoundException(
        resource = request.path,
        message = "could not locate requested account in the application",
        developerMessage = "Could not locate the requested account in the application." +
          " Make sure to use the account ID (not the username) and that the account is mapped to the application."
      )))
    }
  }

  def getOrgAccountByUsername(orgId: UUID, username: String) = AuthenticatedAction(Some(orgId)) { implicit request =>
    requireAuthorization(READ_DIRECTORY)
    val account = AppFactory.getUsernameInOrgDefaultAccountStore(orgId, username)
    renderTry[GestaltAccount](Ok)(account)
  }

  def getOrgGroupByName(orgId: java.util.UUID, groupName: String) = AuthenticatedAction(Some(orgId)) { implicit request =>
    requireAuthorization(READ_DIRECTORY)
    val group = AppFactory.getGroupNameInOrgDefaultGroupStore(orgId, groupName)
    renderTry[GestaltGroup](Ok)(group)
  }

  def getAppGroupByName(appId: java.util.UUID, groupName: String) = AuthenticatedAction(resolveAppOrg(appId)) { implicit request =>
    requireAuthorization(READ_DIRECTORY)
    val group = AppFactory.getGroupNameInAppDefaultGroupStore(appId, groupName)
    renderTry[GestaltGroup](Ok)(group)
  }

  def getAppAccountByUsername(appId: UUID, username: String) = AuthenticatedAction(resolveAppOrg(appId)) { implicit request =>
    requireAuthorization(READ_DIRECTORY)
    val account = AppFactory.getUsernameInDefaultAccountStore(appId, username)
    renderTry[GestaltAccount](Ok)(account)
  }

  def getGroup(groupId: UUID) = AuthenticatedAction(resolveGroupOrg(groupId)) { implicit request =>
    requireAuthorization(READ_DIRECTORY)
    GroupFactory.find(groupId) match {
      case Some(group) => Ok(Json.toJson[GestaltGroup](group))
      case None => NotFound(Json.toJson(ResourceNotFoundException(
        resource = request.path,
        message = "could not locate requested group",
        developerMessage = "Could not locate the requested group using the given ID. Make sure to use the group ID (not the name)."
      )))
    }
  }

  def listGroupMembers(groupId: java.util.UUID) = AuthenticatedAction(resolveGroupOrg(groupId)) { implicit request =>
    requireAuthorization(READ_DIRECTORY)
    GroupFactory.find(groupId) match {
      case Some(group) => DirectoryFactory.find(group.dirId.asInstanceOf[UUID]) match {
        case None => defaultResourceNotFound
        case Some(dir) => Ok(Json.toJson[Seq[GestaltAccount]](
          dir.listGroupAccounts(groupId) map {acc => acc: GestaltAccount}
        ))
      }
      case None => NotFound(Json.toJson(ResourceNotFoundException(
        resource = request.path,
        message = "could not locate requested group",
        developerMessage = "Could not locate the requested group using the given ID. Make sure to use the group ID (not the name)."
      )))
    }
  }

  def getOrgGroup(orgId: UUID, groupId: UUID) = AuthenticatedAction(Some(orgId)) { implicit request =>
    requireAuthorization(READ_DIRECTORY)
    GroupFactory.getAppGroupMapping(appId = request.user.serviceAppId, groupId = groupId) match {
      case Some(group) => Ok(Json.toJson[GestaltGroup](group))
      case None => NotFound(Json.toJson(ResourceNotFoundException(
        resource = request.path,
        message = "could not locate requested group in the organization",
        developerMessage = "Could not locate the requested group in the organization. Make sure to use the group ID (not the name) and that the group is a member of the organization."
      )))
    }
  }

  def getAppGroup(appId: UUID, groupId: UUID) = AuthenticatedAction(resolveAppOrg(appId)) { implicit request =>
    requireAuthorization(READ_DIRECTORY)
    GroupFactory.getAppGroupMapping(appId = appId, groupId = groupId) match {
      case Some(group) => Ok(Json.toJson[GestaltGroup](group))
      case None => NotFound(Json.toJson(ResourceNotFoundException(
        resource = request.path,
        message = "could not locate requested group in the app",
        developerMessage = "Could not locate the requested group in the app. Make sure to use the group ID (not the name) and that the group is a member of the organization."
      )))
    }
  }

  def getAccount(accountId: UUID) = AuthenticatedAction(resolveAccountOrg(accountId)) { implicit request =>
    if (request.user.identity.id != accountId) requireAuthorization(READ_DIRECTORY)
    AccountFactory.find(accountId) match {
      case Some(account) => Ok(Json.toJson[GestaltAccount](account))
      case None => NotFound(Json.toJson(ResourceNotFoundException(
        resource = request.path,
        message = "could not locate requested account",
        developerMessage = "Could not locate the requested account. Make sure to use the account ID and not the username."
      )))
    }
  }

  def listAccountStores(appId: UUID) = AuthenticatedAction(resolveAppOrg(appId)) { implicit request =>
    Ok(Json.toJson(AppFactory.listAccountStoreMappings(appId) map { mapping => mapping: GestaltAccountStoreMapping }))
  }

  def listOrgAccountStores(orgId: UUID) = AuthenticatedAction(Some(orgId)) { implicit request =>
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
    val right = for {
      account <- AppFactory.getUsernameInOrgDefaultAccountStore(orgId, username)
      grant <- AccountFactory.getAppAccountGrant(request.user.serviceAppId, account.id.asInstanceOf[UUID], grantName) match {
        case Some(grant) => Success(grant)
        case None => Failure(ResourceNotFoundException(
          resource = request.path,
          message = "could not locate requested right",
          developerMessage = "Could not locate a right grant with the given name, for that account in that org."
        ))
      }
    } yield grant
    renderTry[GestaltRightGrant](Ok)(right)
  }

  def getAppAccountRight(appId: UUID, accountId: UUID, grantName: String) = AuthenticatedAction(resolveAppOrg(appId)) { implicit request =>
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

  def getAppAccountRightByUsername(appId: UUID, username: String, grantName: String) = AuthenticatedAction(resolveAppOrg(appId)) { implicit request =>
    requireAuthorization(LIST_APP_GRANTS)
    val grant = for {
      account <- AppFactory.getUsernameInDefaultAccountStore(appId, username)
      grant <- AccountFactory.getAppAccountGrant(appId, account.id.asInstanceOf[UUID], grantName) match {
        case Some(g) => Success(g)
        case None => Failure(ResourceNotFoundException(
          resource = request.path,
          message = "could not locate requested right",
          developerMessage = "Could not locate a right grant with the given name, for that account in that app."
        ))
      }
    } yield grant
    renderTry[GestaltRightGrant](Ok)(grant)
  }

  def getDirectory(dirId: UUID) = AuthenticatedAction(resolveDirectoryOrg(dirId)) { implicit request =>
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

  def getDirAccountByUsername(dirId: UUID, username: String) = AuthenticatedAction(resolveDirectoryOrg(dirId)) { implicit request =>
    requireAuthorization(READ_DIRECTORY)
    DirectoryFactory.find(dirId) match {
      case Some(dir) =>
        AccountFactory.findInDirectoryByName(dirId, username) match {
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

  def getDirGroupByName(dirId: UUID, groupName: String) = AuthenticatedAction(resolveDirectoryOrg(dirId)) { implicit request =>
    requireAuthorization(READ_DIRECTORY)
    DirectoryFactory.find(dirId) match {
      case Some(dir) =>
        GroupFactory.findInDirectoryByName(dirId, groupName) match {
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

  def getAccountStoreMapping(mapId: UUID) = AuthenticatedAction(resolveMappingOrg(mapId)) { implicit request =>
    services.accountStoreMappingService.find(mapId) match {
      case Some(asm) => Ok(Json.toJson[GestaltAccountStoreMapping](asm))
      case None => NotFound(Json.toJson(ResourceNotFoundException(
        resource = request.path,
        message = "could not locate requested account store mapping",
        developerMessage = "Could not locate the requested account store mapping."
      )))
    }
  }

  def rootOrgSync() = AuthenticatedAction(resolveFromCredentials _) { implicit request =>
    Ok(Json.toJson(OrgFactory.orgSync(request.user.orgId)))
  }

  def subOrgSync(orgId: UUID) = AuthenticatedAction(Some(orgId)) { implicit request =>
    OrgFactory.find(orgId) match {
      case Some(org) => Ok(Json.toJson(OrgFactory.orgSync(org.id.asInstanceOf[UUID])))
      case None => NotFound(Json.toJson(ResourceNotFoundException(
        resource = request.path,
        message = "could not locate requested org",
        developerMessage = "Could not locate the requested org. Make sure to use the org ID."
      )))
    }
  }

  def listAllOrgs() = AuthenticatedAction(resolveFromCredentials _) { implicit request =>
    Ok(Json.toJson(OrgFactory.getOrgTree(request.user.orgId).map { o => o: GestaltOrg }))
  }

  def listOrgTree(orgId: UUID) = AuthenticatedAction(Some(orgId)) { implicit request =>
    OrgFactory.find(orgId) match {
      case Some(org) =>
        Ok(Json.toJson(OrgFactory.getOrgTree(org.id.asInstanceOf[UUID]).filter(_.id != orgId).map { o => o: GestaltOrg }))
      case None => NotFound(Json.toJson(ResourceNotFoundException(
        resource = request.path,
        message = "could not locate requested org",
        developerMessage = "Could not locate the requested org. Make sure to use the org ID."
      )))
    }
  }

  def listAppAccounts(appId: UUID) = AuthenticatedAction(resolveAppOrg(appId)) { implicit request =>
    AppFactory.findByAppId(appId) match {
      case Some(app) => Ok(Json.toJson[Seq[GestaltAccount]](
        AccountFactory.listEnabledAppUsers(app.id.asInstanceOf[UUID]) map { a => a: GestaltAccount }
      ))
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
    val grants = for {
      account <- AppFactory.getUsernameInOrgDefaultAccountStore(orgId, username)
      grants = AccountFactory.listAppAccountGrants(request.user.serviceAppId, account.id.asInstanceOf[UUID])
    } yield grants
    renderTry[Seq[GestaltRightGrant]](Ok)(grants)
  }

  def listAppAccountRights(appId: UUID, accountId: UUID) = AuthenticatedAction(resolveAppOrg(appId)) { implicit request =>
    val rights = AccountFactory.listAppAccountGrants(appId, accountId)
    Ok(Json.toJson[Seq[GestaltRightGrant]](rights map { r => r: GestaltRightGrant }))
  }

  def listAppAccountRightsByUsername(appId: UUID, username: String) = AuthenticatedAction(resolveAppOrg(appId)) { implicit request =>
    requireAuthorization(LIST_APP_GRANTS)
    requireAuthorization(READ_DIRECTORY)
    val grants = for {
      account <- AppFactory.getUsernameInDefaultAccountStore(appId, username)
      grants = AccountFactory.listAppAccountGrants(request.user.serviceAppId, account.id.asInstanceOf[UUID])
    } yield grants
    renderTry[Seq[GestaltRightGrant]](Ok)(grants)
  }

  def getRightGrant(rightId: UUID) = AuthenticatedAction(resolveGrantOrg(rightId)) { implicit request =>
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

  def getServiceApp(orgId: java.util.UUID) = AuthenticatedAction(Some(orgId)) { implicit request =>
    AppFactory.findServiceAppForOrg(orgId) match {
      case Some(app) => Ok(Json.toJson[GestaltApp](app))
      case None => defaultResourceNotFound
    }
  }

  def listDirAccounts(dirId: UUID) = AuthenticatedAction(resolveDirectoryOrg(dirId)) { implicit request =>
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

  def listDirGroups(dirId: UUID) = AuthenticatedAction(resolveDirectoryOrg(dirId)) { implicit request =>
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
    val nameQuery = request.getQueryString("username")
    val emailQuery = request.getQueryString("email")
    val phoneQuery = request.getQueryString("phoneNumber")
    if (nameQuery.isDefined || emailQuery.isDefined || phoneQuery.isDefined) {
      Ok(Json.toJson(
        AccountFactory.lookupByAppId(
          appId = request.user.serviceAppId,
          nameQuery = nameQuery,
          emailQuery = emailQuery,
          phoneQuery = phoneQuery
        ).map { a => a: GestaltAccount }
      ))
    } else {
      Ok(Json.toJson(
        AccountFactory.listEnabledAppUsers(request.user.serviceAppId).map { a => a: GestaltAccount}
      ))
    }
  }

  def listOrgGroups(orgId: UUID) = AuthenticatedAction(Some(orgId)) { implicit request =>
    val nameQuery = request.getQueryString("name")
    val results = if (nameQuery.isDefined) {
      SDKGroupFactory.lookupAppGroups(
        appId = request.user.serviceAppId,
        nameQuery = nameQuery.get
      )
    } else {
      SDKGroupFactory.queryShadowedAppGroups( request.user.serviceAppId, None )
    }
    Ok(Json.toJson[Seq[GestaltGroup]](results))
  }

  def listAccountGroups(accountId: UUID) = AuthenticatedAction(resolveAccountOrg(accountId)) { implicit request =>
    AccountFactory.find(accountId) match {
      case Some(account) => Ok(Json.toJson[Seq[GestaltGroup]](
        GroupFactory.listAccountGroups(accountId = accountId).map { g => g: GestaltGroup }
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
    val newOrg = OrgFactory.createSubOrgWithAdmin(
      parentOrgId = parentOrgId,
      creator = request.user.identity,
      create = validateBody[GestaltOrgCreate]
    )
    renderTry[GestaltOrg](Created)(newOrg)
  }

  def createOrgAccount(parentOrgId: UUID) = AuthenticatedAction(Some(parentOrgId))(parse.json) { implicit request =>
    requireAuthorization(CREATE_ACCOUNT)
    val create = validateBody[GestaltAccountCreateWithRights]
    val newAccount = AppFactory.createAccountInApp(appId = request.user.serviceAppId, create)
    renderTry[GestaltAccount](Created)(newAccount)
  }

  def createOrgGroup(parentOrgId: UUID) = AuthenticatedAction(Some(parentOrgId))(parse.json) { implicit request =>
    requireAuthorization(CREATE_GROUP)
    val create = validateBody[GestaltGroupCreateWithRights]
    val newGroup = AppFactory.createGroupInApp(appId = request.user.serviceAppId, create)
    renderTry[GestaltGroup](Created)(newGroup)
  }

  def createOrgAccountStore(orgId: UUID) = AuthenticatedAction(Some(orgId))(parse.json) { implicit request =>
    requireAuthorization(CREATE_ACCOUNT_STORE)
    val create = validateBody[GestaltAccountStoreMappingCreate]
    val newMapping = AppFactory.createOrgAccountStoreMapping(orgId, create)
    renderTry[GestaltAccountStoreMapping](Created)(newMapping)
  }

  def createAppAccountStoreMapping(appId: UUID) = AuthenticatedAction(resolveAppOrg(appId))(parse.json) { implicit request =>
    requireAuthorization(CREATE_ACCOUNT_STORE)
    val create = validateBody[GestaltAccountStoreMappingCreate]
    val newMapping = AppFactory.createAppAccountStoreMapping(appId = appId, create)
    renderTry[GestaltAccountStoreMapping](Created)(newMapping)
  }

  def createOrgApp(orgId: UUID) = AuthenticatedAction(Some(orgId))(parse.json) { implicit request =>
    requireAuthorization(CREATE_APP)
    val create = validateBody[GestaltAppCreate]
    val newApp = AppFactory.create(orgId = orgId, name = create.name, isServiceOrg = false)
    renderTry[GestaltApp](Created)(newApp)
  }

  def createOrgDirectory(orgId: UUID) = AuthenticatedAction(Some(orgId))(parse.json) { implicit request =>
    requireAuthorization(CREATE_DIRECTORY)
    val create = validateBody[GestaltDirectoryCreate]
    val newDir = DirectoryFactory.createDirectory(orgId = orgId, create)
    renderTry[GestaltDirectory](Created)(newDir)
  }

  def createAppAccount(appId: UUID) = AuthenticatedAction(resolveAppOrg(appId))(parse.json) { implicit request =>
    requireAuthorization(CREATE_ACCOUNT)
    val create = validateBody[GestaltAccountCreateWithRights]
    val newAccount = AppFactory.createAccountInApp(appId = appId, create)
    renderTry[GestaltAccount](Created)(newAccount)
  }

  def createAppGroup(appId: UUID) = AuthenticatedAction(resolveAppOrg(appId))(parse.json) { implicit request =>
    requireAuthorization(CREATE_GROUP)
    val create = validateBody[GestaltGroupCreateWithRights]
    val newGroup = AppFactory.createGroupInApp(appId = appId, create)
    renderTry[GestaltGroup](Created)(newGroup)
  }

  def createDirAccount(dirId: UUID) = AuthenticatedAction(resolveDirectoryOrg(dirId))(parse.json) { implicit request =>
    requireAuthorization(CREATE_ACCOUNT)
    val create = validateBody[GestaltAccountCreate]
    val newAccount = DirectoryFactory.createAccountInDir(dirId = dirId, create)
    renderTry[GestaltAccount](Created)(newAccount)
  }

  def createDirGroup(dirId: UUID) = AuthenticatedAction(resolveDirectoryOrg(dirId))(parse.json) { implicit request =>
    requireAuthorization(CREATE_GROUP)
    val create = validateBody[GestaltGroupCreate]
    val newGroup = DirectoryFactory.createGroupInDir(dirId = dirId, create)
    renderTry[GestaltGroup](Created)(newGroup)
  }

  def createOrgAccountRight(orgId: UUID, accountId: UUID) = AuthenticatedAction(Some(orgId))(parse.json) { implicit request =>
    requireAuthorization(CREATE_ORG_GRANT)
    val grant = validateBody[GestaltGrantCreate]
    val newGrant = RightGrantFactory.addRightToAccount(request.user.serviceAppId, accountId, grant)
    renderTry[GestaltRightGrant](Created)(newGrant)
  }

  def createOrgGroupRight(orgId: UUID, groupId: UUID) = AuthenticatedAction(Some(orgId))(parse.json) { implicit request =>
    requireAuthorization(CREATE_ORG_GRANT)
    val grant = validateBody[GestaltGrantCreate]
    val newGrant = RightGrantFactory.addRightToGroup(request.user.serviceAppId, groupId, grant)
    renderTry[GestaltRightGrant](Ok)(newGrant)
  }

  def createAppGroupRight(appId: UUID, groupId: UUID) = AuthenticatedAction(resolveAppOrg(appId))(parse.json) { implicit request =>
    requireAuthorization(CREATE_APP_GRANT)
    val grant = validateBody[GestaltGrantCreate]
    val newGrant = RightGrantFactory.addRightToGroup(appId, groupId, grant)
    renderTry[GestaltRightGrant](Ok)(newGrant)
  }

  def createAppAccountRight(appId: UUID, accountId: UUID) = AuthenticatedAction(resolveAppOrg(appId))(parse.json) { implicit request =>
    requireAuthorization(CREATE_APP_GRANT)
    val grant = validateBody[GestaltGrantCreate]
    val newGrant = RightGrantFactory.addRightToAccount(appId, accountId, grant)
    renderTry[GestaltRightGrant](Created)(newGrant)
  }

  ////////////////////////////////////////////////////////
  // Update methods
  ////////////////////////////////////////////////////////

  def removeAccountEmail(accountId: UUID) = AuthenticatedAction(resolveAccountOrg(accountId)) { implicit request =>
    // user can update their own account
    if (request.user.identity.id != accountId) requireAuthorization(UPDATE_ACCOUNT)
    AccountFactory.find(accountId) match {
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

  def removeAccountPhoneNumber(accountId: UUID) = AuthenticatedAction(resolveAccountOrg(accountId)) { implicit request =>
    // user can update their own account
    if (request.user.identity.id != accountId) requireAuthorization(UPDATE_ACCOUNT)
    AccountFactory.find(accountId) match {
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

  def updateGroupMembership(groupId: java.util.UUID) = AuthenticatedAction(resolveGroupOrg(groupId))(parse.json) { implicit request =>
    val payload = validateBody[Seq[PatchOp]]
    requireAuthorization(UPDATE_GROUP)
    GroupFactory.find(groupId) match {
      case Some(group) =>
        val newMembers = GroupFactory.updateGroupMembership(groupId, payload)
        renderTry[Seq[ResourceLink]](Ok)(
          newMembers map { _.map {a => (a: GestaltAccount).getLink()} }
        )
      case None => NotFound(Json.toJson(ResourceNotFoundException(
        resource = request.path,
        message = "could not locate requested group",
        developerMessage = "Could not locate the requested group. Make sure to use the group ID and not the group name."
      )))
    }
  }

  def updateAccount(accountId: UUID) = AuthenticatedAction(resolveAccountOrg(accountId))(parse.json) { implicit request =>
     println("RESTAPIController.updateAccount()....")
    val payload: Either[GestaltAccountUpdate, Seq[PatchOp]] = request.body.validate[Seq[PatchOp]] match {
      case s: JsSuccess[Seq[PatchOp]] => Right(s.get)
      case _ => request.body.validate[GestaltAccountUpdate] match {
        case s: JsSuccess[GestaltAccountUpdate] => Left(s.get)
        case _ => throw new BadRequestException(
          resource = request.path,
          message = "invalid payload",
          developerMessage = s"Payload could not be parsed; was expecting JSON representation of SDK object GestaltAccountUpdate or an array of PatchOp objects"
        )
      }
    }
    // user can update their own account
    if (request.user.identity.id != accountId) requireAuthorization(UPDATE_ACCOUNT)
    AccountFactory.find(accountId) match {
      case Some(account) =>
        val updatedAccount = payload.fold(
          update => AccountFactory.updateAccountSDK(account, update),
          patches => AccountFactory.updateAccount(account, patches)
        )
        renderTry[GestaltAccount](Ok)(updatedAccount)
      case None => NotFound(Json.toJson(ResourceNotFoundException(
        resource = request.path,
        message = "could not locate requested account",
        developerMessage = "Could not locate the requested account. Make sure to use the account ID and not the username."
      )))
    }
  }

  def updateRightGrant(rightId: UUID) = AuthenticatedAction(resolveGrantOrg(rightId))(parse.json) { implicit request =>
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

  def updateAccountStoreMapping(mapId: UUID) = AuthenticatedAction(resolveMappingOrg(mapId))(parse.json) { implicit request =>
    requireAuthorization(UPDATE_ACCOUNT_STORE)
    val patch = validateBody[Seq[PatchOp]]
    services.accountStoreMappingService.find(mapId) match {
      case None => defaultResourceNotFound
      case Some(map) =>
        val updated: AccountStoreMappingRepository = services.accountStoreMappingService.updateMapping( map, patch )
        Ok(Json.toJson[GestaltAccountStoreMapping](updated))
    }
  }

  def updateAppAccountRight(appId: UUID, accountId: UUID, grantName: String) = AuthenticatedAction(resolveAppOrg(appId))(parse.json) { implicit request =>
    requireAuthorization(UPDATE_APP_GRANT)
    val patch = validateBody[Seq[PatchOp]]
    val grant = AccountFactory.updateAppAccountGrant(appId, accountId, grantName, patch)
    Ok(Json.toJson[GestaltRightGrant](grant: GestaltRightGrant))
  }

  def updateAppAccountRightByUsername(appId: UUID, username: String, grantName: String) = AuthenticatedAction(resolveAppOrg(appId))(parse.json) { implicit request =>
    requireAuthorization(UPDATE_APP_GRANT)
    requireAuthorization(READ_DIRECTORY)
    val patch = validateBody[Seq[PatchOp]]
    val updated = for {
      account <- AppFactory.getUsernameInDefaultAccountStore(appId, username)
      grant = AccountFactory.updateAppAccountGrant(appId, account.id.asInstanceOf[UUID], grantName, patch)
    } yield grant
    renderTry[GestaltRightGrant](Ok)(updated)
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
    val updated = for {
      account <- AppFactory.getUsernameInOrgDefaultAccountStore(orgId, username)
      grant = AccountFactory.updateAppAccountGrant(request.user.serviceAppId, account.id.asInstanceOf[UUID], grantName, patch)
    } yield grant
    renderTry[GestaltRightGrant](Ok)(updated)
  }

  ////////////////////////////////////////////////////////
  // Delete methods
  ////////////////////////////////////////////////////////

  def deleteAccountStoreMapping(mapId: UUID) = AuthenticatedAction(resolveMappingOrg(mapId)).async { implicit request =>
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

  def deleteDirectory(dirId: UUID) = AuthenticatedAction(resolveDirectoryOrg(dirId)).async { implicit request =>
    requireAuthorization(DELETE_DIRECTORY)
    DirectoryFactory.find(dirId) match {
      case Some(dir) =>
        Future {
          DirectoryFactory.removeDirectory(dirId)
        } map {
          _ => Ok(Json.toJson(DeleteResult(true)))
        }
      case None =>
        Future(NotFound(Json.toJson(ResourceNotFoundException(
          resource = request.path,
          message = "directory doe not exist",
          developerMessage = "Could not delete the target directory because it does not exist or the provided credentials do not have rights to see it."
        ))))
    }
  }

  def deleteOrgById(orgId: UUID) = AuthenticatedAction(Some(orgId)).async { implicit request =>
    val orgRights = RightGrantFactory.listAccountRights(appId = request.user.serviceAppId, accountId = request.user.identity.id.asInstanceOf[UUID])
    if (!orgRights.exists(r => (DELETE_ORG == r.grantName || r.grantName == SUPERUSER) && r.grantValue.isEmpty)) throw new ForbiddenAPIException(
      message = "Forbidden",
      developerMessage = "Forbidden. API credentials did not correspond to the parent organization or the account did not have sufficient permissions."
    )
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

  def deleteAppById(appId: UUID) = AuthenticatedAction(resolveAppOrg(appId)).async { implicit request =>
    requireAuthorization(DELETE_APP)
    AppFactory.findByAppId(appId) match {
      case Some(app) if !app.isServiceApp =>
        Future {
          AppFactory.delete(app)
        } map { _ => Ok(Json.toJson(DeleteResult(true))) }
      case Some(app) if app.isServiceApp =>
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

  def deleteGroup(groupId: java.util.UUID) = AuthenticatedAction(resolveGroupOrg(groupId)).async { implicit request =>
    requireAuthorization(DELETE_GROUP)
    GroupFactory.find(groupId) match {
      case None => Future{defaultResourceNotFound}
      case Some(group) => Future{
        DirectoryFactory.find(group.dirId.asInstanceOf[UUID]) match {
          case None => throw new UnknownAPIException(
            code = 500,
            resource = request.path,
            message = "could not locate directory associated with group",
            developerMessage = "Could not locate the directory associated with the specified group. Please contact support."
          )
          case Some(dir) =>
            val deleted = dir.deleteGroup(group.id.asInstanceOf[UUID])
            Ok(Json.toJson(DeleteResult(deleted)))
        }
      }
    }
  }

  def disableAccount(accountId: UUID) = AuthenticatedAction(resolveAccountOrg(accountId)).async { implicit request =>
    requireAuthorization(DELETE_ACCOUNT)
    AccountFactory.find(accountId) match {
      case Some(account) if !account.disabled && account.id != request.user.identity.id =>
        Future {
          DirectoryFactory.find(account.dirId.asInstanceOf[UUID]) match {
            case None => throw new UnknownAPIException(
              code = 500,
              resource = request.path,
              message = "could not locate directory associated with account",
              developerMessage = "Could not locate the directory associated with the specified account. Please contact support."
            )
            case Some(dir) =>
              dir.disableAccount(account.id.asInstanceOf[UUID])
              Ok(Json.toJson(DeleteResult(true)))
          }
        }
      case Some(account) if account.disabled => Future {
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

  def deleteRightGrant(rightId: UUID) = AuthenticatedAction(resolveGrantOrg(rightId)) { implicit request =>
    requireAuthorization(DELETE_APP_GRANT)
    Ok(Json.toJson(DeleteResult(RightGrantFactory.deleteRightGrant(rightId))))
  }

  def deleteOrgAccountRight(orgId: UUID, accountId: UUID, grantName: String) = AuthenticatedAction(Some(orgId)) { implicit request =>
    requireAuthorization(DELETE_ORG_GRANT)
    val wasDeleted = AccountFactory.deleteAppAccountGrant(request.user.serviceAppId, accountId, grantName)
    Ok(Json.toJson(DeleteResult(wasDeleted)))
  }

  def deleteOrgAccountRightByUsername(orgId: UUID, username: String, grantName: String) = AuthenticatedAction(Some(orgId)) { implicit request =>
    requireAuthorization(READ_DIRECTORY)
    requireAuthorization(DELETE_ORG_GRANT)
    val result = for {
      account <- AppFactory.getUsernameInOrgDefaultAccountStore(orgId, username)
      wasDeleted = AccountFactory.deleteAppAccountGrant(request.user.serviceAppId, account.id.asInstanceOf[UUID], grantName)
    } yield DeleteResult(wasDeleted)
    renderTry[DeleteResult](Ok)(result)
  }

  def deleteAppAccountRight(appId: UUID, accountId: UUID, grantName: String) = AuthenticatedAction(resolveAppOrg(appId)) { implicit request =>
    val wasDeleted = AccountFactory.deleteAppAccountGrant(appId, accountId, grantName)
    Ok(Json.toJson(DeleteResult(wasDeleted)))
  }

  def deleteAppAccountRightByUsername(appId: UUID, username: String, grantName: String) = AuthenticatedAction(resolveAppOrg(appId)) { implicit request =>
    requireAuthorization(DELETE_APP_GRANT)
    requireAuthorization(READ_DIRECTORY)
    val result = for {
      account <- AppFactory.getUsernameInDefaultAccountStore(appId, username)
      wasDeleted = AccountFactory.deleteAppAccountGrant(appId, account.id.asInstanceOf[UUID], grantName)
    } yield DeleteResult(wasDeleted)
    renderTry[DeleteResult](Ok)(result)
  }

  def deleteOrgGroupRight(orgId: UUID, groupId: UUID, grantName: String) = AuthenticatedAction(Some(orgId)) { implicit request =>
    val wasDeleted = AccountFactory.deleteAppGroupGrant(request.user.serviceAppId, groupId, grantName)
    Ok(Json.toJson(DeleteResult(wasDeleted)))
  }

  def deleteAppGroupRight(appId: UUID, groupId: UUID, grantName: String) = AuthenticatedAction(resolveAppOrg(appId)) { implicit request =>
    val wasDeleted = AccountFactory.deleteAppGroupGrant(appId, groupId, grantName)
    Ok(Json.toJson(DeleteResult(wasDeleted)))
  }

  def listOrgGroupRights(orgId: UUID, groupId: UUID) = AuthenticatedAction(Some(orgId)) { implicit request =>
    requireAuthorization(LIST_APP_GRANTS)
    val rights = AccountFactory.listAppGroupGrants(request.user.serviceAppId, groupId)
    Ok(Json.toJson[Seq[GestaltRightGrant]](rights map { r => r: GestaltRightGrant }))
  }

  def listAppGroupRights(appId: UUID, groupId: UUID) = AuthenticatedAction(resolveAppOrg(appId)) { implicit request =>
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

  def getAppGroupRight(appId: UUID, groupId: UUID, grantName: String) = AuthenticatedAction(resolveAppOrg(appId)) { implicit request =>
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

  def listAppGroupMappings(appId: UUID) = AuthenticatedAction(resolveAppOrg(appId)) { implicit request =>
    Ok(Json.toJson[Seq[GestaltGroup]](
      GroupFactory.queryShadowedAppGroups(
        appId = appId,
        nameQuery = None
      ).map { g => g: GestaltGroup }
    ))
  }

  def updateOrgGroupRight(orgId: UUID, groupId: UUID, grantName: String) = AuthenticatedAction(Some(orgId))(parse.json) { implicit request =>
    val patch = validateBody[Seq[PatchOp]]
    val grant = AccountFactory.updateAppGroupGrant(request.user.serviceAppId, groupId = groupId, grantName, patch)
    Ok(Json.toJson[GestaltRightGrant](grant: GestaltRightGrant))
  }

  def updateAppGroupRight(appId: UUID, groupId: UUID, grantName: String) = AuthenticatedAction(resolveAppOrg(appId))(parse.json) { implicit request =>
    val patch = validateBody[Seq[PatchOp]]
    val grant = AccountFactory.updateAppGroupGrant(appId, groupId = groupId, grantName, patch)
    Ok(Json.toJson[GestaltRightGrant](grant: GestaltRightGrant))
  }

  private def passwordGrantFlow(orgIdGen: => Option[UUID])
                               (implicit request: Request[Map[String,Seq[String]]]): Future[Result] = {
    val grantContext = for {
      orgId <- o2t(orgIdGen)(ResourceNotFoundException("","could not locate specified org",""))
      serviceApp <- o2t(AppFactory.findServiceAppForOrg(orgId))(ResourceNotFoundException("","could not locate service app for the specified org",""))
      serviceAppId = serviceApp.id.asInstanceOf[UUID]
      creds <- o2t(for {
        grant_type <- request.body.get("grant_type") flatMap asSingleton
        if grant_type == "password"
        username <- request.body.get("username") flatMap asSingleton
        if !username.contains("*")
        password <- request.body.get("password") flatMap asSingleton
      } yield GestaltBasicCredsToken(username = username, password = password))(OAuthError(INVALID_REQUEST,"Invalid content in one of required fields: `username` or `password`"))
    } yield (orgId,serviceAppId,creds)

    grantContext match {
      case Success((orgId,serviceAppId,creds)) =>
        implicit val timeout = Timeout(5 seconds)
        val fRateCheck = rateCheckActor ? TokenGrantRateLimitCheck(serviceAppId.toString, creds.username)
        fRateCheck map { _ match {
          case RequestAccepted => renderTry[AccessTokenResponse](Ok)(for {
            account <- o2t(AccountFactory.authenticate(serviceAppId, creds))(OAuthError(INVALID_GRANT,"The provided authorization grant is invalid, expired or revoked."))
            newToken <- TokenFactory.createToken(
              orgId = Some(orgId),
              accountId = account.id.asInstanceOf[UUID],
              validForSeconds = defaultTokenExpiration,
              tokenType = ACCESS_TOKEN,
              parentApiKey = None
            )
          } yield AccessTokenResponse(accessToken = newToken, refreshToken = None, tokenType = BEARER, expiresIn = defaultTokenExpiration, gestalt_access_token_href = ""))
          case _ => oAuthErr(INVALID_GRANT,"Rate limit exceeded")
        } }
      case Failure(ex) => Future.successful(Global.handleError(request, ex))
    }
  }

  private def clientCredGrantFlow(orgIdGen: APICredentialRepository => Option[UUID])
                                 (implicit request: Request[Map[String,Seq[String]]]): Future[Result] = Future {
    GestaltHeaderAuthentication.authenticateHeader(request) match {
      case None => oAuthErr(INVALID_CLIENT, "client_credential grant requires client authentication")
      case Some(auth) => auth.credential match {
        case Right(token) => oAuthErr(INVALID_CLIENT, "client_credential grant requires client is authenticated using API credentials and does not support token authentication")
        case Left(apiKey) =>
          orgIdGen(apiKey) match {
            case None => oAuthErr(INVALID_GRANT, "the authenticated client does not belong to the specified organization or the organization does not exist")
            case Some(orgId) =>
              val authResp = TokenFactory.createToken(
                  orgId = Some(orgId),
                  accountId = apiKey.accountId.asInstanceOf[UUID],
                  validForSeconds = defaultTokenExpiration,
                  tokenType = ACCESS_TOKEN,
                  parentApiKey = Some(apiKey.id)
                ) map {newToken => AccessTokenResponse(
                accessToken = newToken,
                refreshToken = None,
                tokenType = BEARER,
                expiresIn = defaultTokenExpiration,
                gestalt_access_token_href = "")}
              renderTry[AccessTokenResponse](Ok)(authResp)
          }
      }
    }
  }

  def globalTokenIssue() = Action.async(parse.urlFormEncoded) { implicit request =>
    def getIssuedOrgFromAPIKey = (apiKey: APICredentialRepository) => apiKey.issuedOrgId map (_.asInstanceOf[UUID])

    request.body.get("grant_type") flatMap asSingleton match {
      case Some("client_credentials") => clientCredGrantFlow(getIssuedOrgFromAPIKey)
      case Some(t) => Future{oAuthErr(UNSUPPORTED_GRANT_TYPE, "global token issue endpoint only support client_credentials grant")}
      case None => Future{oAuthErr(INVALID_REQUEST, "request must contain a single grant_type field")}
    }
  }

  def orgTokenIssue(orgId: UUID) = Action.async(parse.urlFormEncoded) { implicit request =>
    def verifyKeyOwnerBelongsToOrg = (apiKey: APICredentialRepository) => for {
        app <- AppFactory.findServiceAppForOrg(orgId)
        account <- AccountFactory.getAppAccount(app.id.asInstanceOf[UUID], apiKey.accountId.asInstanceOf[UUID])
    } yield orgId

    request.body.get("grant_type") flatMap asSingleton match {
      case Some("client_credentials") => clientCredGrantFlow(verifyKeyOwnerBelongsToOrg)
      case Some("password") => passwordGrantFlow(Some(orgId))
      case Some(t) => Future{oAuthErr(UNSUPPORTED_GRANT_TYPE, "org token issue endpoints only support client_credentials and password grants")}
      case None => Future{oAuthErr(INVALID_REQUEST, "request must contain a single grant_type field")}
    }
  }

  private def genericTokenIntro(getOrgId: TokenRepository => Option[UUID])
                               (implicit request: Request[Map[String,Seq[String]]]): Result = {
    GestaltHeaderAuthentication.authenticateHeader(request) match {
      case None => oAuthErr(INVALID_CLIENT,"token introspection requires client authentication")
      case Some(auth) =>
        val introspection = for {
          tokenStr <- o2t(request.body.get("token") flatMap asSingleton)(OAuthError(INVALID_REQUEST,"Invalid content in one of required fields: `token`"))
          tokenAndAccount = for {
            token <- TokenFactory.findValidToken(tokenStr)
            orgId <- getOrgId(token)
            serviceApp <- AppFactory.findServiceAppForOrg(orgId)
            serviceAppId = serviceApp.id.asInstanceOf[UUID]
            account <- AccountFactory.getAppAccount(serviceAppId, token.accountId.asInstanceOf[UUID])
          } yield (token,account,serviceAppId,orgId)
          intro = tokenAndAccount.fold[TokenIntrospectionResponse](INVALID_TOKEN){
            case (token,orgAccount,serviceAppId,orgId) => ValidTokenResponse(
              username = orgAccount.username,
              sub = orgAccount.href,
              iss = "todo",
              exp = token.expiresAt.getMillis/1000,
              iat = token.issuedAt.getMillis/1000,
              jti = token.id.asInstanceOf[UUID],
              gestalt_token_href = token.href,
              gestalt_org_id = orgId,
              gestalt_account = orgAccount,
              gestalt_groups = GroupFactory.listAccountGroups(accountId = orgAccount.id.asInstanceOf[UUID]) map { g => (g: GestaltGroup).getLink },
              gestalt_rights = RightGrantFactory.listAccountRights(serviceAppId, orgAccount.id.asInstanceOf[UUID]) map { r => r: GestaltRightGrant }
            )
          }
        } yield intro
        renderTry[TokenIntrospectionResponse](Ok)(introspection)
    }
  }

  def orgTokenInspect(orgId: UUID) = Action(parse.urlFormEncoded) { implicit request =>
    genericTokenIntro(_ => Some(orgId))
  }

  def globalTokenInspect() = Action(parse.urlFormEncoded) { implicit request =>
    genericTokenIntro( token => token.issuedOrgId map {_.asInstanceOf[UUID]} )
  }

  def deleteToken(tokenId: UUID) = AuthenticatedAction(resolveTokenOrg(tokenId)) { implicit request =>
    val token = TokenFactory.findValidById(tokenId)
    if (! token.exists {_.accountId == request.user.identity.id}) requireAuthorization(DELETE_TOKEN)
    token foreach { t =>
      Logger.info(s"deleting token ${t.id}")
      TokenRepository.destroy(t)
    }
    renderTry[DeleteResult](Ok)(Success(DeleteResult(token.isDefined)))
  }

  def deleteApiKey(apiKey: UUID) = AuthenticatedAction(resolveApiKeyOrg(apiKey)) { implicit request =>
    val key = APICredentialFactory.findByAPIKey(apiKey.toString)
    if (! key.exists {_.accountId == request.user.identity.id}) requireAuthorization(DELETE_APIKEY)
    key foreach { k =>
      Logger.info(s"deleting apiKey ${k.id}")
      APICredentialRepository.destroy(k)
    }
    renderTry[DeleteResult](Ok)(Success(DeleteResult(key.isDefined)))
  }

  def generateAPIKey(accountId: UUID) = AuthenticatedAction(resolveAccountOrg(accountId))(parse.json) { implicit request =>
    if ( request.user.identity.id != accountId ) requireAuthorization(CREATE_APIKEY)
    val explicitOrgId = (request.body \ "orgId").asOpt[UUID]
    val currentOrg = if (request.user.identity.id == accountId) Some(request.user.orgId) else None
    // must have org to bind api key to
    val orgId = explicitOrgId orElse currentOrg
    // only generate if using api key authentication
    val apiKey = request.user.credential.left.toOption map {_.apiKey.asInstanceOf[UUID]}

    (apiKey,orgId) match {
      case (None,_) => BadRequest(Json.toJson(BadRequestException(
        resource = "",
        message = "API key creation requires API key authentication",
        developerMessage = "API key creation request must be authenticated using API keys and does not support bearer token authentication"
      )))
      case (Some(key),None) => BadRequest(Json.toJson(BadRequestException(
        resource = "",
        message = "delegated API key creation requires orgId",
        developerMessage = "API key creation on behalf of another account requires an orgId against which to bind the API key."
      )))
      case (Some(key),Some(boundOrgId)) =>
        val newKey = APICredentialFactory.createAPIKey(
          accountId = accountId,
          boundOrg = Some(boundOrgId),
          parentApiKey = Some(key)
        )
        renderTry[GestaltAPIKey](Created)(newKey)
    }
  }

}
