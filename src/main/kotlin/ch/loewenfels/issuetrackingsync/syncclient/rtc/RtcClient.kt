package ch.loewenfels.issuetrackingsync.syncclient.rtc

import ch.loewenfels.issuetrackingsync.*
import ch.loewenfels.issuetrackingsync.syncclient.IssueClientException
import ch.loewenfels.issuetrackingsync.syncclient.IssueTrackingClient
import ch.loewenfels.issuetrackingsync.syncconfig.DefaultsForNewIssue
import ch.loewenfels.issuetrackingsync.syncconfig.IssueTrackingApplication
import com.fasterxml.jackson.databind.JsonNode
import com.ibm.team.process.client.IProcessClientService
import com.ibm.team.process.common.IProjectArea
import com.ibm.team.repository.client.ITeamRepository
import com.ibm.team.repository.client.TeamPlatform
import com.ibm.team.workitem.client.IAuditableClient
import com.ibm.team.workitem.client.IWorkItemClient
import com.ibm.team.workitem.common.IAuditableCommon
import com.ibm.team.workitem.common.expression.*
import com.ibm.team.workitem.common.model.*
import com.ibm.team.workitem.common.query.IQueryResult
import com.ibm.team.workitem.common.query.IResolvedResult
import org.eclipse.core.runtime.NullProgressMonitor
import org.springframework.beans.BeanWrapperImpl
import java.net.URI
import java.sql.Timestamp
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.*

class RtcClient(private val setup: IssueTrackingApplication) : IssueTrackingClient<IWorkItem>, Logging {
    private val progressMonitor = NullProgressMonitor()
    private val teamRepository: ITeamRepository
    private val workItemClient: IWorkItemClient
    private val projectArea: IProjectArea

    init {
        teamRepository = TeamPlatform.getTeamRepositoryService().getTeamRepository(setup.endpoint)
        teamRepository.registerLoginHandler(LoginHandler())
        teamRepository.login(NullProgressMonitor())
        workItemClient = teamRepository.getClientLibrary(IWorkItemClient::class.java) as IWorkItemClient
        val processClient = teamRepository.getClientLibrary(IProcessClientService::class.java) as IProcessClientService
        val uri = URI.create(
            setup.project?.replace(" ", "%20") ?: throw IllegalStateException(
                "Need project for RTC client"
            )
        )
        projectArea = processClient.findProcessArea(uri, null, null) as IProjectArea?
            ?: throw IllegalStateException("Project area ${setup.project} is invalid")
    }

    override fun getIssue(key: String): Issue? {
        return getRtcIssue(key)?.let {
            toSyncIssue(it)
        }
    }

    override fun getIssueFromWebhookBody(body: JsonNode): Issue =
        throw UnsupportedOperationException("RTC does not support webhooks")

    override fun getProprietaryIssue(issueKey: String): IWorkItem? {
        return getRtcIssue(issueKey)
    }

    override fun getProprietaryIssue(fieldName: String, fieldValue: String): IWorkItem? {
        val queryClient = workItemClient.queryClient
        val attrExpression =
            AttributeExpression(
                getQueryableAttribute(fieldName),
                AttributeOperation.EQUALS,
                fieldValue
            )
        val resolvedResultOfWorkItems =
            queryClient.getResolvedExpressionResults(projectArea, attrExpression, IWorkItem.FULL_PROFILE)
        val workItems = toWorkItems(resolvedResultOfWorkItems)
        return when (workItems.size) {
            0 -> null
            1 -> workItems[0]
            else -> throw IssueClientException("Query too broad, multiple issues found for $fieldValue")
        }
    }

    private fun getRtcIssue(key: String): IWorkItem? {
        return workItemClient.findWorkItemById(Integer.parseInt(key), IWorkItem.SMALL_PROFILE, progressMonitor)
    }

    override fun getLastUpdated(internalIssue: IWorkItem): LocalDateTime =
        LocalDateTime.ofInstant(internalIssue.modified().toInstant(), ZoneId.systemDefault())

