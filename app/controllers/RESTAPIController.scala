package controllers

import java.security.cert.X509Certificate
import java.util.UUID
import javax.inject.{Inject, Named}

import akka.util.Timeout
import com.galacticfog.gestalt.security.actors.RateLimitingActor
import com.galacticfog.gestalt.security.actors.RateLimitingActor.{RequestAccepted, TokenGrantRateLimitCheck}
import com.galacticfog.gestalt.security.api.AccessTokenResponse.BEARER
import com.galacticfog.gestalt.security.api.GestaltToken.ACCESS_TOKEN
import com.galacticfog.gestalt.security.{BuildInfo, Init, SecurityConfig}
import com.galacticfog.gestalt.security.api._
import com.galacticfog.gestalt.security.api.errors._
import com.galacticfog.gestalt.security.data.domain._
import com.galacticfog.gestalt.security.data.model._
import com.galacticfog.gestalt.keymgr.GestaltLicense
import com.galacticfog.gestalt.keymgr.GestaltFeature
import play.api.libs.json._

import scala.concurrent.ExecutionContext.Implicits.global
import com.galacticfog.gestalt.security.data.APIConversions._
import com.galacticfog.gestalt.security.api.json.JsonImports._
import OrgFactory.Rights._
import akka.actor.ActorRef

import scala.concurrent.Future
import scala.util.{Failure, Success, Try}
import akka.pattern.ask
import com.galacticfog.gestalt.patch.PatchOp
import play.api.mvc.Security.AuthenticatedRequest
import AuditEvents._
import AuditEvents.{ga2userInfo, uar2userInfo}
import play.api.Logger

import scala.concurrent.duration._
import play.api.mvc._

