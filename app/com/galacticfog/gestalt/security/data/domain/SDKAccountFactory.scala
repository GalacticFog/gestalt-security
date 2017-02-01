package com.galacticfog.gestalt.security.data.domain
import java.util.UUID

import com.galacticfog.gestalt.patch.PatchOp
import com.galacticfog.gestalt.security.api.errors.{ResourceNotFoundException, SecurityRESTException}
import com.galacticfog.gestalt.security.api.{GestaltAccount, GestaltAccountCreate, GestaltAccountUpdate, GestaltBasicCredsToken}
import com.galacticfog.gestalt.security.data.APIConversions
import com.galacticfog.gestalt.security.data.model.UserAccountRepository
import com.galacticfog.gestalt.security.plugins.AccountFactoryDelegate

import scala.util.{Failure, Success, Try}

object SDKAccountFactory extends AccountFactoryDelegate {

	def instance = this


	override def createAccount(dirId: UUID, username: String, description: Option[String], email: Option[String], phoneNumber: Option[String],
	                           firstName: String, lastName: String, hashMethod: String, salt: String, secret: String, disabled: Boolean) = {
		AccountFactory.createAccount(dirId, username, description, email, phoneNumber,firstName, lastName, hashMethod, salt, secret, disabled).map { APIConversions.accountModelToApi }
	}

	override def disableAccount(accountId: UUID, disabled: Boolean): Unit = AccountFactory.disableAccount(accountId, disabled)

	override def find(accountId: UUID): Option[GestaltAccount] = AccountFactory.find(accountId).map { APIConversions.accountModelToApi }

	override def findEnabled(accountId: UUID): Option[GestaltAccount] = AccountFactory.findEnabled(accountId).map { APIConversions.accountModelToApi }

	override def delete(accountId: UUID) = AccountFactory.find(accountId) match {
		case Some(uar) => uar.destroy()
			Success( APIConversions.accountModelToApi(uar))
		case None => throw ResourceNotFoundException(resource = accountId.toString, message = "Error deleting account", developerMessage = "Error deleting account, not found")
	}

	override def listByDirectoryId(dirId: UUID): List[GestaltAccount] = AccountFactory.listByDirectoryId(dirId).map { APIConversions.accountModelToApi }

	override def updateAccount(account: GestaltAccount, patches: Seq[PatchOp]): Try[GestaltAccount] = {
		UserAccountRepository.find(account.id) match {
			case Some(uar) => AccountFactory.updateAccount(uar, patches).map { APIConversions.accountModelToApi }
			case None => throw ResourceNotFoundException(resource = account.id.toString, message = "Error finding account for update", developerMessage = "Error finding repository account for update")

		}
	}

	override def updateAccountSDK(account: GestaltAccount, update: GestaltAccountUpdate): Try[GestaltAccount] = {
		UserAccountRepository.find(account.id) match {
			case Some(uar) => AccountFactory.updateAccountSDK(uar, update).map { APIConversions.accountModelToApi }
			case None => throw ResourceNotFoundException(resource = account.id.toString, message = "Error finding account for update", developerMessage = "Error finding repository account for update")
		}
	}

	override def lookupByAppId(appId: UUID, nameQuery: Option[String], emailQuery: Option[String], phoneQuery: Option[String]): Seq[GestaltAccount] = {
		AccountFactory.lookupByAppId(appId, nameQuery, emailQuery, phoneQuery)  //.map { APIConversions.accountModelToApi(_) }
	}

	override def authenticate(appId: UUID, creds: GestaltBasicCredsToken): Option[GestaltAccount] = AccountFactory.authenticate(appId, creds)

	override def getAppAccount(appId: UUID, accountId: UUID): Option[GestaltAccount] = AccountFactory.getAppAccount(appId, accountId).map { APIConversions.accountModelToApi }

	override def listEnabledAppUsers(appId: UUID): List[GestaltAccount] = AccountFactory.listEnabledAppUsers(appId).map { APIConversions.accountModelToApi }

	override def queryShadowedDirectoryAccounts(dirId: Option[UUID], nameQuery: Option[String], phoneQuery: Option[String], emailQuery: Option[String]): List[GestaltAccount] = {
		AccountFactory.queryShadowedDirectoryAccounts(dirId, nameQuery, emailQuery, phoneQuery).map { APIConversions.accountModelToApi }
	}

	override def findInDirectoryByName(dirId: UUID, username: String): Option[GestaltAccount] = AccountFactory.findInDirectoryByName(dirId, username).map { APIConversions.accountModelToApi }

	override def checkPassword(account: GestaltAccount, plaintext: String): Boolean = false

	override def saveAccount(account: GestaltAccountCreate): Try[GestaltAccount] = {
		throw ResourceNotFoundException(resource = "", message = "Action not permitted", developerMessage = "Security adapter not allowed to save account")
	}

}