    override fun getValue(internalIssue: IWorkItem, fieldName: String): Any? {
        val beanWrapper = BeanWrapperImpl(internalIssue)
        var internalValue = if (beanWrapper.isReadableProperty(fieldName))
            beanWrapper.getPropertyValue(fieldName)
        else
            null
        return internalValue?.let { convertFromMetadataId(fieldName, it) }
    }

    override fun setValue(internalIssueBuilder: Any, fieldName: String, value: Any?) {
        convertToMetadataId(fieldName, value)?.let {
            val workItem = internalIssueBuilder as IWorkItem
            val attribute = getAttribute(fieldName)
            workItem.setValue(attribute, value);
        }
    }

    private fun convertToMetadataId(fieldName: String, value: Any?): Any? {
        return when (fieldName) {
            "priority" -> RtcMetadata.getPriorityId(
                value?.toString() ?: "",
                getAttribute(IWorkItem.PRIORITY_PROPERTY),
                workItemClient
            )
            "severity" -> RtcMetadata.getSeverityId(
                value?.toString() ?: "",
                getAttribute(IWorkItem.SEVERITY_PROPERTY),
                workItemClient
            )
            else -> value
        }
    }

    private fun convertFromMetadataId(fieldName: String, value: Any): Any {
        return when (fieldName) {
            "priority" -> RtcMetadata.getPriorityName(
                value.toString(),
                getAttribute(IWorkItem.PRIORITY_PROPERTY),
                workItemClient
            )
            "severity" -> RtcMetadata.getSeverity(
                value.toString(),
                getAttribute(IWorkItem.SEVERITY_PROPERTY),
                workItemClient
            )
            else -> value
        }
    }

    override fun createOrUpdateTargetIssue(
        issue: Issue,
        defaultsForNewIssue: DefaultsForNewIssue?
    ) {
        val targetKeyFieldname = issue.keyFieldMapping!!.getTargetFieldname()
        val targetIssueKey = issue.keyFieldMapping!!.getKeyForTargetIssue().toString()
        val targetIssue =
            if (targetIssueKey.isNotEmpty()) getProprietaryIssue(targetKeyFieldname, targetIssueKey) else null
        if (targetIssue != null) {
            updateTargetIssue(targetIssue, issue)
        } else if (defaultsForNewIssue != null) {
            createTargetIssue(defaultsForNewIssue, issue)
        } else {
            throw SynchronizationAbortedException("No target issue found for $targetIssueKey, and no defaults for creating issue were provided")
        }
    }

    private fun createTargetIssue(defaultsForNewIssue: DefaultsForNewIssue, issue: Issue) {
        val workItemType: IWorkItemType =
            workItemClient.findWorkItemType(projectArea, defaultsForNewIssue.issueType, progressMonitor)
        val path = defaultsForNewIssue.category.split("/")
        val category: ICategoryHandle = workItemClient.findCategoryByNamePath(projectArea, path, progressMonitor)
        val operation = WorkItemInitialization("", category)
        val handle: IWorkItemHandle = operation.run(workItemType, progressMonitor)
        operation.workItem?.let { mapNewIssueValues(it, issue) }
        val auditableClient: IAuditableClient =
            teamRepository.getClientLibrary(IAuditableClient::class.java) as IAuditableClient
        val workItem: IWorkItem = auditableClient.resolveAuditable(handle, IWorkItem.FULL_PROFILE, progressMonitor)
        logger().info("Created new RTC issue ${workItem.id}")
        // TODO: update collections such as comments and attachments
    }

    private fun updateTargetIssue(targetIssue: IWorkItem, issue: Issue) {
        val copyManager = workItemClient.workItemWorkingCopyManager
        copyManager.connect(targetIssue, IWorkItem.FULL_PROFILE, progressMonitor)
        try {
            val workingCopy = copyManager.getWorkingCopy(targetIssue)
            val changeableWorkingItem = workingCopy.workItem
            mapNewIssueValues(changeableWorkingItem, issue)
            logger().info("Updating RTC issue ${targetIssue.id}")
            val detailedStatus = workingCopy.save(null)
            if (!detailedStatus.isOK) {
                throw  RuntimeException("Error saving work item", detailedStatus.getException())
            }
        } finally {
            copyManager.disconnect(targetIssue)
        }
    }

