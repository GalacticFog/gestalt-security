package com.galacticfog.gestalt.security.data.domain

import java.util.UUID

import com.galacticfog.gestalt.patch.PatchOp
import com.galacticfog.gestalt.security.api.{GestaltAccount, GestaltGroup}
import com.galacticfog.gestalt.security.data.model.GroupMembershipRepository
import com.galacticfog.gestalt.security.data.APIConversions
import com.galacticfog.gestalt.security.plugins.{GroupFactoryDelegate, GroupMembership}

object SDKGroupFactory extends GroupFactoryDelegate {

	def instance = this

	override def find(groupId: UUID) = GroupFactory.find(groupId).map { APIConversions.groupModelToApi(_) }

	override def updateGroupMembership(groupId: UUID, payload: Seq[PatchOp]) = GroupFactory.updateGroupMembership(groupId, payload).map { _.map { a => APIConversions.accountModelToApi(a) } }

	override def queryShadowedDirectoryGroups(id: Option[UUID], groupName: Option[String]) = GroupFactory.queryShadowedDirectoryGroups(id, groupName).map { APIConversions.groupModelToApi(_) }

	override def listByDirectoryId(dirId: UUID) = GroupFactory.listByDirectoryId(dirId).map { APIConversions.groupModelToApi(_) }

	override def addAccountToGroup(groupId: UUID, accountId: UUID) = GroupFactory.addAccountToGroup(groupId, accountId).map { a => GroupMembership(a.accountId.asInstanceOf[UUID], a.groupId.asInstanceOf[UUID]) }

	override def queryShadowedAppGroups(appId: UUID, nameQuery: Option[String]) = GroupFactory.queryShadowedAppGroups(appId, nameQuery).map { APIConversions.groupModelToApi(_) }

	override def findInDirectoryByName(dirId: UUID, groupName: String) = GroupFactory.findInDirectoryByName(dirId, groupName).map { APIConversions.groupModelToApi(_) }

	override def lookupAppGroups(appId: UUID, nameQuery: String) = GroupFactory.lookupAppGroups(appId, nameQuery)

	override def listAccountGroups(accountId: UUID) = GroupFactory.listAccountGroups(accountId).map { APIConversions.groupModelToApi(_) }

	override def findGroupMemberships(accountId: UUID, groupId: UUID) = GroupMembershipRepository.find(accountId, groupId).map { APIConversions.groupMembershipModelToPlugin(_) }

	override def listGroupAccounts(groupId: UUID) = GroupFactory.listGroupAccounts(groupId).map { APIConversions.accountModelToApi(_) }

	override def delete(groupId: UUID) = GroupFactory.delete(groupId)

	override def removeAccountFromGroup(groupId: UUID, accountId: UUID) = GroupFactory.removeAccountFromGroup(groupId, accountId)

	override def getAppGroupMapping(appId: UUID, groupId: UUID) = GroupFactory.getAppGroupMapping(appId, groupId).map { APIConversions.groupModelToApi(_) }

	override def create(name: String, description: Option[String], dirId: UUID, maybeParentOrg: Option[UUID]) = GroupFactory.create(name, description, dirId, maybeParentOrg).map { APIConversions.groupModelToApi(_) }

}