class RESTAPIController @Inject()( config: SecurityConfig,
                                   accountStoreMappingService: AccountStoreMappingService,
                                   init: Init,
                                   override val auditer: Auditer,
                                   @Named(RateLimitingActor.ACTOR_NAME) rateCheckActor: ActorRef )
  extends Controller with GestaltHeaderAuthentication with ControllerHelpers with WithAuditer {

  val defaultTokenExpiration: Long =  config.tokenLifetime.getStandardSeconds

  ////////////////////////////////////////////////////////////////
  // Utility methods
  ////////////////////////////////////////////////////////////////

  private[this] implicit def s2s: Seq[RightGrantRepository] => Seq[GestaltRightGrant] =
    _.map {g => g: GestaltRightGrant}

  private[this] def requireAuthorization[T](requiredRight: String)(implicit request: Security.AuthenticatedRequest[T, GestaltHeaderAuthentication.AccountWithOrgContext]) = {
    val rights = RightGrantFactory.listAccountRights(appId = request.user.serviceAppId, accountId = request.user.identity.id.asInstanceOf[UUID])
    if (!rights.exists(r => (requiredRight == r.grantName || r.grantName == SUPERUSER) && r.grantValue.isEmpty)) throw ForbiddenAPIException(
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
    mapping <- accountStoreMappingService.find(mapId)
    app <- AppFactory.find(mapping.appId.asInstanceOf[UUID])
  } yield app.orgId.asInstanceOf[UUID]

  ////////////////////////////////////////////////////////
  // Auth methods
  ////////////////////////////////////////////////////////

  private[this] def authenticateWithClientCredentials(request: AuthenticatedRequest[AnyContent,GestaltHeaderAuthentication.AccountWithOrgContext]): Result = {
    val accountId = request.user.identity.id.asInstanceOf[UUID]
    val groups = GroupFactory.listAccountGroups(accountId = accountId)
    val rights = RightGrantFactory.listAccountRights(appId = request.user.serviceAppId, accountId = accountId)
    val extraData = request.body.asJson.flatMap(_.asOpt[Map[String,String]])
    val ar = GestaltAuthResponse(account = request.user.identity, groups = groups map { g => (g: GestaltGroup).getLink }, rights = rights map { r => r: GestaltRightGrant }, orgId = request.user.orgId, extraData = extraData)
    auditer(AuthAttempt(Some(request.user.identity), true, Some("org",request.user.orgId), Some(request.user.identity)))(request)
    Ok(Json.toJson(ar))
  }

  def orgAuth(orgId: UUID) = AuthenticatedAction(Some(orgId), AuthAttempt(Some("org",orgId)))(authenticateWithClientCredentials(_))

  def globalAuth() = AuthenticatedAction(resolveFromCredentials _, AuthAttempt())(authenticateWithClientCredentials(_))

  def appAuth(appId: UUID) = AuthenticatedAction(resolveAppOrg(appId), AuthAttempt(Some("app",appId)))(parse.json)(
    withAuthorization(AUTHENTICATE_ACCOUNTS, AuthAttempt(Some("app",appId))) { event => implicit request =>
      withBody[GestaltBasicCredsToken](event) { creds =>
        AppFactory.findByAppId(appId).fold {
          val ex = UnknownAPIException(
            code = 500,
            resource = request.path,
            message = "error looking up application",
            developerMessage = "The application could not be located, but the API request was authenticated. This suggests a problem with the database; please try again or contact support."
          )
          auditer(mapExceptionToFailedEvent(ex, event))
          handleError(request, ex)
        } { app =>
          val tryAuth = AccountFactory.authenticate(app.id.asInstanceOf[UUID], creds).fold[Try[GestaltAuthResponse]] {
            Failure(ResourceNotFoundException(
              resource = request.path,
              message = "invalid username or password",
              developerMessage = "The specified credentials do not match any in the application's account stores."
            ))
          } { account => Try {
            val accountId = account.id.asInstanceOf[UUID]
            val rights = RightGrantFactory.listAccountRights(appId = appId, accountId = accountId)
            val groups = GroupFactory.listAccountGroups(accountId = accountId)
            GestaltAuthResponse(
              account,
              groups map { g => (g: GestaltGroup).getLink },
              rights map { r => r: GestaltRightGrant },
              orgId = app.orgId.asInstanceOf[UUID],
              extraData = None
            )
          }}
          auditTry(tryAuth, event){case (e,t) => e.copy(
            successful = true,
            authenticatedAccount = Some(t.account)
          )}
          renderTry[GestaltAuthResponse](Ok)(tryAuth)
        }
      }
    }
  )

  ////////////////////////////////////////////////////////
  // Get/List methods
  ////////////////////////////////////////////////////////

  def getHealth = Action.async { request =>
    Future {
      Try {
        resolveRoot
      } match {
        case Success(Some(_)) =>
          Ok("healthy")
        case Success(None) =>
          InternalServerError("could not find root org; check database version")
        case Failure(_) =>
          init.isInit match {
            case Success(true) =>
              InternalServerError("service is initialized but could not determine root org")
            case Success(false) =>
              BadRequest("service not initialized")
            case Failure(t) =>
              handleError(request, t)
          }
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

  def getCurrentOrg = AuthenticatedAction(resolveFromCredentials _, GetCurrentOrgAttempt()) { implicit request =>
    OrgFactory.findByOrgId(request.user.orgId) match {
      case Some(org) =>
        auditer(GetCurrentOrgAttempt(Some(request.user.identity),true))
        Ok(Json.toJson[GestaltOrg](org))
      case None =>
        auditer(FailedNotFound(GetCurrentOrgAttempt(Some(request.user.identity), false)))
        NotFound(Json.toJson(ResourceNotFoundException(
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
    val ok  = Ok
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
    if ( DirectoryFactory.find(dirId).isDefined ) {
      AccountFactory.findInDirectoryByName(dirId, username) match {
        case Some(account) => Ok(Json.toJson[GestaltAccount](account))
        case None => NotFound(Json.toJson(ResourceNotFoundException(
          resource = request.path,
          message = "could not locate requested account in the directory",
          developerMessage = "Could not locate the requested account in the directory. Make sure to use the account username."
        )))
      }
    }
    else NotFound(Json.toJson(ResourceNotFoundException(
      resource = request.path,
      message = "could not locate requested directory",
      developerMessage = "Could not locate the requested directory. Make sure to use the directory ID."
    )))
  }

  def getDirGroupByName(dirId: UUID, groupName: String) = AuthenticatedAction(resolveDirectoryOrg(dirId)) { implicit request =>
    requireAuthorization(READ_DIRECTORY)
    if ( DirectoryFactory.find(dirId).isDefined ) {
      GroupFactory.findInDirectoryByName(dirId, groupName) match {
        case Some(group) => Ok(Json.toJson[GestaltGroup](group))
        case None => NotFound(Json.toJson(ResourceNotFoundException(
          resource = request.path,
          message = "could not locate requested group in the directory",
          developerMessage = "Could not locate the requested group in the directory. Make sure to use the group name."
        )))
      }
    }
    else NotFound(Json.toJson(ResourceNotFoundException(
      resource = request.path,
      message = "could not locate requested directory",
      developerMessage = "Could not locate the requested directory. Make sure to use the directory ID."
    )))
  }

  def getAccountStoreMapping(mapId: UUID) = AuthenticatedAction(resolveMappingOrg(mapId)) { implicit request =>
    accountStoreMappingService.find(mapId) match {
      case Some(asm) => Ok(Json.toJson[GestaltAccountStoreMapping](asm))
      case None => NotFound(Json.toJson(ResourceNotFoundException(
        resource = request.path,
        message = "could not locate requested account store mapping",
        developerMessage = "Could not locate the requested account store mapping."
      )))
    }
  }

  def rootOrgSync() = AuthenticatedAction(resolveFromCredentials _, SyncAttempt())(parse.default) (
    withAuthorization(SYNC, SyncAttempt(None)) { event => implicit request =>
      auditer(event.copy(syncRoot = Some(request.user.orgId)))
      Ok(Json.toJson(OrgFactory.orgSync(init, request.user.orgId)))
    }
  )

  def subOrgSync(orgId: UUID) = AuthenticatedAction(Some(orgId), SyncAttempt(Some(orgId)))(parse.default) (
    withAuthorization(SYNC, SyncAttempt(Some(orgId))) { event => implicit request =>
      OrgFactory.find(orgId) match {
        case Some(org) =>
          auditer(event)
          Ok(Json.toJson(OrgFactory.orgSync(init, org.id.asInstanceOf[UUID])))
        case None =>
          auditer(FailedNotFound(event))
          NotFound(Json.toJson(ResourceNotFoundException(
            resource = request.path,
            message = "could not locate requested org",
            developerMessage = "Could not locate the requested org. Make sure to use the org ID."
          )))
      }
    }
  )

  def listAllOrgs() = AuthenticatedAction(resolveFromCredentials _, ListOrgsAttempt()) { implicit request =>
    auditer(ListOrgsAttempt.success(request.user.identity, request.user.orgId))
    Ok(Json.toJson(OrgFactory.getOrgTree(request.user.orgId).map { o => o: GestaltOrg }))
  }

  def listOrgTree(orgId: UUID) = AuthenticatedAction(Some(orgId), ListOrgsAttempt(Some(orgId))) { implicit request =>
    OrgFactory.find(orgId) match {
      case Some(org) =>
        auditer(ListOrgsAttempt.success(request.user.identity, orgId))
        Ok(Json.toJson(OrgFactory.getOrgTree(org.id.asInstanceOf[UUID]).filter(_.id != orgId).map { o => o: GestaltOrg }))
      case None =>
        auditer(FailedNotFound(ListOrgsAttempt.failed(request.user.identity, orgId)))
        NotFound(Json.toJson(ResourceNotFoundException(
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
      case None =>
        NotFound(Json.toJson(ResourceNotFoundException(
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

  def listOrgDirectories(orgId: UUID) = AuthenticatedAction(Some(orgId), ListOrgDirectoriesAttempt(orgId)) { implicit request =>
    auditer(ListOrgDirectoriesAttempt(orgId, Some(request.user.identity), successful = true))
    Ok(Json.toJson[Seq[GestaltDirectory]](DirectoryFactory.listByOrgId(orgId) map { d => d: GestaltDirectory }))
  }

  def listOrgApps(orgId: UUID) = AuthenticatedAction(Some(orgId), ListOrgAppsAttempt(orgId)) { implicit request =>
    auditer(ListOrgAppsAttempt(orgId, Some(request.user.identity), successful = true))
    Ok(Json.toJson[Seq[GestaltApp]](AppFactory.listByOrgId(orgId) map { a => a: GestaltApp }))
  }

  def getServiceApp(orgId: java.util.UUID) = AuthenticatedAction(Some(orgId), ListOrgAppsAttempt(orgId)) { implicit request =>
    val event = ListOrgAppsAttempt(orgId, Some(request.user.identity))
    AppFactory.findServiceAppForOrg(orgId) match {
      case Some(app) =>
        auditer(event.copy(successful = true))
        Ok(Json.toJson[GestaltApp](app))
      case None =>
        auditer(FailedNotFound(event))
        defaultResourceNotFound
    }
  }

  def listDirAccounts(dirId: UUID) = AuthenticatedAction(resolveDirectoryOrg(dirId)) { implicit request =>
    requireAuthorization(READ_DIRECTORY)
    if ( DirectoryFactory.find(dirId).isDefined ) Ok( Json.toJson(
      AccountFactory.listByDirectoryId(dirId) map {
        a => a: GestaltAccount
      }
    ) )
    else NotFound(Json.toJson(ResourceNotFoundException(
      resource = request.path,
      message = "could not locate requested directory",
      developerMessage = "Could not locate the requested directory. Make sure to use the directory ID."
    )))
  }

  def listDirGroups(dirId: UUID) = AuthenticatedAction(resolveDirectoryOrg(dirId)) { implicit request =>
    requireAuthorization(READ_DIRECTORY)
    if ( DirectoryFactory.find(dirId).isDefined ) Ok(
      Json.toJson(
        GroupFactory.listByDirectoryId(dirId) map {
          g => g: GestaltGroup
        }
      )
    )
    else NotFound(Json.toJson(ResourceNotFoundException(
      resource = request.path,
      message = "could not locate requested directory",
      developerMessage = "Could not locate the requested directory. Make sure to use the directory ID."
    )))
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
    if ( AccountFactory.find(accountId).isDefined ) Ok(Json.toJson[Seq[GestaltGroup]](
      GroupFactory.listAccountGroups(accountId = accountId).map { g => g: GestaltGroup }
    ))
    else  NotFound(Json.toJson(ResourceNotFoundException(
      resource = request.path,
      message = "could not locate requested account",
      developerMessage = "Could not locate the requested account. Make sure to use the account ID and not the account username."
    )))
  }

  ////////////////////////////////////////////////////////
  // Create methods
  ////////////////////////////////////////////////////////

  def createOrg(parentOrgId: UUID) = AuthenticatedAction(Some(parentOrgId), CreateOrgAttempt(parentOrgId))(parse.json)(
    withAuthorization(CREATE_ORG, CreateOrgAttempt(parentOrgId)) { event => implicit request =>
      withBody[GestaltOrgCreate](event) { create =>
        val newOrg = OrgFactory.createSubOrgWithAdmin(
          parentOrgId = parentOrgId,
          creator = request.user.identity,
          create = create
        )
        auditTry(newOrg, event){case (e,t) => e.copy(successful = true, newOrg = Some(t))}
        renderTry[GestaltOrg](Created)(newOrg)
      }
    }
  )

  def createOrgAccount(parentOrgId: UUID) = AuthenticatedAction(Some(parentOrgId), CreateAccountAttempt(parentOrgId, "org"))(parse.json)(
    withAuthorization(CREATE_ACCOUNT, CreateAccountAttempt(parentOrgId, "org")) { event =>implicit request =>
      withBody[GestaltAccountCreateWithRights](event) { create =>
        val newAccount = AppFactory.createAccountInApp(appId = request.user.serviceAppId, create)
        auditTry(newAccount,event){case (e,t) => e.copy(successful = true, newAccount = Some(t))}
        renderTry[GestaltAccount](Created)(newAccount)
      }
    }
  )

  def createOrgGroup(parentOrgId: UUID) = AuthenticatedAction(Some(parentOrgId), CreateGroupAttempt(parentOrgId, "org"))(parse.json)(
    withAuthorization(CREATE_GROUP, CreateGroupAttempt(parentOrgId, "org")) { event =>implicit request =>
      withBody[GestaltGroupCreateWithRights](event) { create =>
        val newGroup = AppFactory.createGroupInApp(appId = request.user.serviceAppId, create)
        auditTry(newGroup,event){case (e,t) => e.copy(successful = true, newGroup = Some(t))}
        renderTry[GestaltGroup](Created)(newGroup)
      }
    }
  )

  def createOrgAccountStore(orgId: UUID) = AuthenticatedAction(Some(orgId), CreateAccountStoreAttempt(orgId, "org"))(parse.json)(
    withAuthorization(CREATE_ACCOUNT_STORE, CreateAccountStoreAttempt(orgId, "org")) { event => implicit request =>
      withBody[GestaltAccountStoreMappingCreate](event) { create =>
        val newMapping = AppFactory.createOrgAccountStoreMapping(orgId, create)
        auditTry(newMapping, event){case (e,t) => e.copy(successful = true, newAccountStore = Some(t))}
        renderTry[GestaltAccountStoreMapping](Created)(newMapping)
      }
    }
  )

  def createAppAccountStore(appId: UUID) = AuthenticatedAction(resolveAppOrg(appId), CreateAccountStoreAttempt(appId, "app"))(parse.json)(
    withAuthorization(CREATE_ACCOUNT_STORE, CreateAccountStoreAttempt(appId, "app")) { event => implicit request =>
      withBody[GestaltAccountStoreMappingCreate](event) { create =>
        val newMapping = AppFactory.createAppAccountStoreMapping(appId = appId, create)
        auditTry(newMapping, event){case (e,t) => e.copy(successful = true, newAccountStore = Some(t))}
        renderTry[GestaltAccountStoreMapping](Created)(newMapping)
      }
    }
  )

  def createOrgApp(orgId: UUID) = AuthenticatedAction(Some(orgId), CreateAppAttempt(orgId))(parse.json)(
    withAuthorization(CREATE_APP, CreateAppAttempt(orgId)) { event => implicit request =>
      withBody[GestaltAppCreate](event) { create =>
        val newApp = AppFactory.create(orgId = orgId, name = create.name, isServiceOrg = false)
        auditTry(newApp,event){case (e,t) => e.copy(successful = true, newApp = Some(t))}
        renderTry[GestaltApp](Created)(newApp)
      }
    }
  )

  def createOrgDirectory(orgId: UUID) = AuthenticatedAction(Some(orgId), CreateDirectoryAttempt(orgId, "org"))(parse.json)(
    withAuthorization(CREATE_DIRECTORY,CreateDirectoryAttempt(orgId, "org")) { event => implicit request =>
      withBody[GestaltDirectoryCreate](event) { create =>
        if (create.directoryType == DIRECTORY_TYPE_LDAP && !GestaltLicense.instance.isFeatureActive(GestaltFeature.LdapDirectory)) {
          throw UnknownAPIException(code = 406, resource = "", message = "Attempt to use feature AD/LDAPDirectory denied due to license.",
            developerMessage = "Attempt to use feature AD/LDAPDirectory denied due to license.")
        }
        val newDir = DirectoryFactory.createDirectory(orgId = orgId, create)
        auditTry(newDir, event){case (e,o) => e.copy(successful = true, newDirectory = Some(o))}
        renderTry[GestaltDirectory](Created)(newDir)
      }
    }
  )

  def createAppAccount(appId: UUID) = AuthenticatedAction(resolveAppOrg(appId), CreateAccountAttempt(appId, "app"))(parse.json) (
    withAuthorization(CREATE_ACCOUNT, CreateAccountAttempt(appId, "app")) { event => implicit request =>
      withBody[GestaltAccountCreateWithRights](event) { create =>
        val newAccount = AppFactory.createAccountInApp(appId = appId, create)
        auditTry(newAccount, event){case (e,o) => e.copy(successful = true, newAccount = Some(o))}
        renderTry[GestaltAccount](Created)(newAccount)
      }
    }
  )

  def createAppGroup(appId: UUID) = AuthenticatedAction(resolveAppOrg(appId), CreateGroupAttempt(appId, "app"))(parse.json) (
    withAuthorization(CREATE_GROUP, CreateGroupAttempt(appId, "app")) { event =>
      implicit request =>
        withBody[GestaltGroupCreateWithRights](event) { create =>
          val newGroup = AppFactory.createGroupInApp(appId = appId, create)
          auditTry(newGroup,event){case (e,o) => e.copy(successful = true, newGroup = Some(o))}
          renderTry[GestaltGroup](Created)(newGroup)
        }
    }
  )

  def createDirAccount(dirId: UUID) = AuthenticatedAction(resolveDirectoryOrg(dirId), CreateAccountAttempt(dirId, "directory"))(parse.json)(
    withAuthorization(CREATE_ACCOUNT, CreateAccountAttempt(dirId, "directory")) { event => implicit request =>
      withBody[GestaltAccountCreate](event) { create =>
        val newAccount = DirectoryFactory.createAccountInDir(dirId = dirId, create)
        auditTry(newAccount,event){case (e,o) => e.copy(successful = true, newAccount = Some(o))}
        renderTry[GestaltAccount](Created)(newAccount)
      }
    }
  )

  def createDirGroup(dirId: UUID) = AuthenticatedAction(resolveDirectoryOrg(dirId), CreateGroupAttempt(dirId, "directory"))(parse.json)(
    withAuthorization(CREATE_GROUP, CreateGroupAttempt(dirId, "directory")) { event =>
      implicit request =>
        withBody[GestaltGroupCreate](event) { create =>
          val newGroup = DirectoryFactory.createGroupInDir(dirId = dirId, create)
          auditTry(newGroup,event){case (e,o) => e.copy(successful = true, newGroup = Some(o))}
          renderTry[GestaltGroup](Created)(newGroup)
        }
    }
  )

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

  def removeAccountEmail(accountId: UUID) = Action.async(parse.default) { request =>
    updateAccount(accountId).apply(new Request[JsValue]{
      override def body: JsValue = Json.arr(Json.toJson(PatchOp.Remove("/email")))
      override def id: Long = request.id
      override def tags: Map[String, String] = request.tags
      override def uri: String = request.uri
      override def path: String = request.path
      override def method: String = request.method
      override def version: String = request.version
      override def queryString: Map[String, Seq[String]] = request.queryString
      override def headers: Headers = request.headers
      override def remoteAddress: String = request.remoteAddress
      override def secure: Boolean = request.secure
      override def clientCertificateChain: Option[Seq[X509Certificate]] = request.clientCertificateChain
    })
  }

  def removeAccountPhoneNumber(accountId: UUID) = Action.async(parse.default) { request =>
    updateAccount(accountId).apply(new Request[JsValue]{
      override def body: JsValue = Json.arr(Json.toJson(PatchOp.Remove("/phoneNumber")))
      override def id: Long = request.id
      override def tags: Map[String, String] = request.tags
      override def uri: String = request.uri
      override def path: String = request.path
      override def method: String = request.method
      override def version: String = request.version
      override def queryString: Map[String, Seq[String]] = request.queryString
      override def headers: Headers = request.headers
      override def remoteAddress: String = request.remoteAddress
      override def secure: Boolean = request.secure
      override def clientCertificateChain: Option[Seq[X509Certificate]] = request.clientCertificateChain
    })
  }

  def updateAccount(accountId: UUID) = AuthenticatedAction(resolveAccountOrg(accountId), UpdateAccountAttempt(accountId))(parse.json) (
    // user can update their own account
    withAuthorization( {r => if (r.user.identity.id == accountId) None else Some(UPDATE_ACCOUNT)}, UpdateAccountAttempt(accountId) ) { event => implicit request =>
      withBody[Seq[PatchOp], GestaltAccountUpdate](event) { payload =>
        val attempt = AccountFactory.find(accountId) match {
          case Some(before) =>
            payload.fold(
              patches => AccountFactory.updateAccount(before, patches),
              update => AccountFactory.updateAccountSDK(before, update)
            ) map { after => (before,after) }
          case None =>
            Failure(ResourceNotFoundException(
              resource = request.path,
              message = "could not locate requested account",
              developerMessage = "Could not locate the requested account. Make sure to use the account ID and not the username."
            ))
        }
        auditTry(attempt, event){case (e,(before,after)) => e.copy(
          successful = true,
          accounts = Some((before,after))
        )}
        renderTry[GestaltAccount](Ok)(attempt.map(_._2))
      }
    }
  )

  def updateGroupMembership(groupId: java.util.UUID) = AuthenticatedAction(resolveGroupOrg(groupId), UpdateGroupMembershipAttempt(groupId))(parse.json) (
    withAuthorization(UPDATE_GROUP, UpdateGroupMembershipAttempt(groupId)) { event => implicit request =>
      withBody[Seq[PatchOp]](event) { payload =>
        val attempt = GroupFactory.find(groupId) match {
          case Some(_) =>
            val oldMembers = GroupFactory.listGroupAccounts(groupId) map { a => (a: GestaltAccount).getLink() }
            val newMembers = GroupFactory.updateGroupMembership(groupId, payload) map {
              _.map { a => (a: GestaltAccount).getLink() }
            }
            newMembers map ( l => (oldMembers,l) )
          case None => Failure(ResourceNotFoundException(
            resource = request.path,
            message = "could not locate requested group",
            developerMessage = "Could not locate the requested group. Make sure to use the group ID and not the group name."
          ))
        }
        auditTry(attempt, event){ case (e,t) => e.copy( successful = true, memberLists = Some(t) )}
        renderTry[Seq[ResourceLink]](Ok)(attempt.map(_._2))
      }
    }
  )

  def updateRightGrant(rightId: UUID) = AuthenticatedAction(resolveGrantOrg(rightId))(parse.json) { implicit request =>
    requireAuthorization(UPDATE_APP_GRANT)
    RightGrantRepository.find(rightId).fold(defaultResourceNotFound) { grant =>
        validateBody[Seq[PatchOp]] match {
          case Seq(patch) if patch.path == "/grantValue" =>
            (patch.op.toLowerCase,patch.value) match {
              case (update,Some(value)) if update == "add" || update == "replace" =>
                val newvalue = value.as[String]
                Ok(Json.toJson[GestaltRightGrant](grant.copy(grantValue = Some(newvalue))))
              case ("remove",None) => Ok(Json.toJson[GestaltRightGrant](
                grant.copy(grantValue = None).save()
              ))
              case _ => defaultBadPatch
            }
          case _ => defaultBadPatch
        }
    }
  }

  def updateAccountStoreMapping(mapId: UUID) = AuthenticatedAction(resolveMappingOrg(mapId), UpdateAccountStoreAttempt(mapId))(parse.json)(
    withAuthorization(UPDATE_ACCOUNT_STORE, UpdateAccountStoreAttempt(mapId)) { event => implicit request =>
      withBody[Seq[PatchOp]](event) { patch =>
        val attempt = accountStoreMappingService.find(mapId) match {
          case None =>
            Failure(ResourceNotFoundException(
              resource = request.path,
              message = "could not locate requested account store mapping",
              developerMessage = "Could not locate the requested account store mapping."
            ))
          case Some(original) =>
            accountStoreMappingService.updateMapping( original, patch ) map {updated => (original,updated) }
        }
        auditTry(attempt, event){case (e,(original,updated)) => e.copy(
          successful = true,
          asms = Some(original,updated)
        )}
        renderTry[GestaltAccountStoreMapping](Ok)(attempt map (_._2))
      }
    }
  )

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

  def deleteAccountStoreMapping(mapId: UUID) = AuthenticatedAction(resolveMappingOrg(mapId), DeleteAccountStoreAttempt(mapId))(parse.default) (
    withAuthorization(DELETE_ACCOUNT_STORE, DeleteAccountStoreAttempt(mapId)) { event => implicit request =>
      val attempt = accountStoreMappingService.find(mapId) match {
        case Some(asm) => Try { AccountStoreMappingRepository.destroy(asm) } map { _ => asm }
        case None =>
          Failure(ResourceNotFoundException(
            resource = request.path,
            message = "account store mapping does not exist",
            developerMessage = "Could not delete the target account store mapping because it does not exist or the provided credentials do not have rights to see it."
          ))
      }
      auditTry(attempt, event){case (e,t) => e.copy(successful = true, accountStore = Some(t))}
      renderTry[DeleteResult](Ok)(attempt map (_ => DeleteResult(true)))
    }
  )

  def deleteDirectory(dirId: UUID) = AuthenticatedAction(resolveDirectoryOrg(dirId), DeleteDirectoryAttempt(dirId))(parse.default) (
    withAuthorization(DELETE_DIRECTORY,DeleteDirectoryAttempt(dirId)) { event => implicit request =>
      val attempt = DirectoryFactory.find(dirId) match {
        case Some(dir) =>
          Try { DirectoryFactory.removeDirectory(dirId) } map { _ => dir }
        case None =>
          Failure(ResourceNotFoundException(
            resource = request.path,
            message = "directory does not exist",
            developerMessage = "Could not delete the target directory because it does not exist or the provided credentials do not have rights to see it."
          ))
      }
      auditTry(attempt, event){case (e,t) => e.copy(successful = true, directory = Some(t))}
      renderTry[DeleteResult](Ok)(attempt map (_ => DeleteResult(true)))
    }
  )

  def deleteOrgById(orgId: UUID) = AuthenticatedAction(Some(orgId), DeleteOrgAttempt(orgId))(parse.default) (
    withAuthorization(DELETE_ORG, DeleteOrgAttempt(orgId)) { event => implicit request =>
      val attempt = OrgFactory.findByOrgId(orgId) match {
        case Some(org) if org.parent.isDefined =>
          Try{OrgFactory.delete(org)} map {_ => org}
        case Some(org) if org.parent.isEmpty =>
          Failure(BadRequestException(
            resource = request.path,
            message = "cannot delete root org",
            developerMessage = "It is not permissible to delete the root organization. Check that you specified the intended org id."
          ))
        case None =>
          Failure(ResourceNotFoundException(
            resource = request.path,
            message = "org does not exist",
            developerMessage = "Could not delete the target org because it does not exist or the provided credentials do not have rights to see it."
          ))
      }
      auditTry(attempt, event){case (e,t) => e.copy(successful = true, org = Some(t))}
      renderTry[DeleteResult](Ok)(attempt map (_ => DeleteResult(true)))
    }
  )

  def deleteAppById(appId: UUID) = AuthenticatedAction(resolveAppOrg(appId), DeleteAppAttempt(appId))(parse.default) (
    withAuthorization(DELETE_APP, DeleteAppAttempt(appId)) { event => implicit request =>
      val attempt = AppFactory.findByAppId(appId) match {
        case Some(app) if !app.isServiceApp =>
          Try { AppFactory.delete(app) } map { _ => app }
        case Some(app) if app.isServiceApp =>
          Failure(BadRequestException(
            resource = request.path,
            message = "cannot delete service app",
            developerMessage = "It is not permissible to delete the current service app for an organization. Verify that this is the app that you want to delete and select a new service app for the organization and try again, or delete the organization."
          ))
        case None =>
          Failure(ResourceNotFoundException(
            resource = request.path,
            message = "app does not exist",
            developerMessage = "Could not delete the target app because it does not exist or the provided credentials do not have rights to see it."
          ))
      }
      auditTry(attempt, event){case (e,t) => e.copy(successful = true, app = Some(t))}
      renderTry[DeleteResult](Ok)(attempt map (_ => DeleteResult(true)))
    }
  )

  def deleteGroup(groupId: java.util.UUID) = AuthenticatedAction(resolveGroupOrg(groupId), DeleteGroupAttempt(groupId))(parse.default) (
    withAuthorization(DELETE_GROUP, DeleteGroupAttempt(groupId)) { event => implicit request =>
      val attempt = GroupFactory.find(groupId) match {
        case Some(group) =>
          DirectoryFactory.find(group.dirId.asInstanceOf[UUID]) match {
            case None => Failure(UnknownAPIException(
              code = 500,
              resource = request.path,
              message = "could not locate directory associated with group",
              developerMessage = "Could not locate the directory associated with the specified group. Please contact support."
            ))
            case Some(dir) =>
              val deleted = dir.deleteGroup(group.id.asInstanceOf[UUID])
              Success(group)
          }
        case None => Failure(ResourceNotFoundException(
          resource = request.path,
          message = "group does not exist",
          developerMessage = "Could not delete the target app because it does not exist or the provided credentials do not have rights to see it."
        ))
      }
      auditTry(attempt, event){case (e,t) => e.copy(successful = true, group = Some(t))}
      renderTry[DeleteResult](Ok)(attempt map {_ => DeleteResult(true)})
    }
  )

  def hardDeleteAccount(accountId: java.util.UUID) = AuthenticatedAction(resolveAccountOrg(accountId), DeleteAccountAttempt(accountId))(parse.default) (
    withAuthorization(DELETE_ACCOUNT, DeleteAccountAttempt(accountId)) { event => implicit request =>
      val attempt = AccountFactory.find(accountId) match {
        case None =>
          Success(false, None)
        case Some(account) if account.id != request.user.identity.id =>
          Try {
            account.destroy()
          } map (_ => (true, Some(account)))
        case Some(account) if account.id == request.user.identity.id =>
          Failure(BadRequestException(
            resource = request.path,
            message = "cannot delete self",
            developerMessage = "The authenticated account is the same as the account targeted by the delete operation. You cannot delete yourself. Get someone else to delete you."
          ))
      }
      auditTry(attempt, event){case (e,(_,t)) => e.copy(successful = true, account = t.map(a => a: GestaltAccount))}
      renderTry[DeleteResult](Ok)(attempt map {case (wasDeleted,_) => DeleteResult(wasDeleted)})
    }
  )

  def deleteOrgAccountRight(orgId: UUID, accountId: UUID, grantName: String) = AuthenticatedAction(Some(orgId)) { implicit request =>
    requireAuthorization(DELETE_ORG_GRANT)
    val wasDeleted = AccountFactory.deleteAppAccountGrant(request.user.serviceAppId, accountId, grantName)
    Ok(Json.toJson(DeleteResult(wasDeleted)))
  }

  def enableAccount(accountId: java.util.UUID) = AuthenticatedAction(resolveAccountOrg(accountId)).async { implicit request =>
    requireAuthorization(UPDATE_ACCOUNT)
    AccountFactory.find(accountId) match {
      case Some(account) if account.id != request.user.identity.id =>
        Future {
          DirectoryFactory.find(account.dirId.asInstanceOf[UUID]) match {
            case None => throw UnknownAPIException(
              code = 500,
              resource = request.path,
              message = "could not locate directory associated with account",
              developerMessage = "Could not locate the directory associated with the specified account. Please contact support."
            )
            case Some(dir) =>
              dir.disableAccount(account.id.asInstanceOf[UUID], disabled = false)
              Ok(Json.obj(
                "enabled" -> true
              ))
          }
        }
      case Some(account) if account.id == request.user.identity.id => Future {
        BadRequest(Json.toJson(BadRequestException(
          resource = request.path,
          message = "cannot enable self",
          developerMessage = "The authenticated account is the same as the account targeted by the enable operation. You cannot enable yourself. Get someone else to enable you."
        )))
      }
      case None => Future {
        NotFound(Json.toJson(ResourceNotFoundException(
          resource = request.path,
          message = "account does not exist",
          developerMessage = "Could not enable the target account because it does not exist or the provided credentials do not have rights to see it."
        )))
      }
    }
  }

  def disableAccount(accountId: UUID) = AuthenticatedAction(resolveAccountOrg(accountId)).async { implicit request =>
    requireAuthorization(UPDATE_ACCOUNT)
    AccountFactory.find(accountId) match {
      case Some(account) if account.id != request.user.identity.id =>
        Future {
          DirectoryFactory.find(account.dirId.asInstanceOf[UUID]) match {
            case None => throw UnknownAPIException(
              code = 500,
              resource = request.path,
              message = "could not locate directory associated with account",
              developerMessage = "Could not locate the directory associated with the specified account. Please contact support."
            )
            case Some(dir) =>
              dir.disableAccount(account.id.asInstanceOf[UUID])
              Ok(Json.obj(
                "disabled" -> true
              ))
          }
        }
      case Some(account) if account.id == request.user.identity.id => Future {
        BadRequest(Json.toJson(BadRequestException(
          resource = request.path,
          message = "cannot disable self",
          developerMessage = "The authenticated account is the same as the account targeted by the disable operation. You cannot disable yourself. Get someone else to disable you."
        )))
      }
      case None => Future {
        NotFound(Json.toJson(ResourceNotFoundException(
          resource = request.path,
          message = "account does not exist",
          developerMessage = "Could not disable the target account because it does not exist or the provided credentials do not have rights to see it."
        )))
      }
    }
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

  def deleteRightGrant(rightId: UUID) = AuthenticatedAction(resolveGrantOrg(rightId)) { implicit request =>
    requireAuthorization(DELETE_APP_GRANT)
    Ok(Json.toJson(DeleteResult(RightGrantFactory.deleteRightGrant(rightId))))
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
        fRateCheck map {
          case RequestAccepted => renderTry[AccessTokenResponse](Ok){
            val v = for {
              account <- o2t(AccountFactory.authenticate(serviceAppId, creds))(OAuthError(INVALID_GRANT, "The provided authorization grant is invalid, expired or revoked."))
              newToken <- TokenFactory.createToken(
                orgId = Some(orgId),
                accountId = account.id.asInstanceOf[UUID],
                validForSeconds = defaultTokenExpiration,
                tokenType = ACCESS_TOKEN,
                parentApiKey = None
              )
            } yield (account, AccessTokenResponse(accessToken = newToken, refreshToken = None, tokenType = BEARER, expiresIn = defaultTokenExpiration, gestalt_access_token_href = ""))
            v match {
              case Success((acc,tr)) =>
                auditer(TokenIssueAttempt.success(acc, creds.username))
              case Failure(ex) =>
                auditer(TokenIssueAttempt.failed(creds.username))
            }
            v.map(_._2)
          }
          case _ =>
            auditer(TokenIssueAttempt.failed("rate limit exceeded"))
            oAuthErr(INVALID_GRANT, "Rate limit exceeded")
        }
      case Failure(ex) => Future.successful(handleError(request, ex))
    }
  }

  private def clientCredGrantFlow(orgIdGen: APICredentialRepository => Option[UUID])
                                 (implicit request: Request[Map[String,Seq[String]]]): Future[Result] = Future {
    GestaltHeaderAuthentication.authenticateHeader(request) match {
      case None =>
        auditer(TokenIssueAttempt.failed(GestaltHeaderAuthentication.presentedIdentity(request).getOrElse("invalid token")))
        oAuthErr(INVALID_CLIENT, "client_credential grant requires client authentication")
      case Some(auth) => auth.credential match {
        case Right(_) =>
          auditer(TokenIssueAttempt.failed(auth.identity, "valid token"))
          oAuthErr(INVALID_CLIENT, "client_credential grant requires client is authenticated using API credentials and does not support token authentication")
        case Left(apiKey) =>
          orgIdGen(apiKey) match {
            case None =>
              auditer(TokenIssueAttempt.failed(auth.identity, apiKey.apiKey.toString))
              oAuthErr(INVALID_GRANT, "the authenticated client does not belong to the specified organization or the organization does not exist")
            case Some(orgId) =>
              auditer(TokenIssueAttempt.success(auth.identity, apiKey.apiKey.toString))
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
      case Some("client_credentials") =>
        clientCredGrantFlow(getIssuedOrgFromAPIKey)
      case Some(_) =>
        auditer(TokenIssueAttempt.failed("invalid grant_type"))
        Future{oAuthErr(UNSUPPORTED_GRANT_TYPE, "global token issue endpoint only support client_credentials grant")}
      case None =>
        auditer(TokenIssueAttempt.failed("missing grant_type"))
        Future{oAuthErr(INVALID_REQUEST, "request must contain a single grant_type field")}
    }
  }

  def orgTokenIssue(orgId: UUID) = Action.async(parse.urlFormEncoded) { implicit request =>
    def verifyKeyOwnerBelongsToOrg = (apiKey: APICredentialRepository) => for {
        app <- AppFactory.findServiceAppForOrg(orgId)
        if AccountFactory.getAppAccount(app.id.asInstanceOf[UUID], apiKey.accountId.asInstanceOf[UUID]).isDefined
    } yield orgId

    request.body.get("grant_type") flatMap asSingleton match {
      case Some("client_credentials") =>
        clientCredGrantFlow(verifyKeyOwnerBelongsToOrg)
      case Some("password") =>
        passwordGrantFlow(Some(orgId))
      case Some(_) =>
        auditer(TokenIssueAttempt.failed("invalid grant type"))
        Future{oAuthErr(UNSUPPORTED_GRANT_TYPE, "org token issue endpoints only support client_credentials and password grants")}
      case None =>
        auditer(TokenIssueAttempt.failed("missing grant_type"))
        Future{oAuthErr(INVALID_REQUEST, "request must contain a single grant_type field")}
    }
  }

  private[this] def logExtraData(requestId: Long, body: Map[String, Seq[String]]): Unit = {
    val gsExtraData = Some(body.collect({
      case (h, v :: vtail) if h.startsWith("gestalt-security") => (h,v)
    }).toSeq).filter(_.nonEmpty).map(_.mkString(",")).getOrElse("<empty>")
    if (gsExtraData.nonEmpty) log.debug(s"req-${requestId}: extra data: ${gsExtraData}")
  }

  private def genericTokenIntro(getOrgId: TokenRepository => Option[UUID])
                               (implicit request: Request[Map[String,Seq[String]]]): Result = {
    GestaltHeaderAuthentication.authenticateHeader(request).fold {
      auditer(TokenIntrospectionAttempt(None, false, None, None))
      oAuthErr(INVALID_CLIENT,"token introspection requires client authentication")
    } { auth =>
      val event = TokenIntrospectionAttempt(Some(auth.identity), false, None, None)
      val introspection = for {
        tokenStr <- {
          logExtraData(request.id, request.body)
          o2t(request.body.get("token") flatMap asSingleton)(OAuthError(INVALID_REQUEST,"Invalid content in one of required fields: `token`"))
        }
        tokenAndAccount = for {
          token <- TokenFactory.findValidToken(tokenStr)
          orgId <- getOrgId(token)
          serviceApp <- AppFactory.findServiceAppForOrg(orgId)
          serviceAppId = serviceApp.id.asInstanceOf[UUID]
          account <- AccountFactory.getAppAccount(serviceAppId, token.accountId.asInstanceOf[UUID])
        } yield (token,account,serviceAppId,orgId)
        intro = tokenAndAccount.fold[TokenIntrospectionResponse](INVALID_TOKEN){
          case (token,orgAccount,serviceAppId,orgId) =>
            val extraData = (request.body - "token" - "token_type_hint").collect({case (k,vs) if vs.nonEmpty => (k -> vs.head)})
            ValidTokenResponse(
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
              gestalt_rights = RightGrantFactory.listAccountRights(serviceAppId, orgAccount.id.asInstanceOf[UUID]) map { r => r: GestaltRightGrant },
              extra_data = Some(extraData)
            )
        }
        acct = tokenAndAccount.map(_._2.id.asInstanceOf[UUID])
      } yield (intro,acct)
      auditTry(introspection, event){case (e,(intro,tokAcctId)) => e.copy(
        successful = true,
        tokenActive = Some(intro.active),
        tokenAccountId = tokAcctId
      )}
      renderTry[TokenIntrospectionResponse](Ok)(introspection.map(_._1))
    }
  }

  def orgTokenInspect(orgId: UUID) = Action(parse.urlFormEncoded) { implicit request =>
    genericTokenIntro(_ => Some(orgId))
  }

  def globalTokenInspect() = Action(parse.urlFormEncoded) { implicit request =>
    genericTokenIntro( token => token.issuedOrgId map {_.asInstanceOf[UUID]} )
  }

  def deleteToken(tokenId: UUID) = AuthenticatedAction(resolveTokenOrg(tokenId), DeleteTokenAttempt(tokenId)) { implicit request =>
    val token = TokenFactory.findValidById(tokenId)
    if (! token.exists {_.accountId == request.user.identity.id}) requireAuthorization(DELETE_TOKEN)
    token match {
      case Some(t) =>
        auditer(DeleteTokenAttempt(tokenId, u = Some(request.user.identity), true))
        Logger.info(s"deleting token ${t.id}")
        TokenRepository.destroy(t)
      case None =>
        auditer(DeleteTokenAttempt(tokenId, u = Some(request.user.identity), false))
        Logger.info(s"not deleting non-existent token ${tokenId}")
    }
    Ok(Json.toJson(DeleteResult(token.isDefined)))
  }

  def deleteApiKey(apiKey: UUID) = AuthenticatedAction(resolveApiKeyOrg(apiKey), DeleteAPIKeyAttempt(apiKey)) { implicit request =>
    val key = APICredentialFactory.findByAPIKey(apiKey.toString)
    if (! key.exists {_.accountId == request.user.identity.id}) requireAuthorization(DELETE_APIKEY)
    key match {
      case Some(k) =>
        auditer(DeleteAPIKeyAttempt(apiKey, u = Some(request.user.identity), true))
        Logger.info(s"deleting apiKey ${k.id}")
        APICredentialRepository.destroy(k)
      case None =>
        auditer(DeleteAPIKeyAttempt(apiKey, u = Some(request.user.identity), false))
        Logger.info(s"not deleting non-existent apiKey ${apiKey}")
    }
    Ok(Json.toJson(DeleteResult(key.isDefined)))
  }

  def generateAPIKey(accountId: UUID) = AuthenticatedAction(resolveAccountOrg(accountId), GenerateAPIKeyAttempt(accountId)) { implicit request =>
    val event = GenerateAPIKeyAttempt(accountId, u = Some(request.user.identity))
    if ( request.user.identity.id != accountId ) requireAuthorization(CREATE_APIKEY)
    val explicitOrgId = request.body.asJson flatMap (b => (b \ "orgId").asOpt[UUID])
    // try hard to get an org to bind api key to
    lazy val currentOrg = if (request.user.identity.id == accountId) Some(request.user.orgId) else None
    val maybeBoundOrgId = explicitOrgId orElse currentOrg
    // only generate if using api key authentication
    val apiKey = request.user.credential.left.toOption map {_.apiKey.asInstanceOf[UUID]}

    val attempt = (apiKey,maybeBoundOrgId) match {
      case (None,_) => Failure(BadRequestException(
        resource = "",
        message = "API key creation requires API key authentication",
        developerMessage = "API key creation request must be authenticated using API keys and does not support bearer token authentication"
      ))
      case (Some(_),None) => Failure(BadRequestException(
        resource = "",
        message = "delegated API key creation requires orgId",
        developerMessage = "API key creation on behalf of another account requires an orgId against which to bind the API key."
      ))
      case (Some(key),Some(boundOrgId)) =>
        APICredentialFactory.createAPIKey(
          accountId = accountId,
          boundOrg = Some(boundOrgId),
          parentApiKey = Some(key)
        )
    }
    auditTry(attempt, event){case (e,t) => e.copy(successful = true, newApiKey = Some(t.apiKey.asInstanceOf[UUID]))}
    renderTry[GestaltAPIKey](Created)(attempt)
  }

  def options(path: String) = Action {Ok("")}
}
