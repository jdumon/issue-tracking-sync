package ch.loewenfels.issuetrackingsync.scheduling

import ch.loewenfels.issuetrackingsync.Issue
import ch.loewenfels.issuetrackingsync.Logging
import ch.loewenfels.issuetrackingsync.app.AppState
import ch.loewenfels.issuetrackingsync.app.SyncApplicationProperties
import ch.loewenfels.issuetrackingsync.executor.SynchronizationFlowFactory
import ch.loewenfels.issuetrackingsync.logger
import ch.loewenfels.issuetrackingsync.syncclient.ClientFactory
import ch.loewenfels.issuetrackingsync.syncclient.IssueTrackingClient
import ch.loewenfels.issuetrackingsync.syncconfig.IssueTrackingApplication
import ch.loewenfels.issuetrackingsync.syncconfig.Settings
import com.fasterxml.jackson.databind.ObjectMapper
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.time.LocalDateTime
import javax.annotation.PostConstruct

@Component
class IssuePoller @Autowired constructor(
    private val settings: Settings,
    private val appState: AppState,
    private val syncApplicationProperties: SyncApplicationProperties,
    private val objectMapper: ObjectMapper,
    private val syncRequestProducer: SyncRequestProducer,
    private val clientFactory: ClientFactory,
    private val synchronizationFlowFactory: SynchronizationFlowFactory
) : Logging {
    private val batchSize = 200
    private val issueTrackingAppUsers = settings.trackingApplications.map { it.username }

    @PostConstruct
    fun afterPropertiesSet() {
        logger().info("Polling set for {}", syncApplicationProperties.pollingCron)
        settings.earliestSyncDate?.let {
            // we want to fail hard here as assuming a different "earliest" date might have
            // unwanted effects
            val erliestSyncDate = LocalDateTime.parse(it)
            appState.lastPollingTimestamp = appState.lastPollingTimestamp?.let { localDateTime ->
                if (localDateTime.isAfter(erliestSyncDate)) {
                    localDateTime
                } else erliestSyncDate
            } ?: erliestSyncDate
        }
    }

    @Scheduled(cron = "\${sync.pollingCron:}")
    fun checkForUpdatedIssues() {
        val polledIssues = pollChangedIssuesFromTrackingApps()
        updateLastPollingTimestamp()
        processChangedIssues(polledIssues)
    }

    private fun pollChangedIssuesFromTrackingApps(): MutableMap<IssueTrackingApplication, List<Issue>> {
        val polledIssues = mutableMapOf<IssueTrackingApplication, List<Issue>>()
        settings.trackingApplications.filter { it.polling }.forEach { trackingApp ->
            logger().info("Checking for issues for {}", trackingApp.name)
            val issueTrackingClient = clientFactory.getClient(trackingApp)
            polledIssues.put(trackingApp, pollIssuesInBatches(issueTrackingClient))
        }
        return polledIssues
    }

    private fun pollIssuesInBatches(
        issueTrackingClient: IssueTrackingClient<Any>
    ): MutableList<Issue> {
        val allChangedIssues = mutableListOf<Issue>()
        var offset = 0
        do {
            val changedIssues: Collection<Issue> = try {
                val timestamp = appState.lastPollingTimestamp ?: LocalDateTime.now()
                issueTrackingClient.changedIssuesSince(timestamp, batchSize, offset)
                    .filter { lastUpdatedByJira2Rtc(it).not() }
            } catch (e: Exception) {
                logger().error(
                    "Could not load issues or polling. One common problem could be your authentication or authorisation." +
                            "\nException was: ${e.message}"
                )
                emptyList()
            }
            allChangedIssues.addAll(changedIssues)
            offset += batchSize
        } while (changedIssues.isNotEmpty())
        return allChangedIssues
    }

    private fun lastUpdatedByJira2Rtc(it: Issue) =
        issueTrackingAppUsers.contains(it.lastUpdatedBy)

    private fun updateLastPollingTimestamp() {
        appState.lastPollingTimestamp = LocalDateTime.now()
        appState.persist(objectMapper)
    }

    private fun processChangedIssues(polledIssues: MutableMap<IssueTrackingApplication, List<Issue>>) {
        polledIssues.forEach { (trackingApp, issues) ->
            issues.forEach { issue ->
                if (synchronizationFlowFactory.getSynchronizationFlow(trackingApp.name, issue) != null) {
                    scheduleSync(issue)
                }
            }
        }
    }

    private fun scheduleSync(issue: Issue) = syncRequestProducer.queue(issue)
}