    private fun mapNewIssueValues(targetIssue: IWorkItem, issue: Issue) {
        issue.fieldMappings.forEach {
            it.setTargetValue(targetIssue, this)
        }
        // TODO: map collections such as comments and attachments
//        val comments = changeableWorkingItem.getComments();
//        val newComment = comments.createComment(
//            teamRepository.loggedInContributor(),
//            XMLString.createFromXMLText("<a href='" + extRefUrl + "'>" + extRefId + "</>")
//        );
//        comments.append(newComment);
    }

    override fun changedIssuesSince(lastPollingTimestamp: LocalDateTime): Collection<Issue> {
        val queryClient = workItemClient.queryClient
        val searchTerms = buildSearchTermForChangedIssues(lastPollingTimestamp)
        val resolvedResultOfWorkItems =
            queryClient.getResolvedExpressionResults(projectArea, searchTerms, IWorkItem.FULL_PROFILE)
        return toWorkItems(resolvedResultOfWorkItems).map { toSyncIssue(it) }
    }

    private fun buildSearchTermForChangedIssues(lastPollingTimestamp: LocalDateTime): Term {
        val modifiedRecently =
            AttributeExpression(
                getQueryableAttribute(IWorkItem.MODIFIED_PROPERTY),
                AttributeOperation.GREATER_OR_EQUALS,
                Timestamp.valueOf(lastPollingTimestamp)
            )
        val createdRecently =
            AttributeExpression(
                getQueryableAttribute(IWorkItem.CREATION_DATE_PROPERTY),
                AttributeOperation.GREATER_OR_EQUALS,
                Timestamp.valueOf(lastPollingTimestamp)
            )
        val projectAreaExpression = AttributeExpression(
            getQueryableAttribute(IWorkItem.PROJECT_AREA_PROPERTY),
            AttributeOperation.EQUALS,
            projectArea
        )
        val relevantIssuesTerm = Term(Term.Operator.OR)
        relevantIssuesTerm.add(modifiedRecently)
        relevantIssuesTerm.add(createdRecently)
        //
        val searchTerm = Term(Term.Operator.AND)
        searchTerm.add(relevantIssuesTerm)
        searchTerm.add(projectAreaExpression)
        return searchTerm
    }

    private fun getQueryableAttribute(attributeName: String): IQueryableAttribute {
        val auditableCommon: IAuditableCommon =
            teamRepository.getClientLibrary(IAuditableCommon::class.java) as IAuditableCommon
        return QueryableAttributes.getFactory(IWorkItem.ITEM_TYPE).findAttribute(
            projectArea,
            attributeName,
            auditableCommon,
            null
        )
    }

    private fun getAttribute(attributeName: String): IAttribute =
        workItemClient.findAttribute(
            projectArea,
            attributeName,
            progressMonitor
        )

    private fun toWorkItems(resolvedResults: IQueryResult<IResolvedResult<IWorkItem>>): List<IWorkItem> {
        val result = LinkedList<IWorkItem>()
        while (resolvedResults.hasNext(progressMonitor)) {
            result.add(resolvedResults.next(progressMonitor).getItem())
        }
        return result
    }

    private fun toSyncIssue(workItem: IWorkItem): Issue {
        return Issue(
            workItem.id.toString(),
            setup.name,
            getLastUpdated(workItem)
        )
    }

    inner class LoginHandler : ITeamRepository.ILoginHandler, ITeamRepository.ILoginHandler.ILoginInfo {
        override fun getUserId(): String {
            return setup.username
        }

        override fun getPassword(): String {
            return setup.password
        }

        override fun challenge(repository: ITeamRepository?): ITeamRepository.ILoginHandler.ILoginInfo {
            return this
        }
    }
}


