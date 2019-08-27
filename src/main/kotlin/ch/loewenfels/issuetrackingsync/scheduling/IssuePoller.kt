package ch.loewenfels.issuetrackingsync.scheduling

import ch.loewenfels.issuetrackingsync.Issue
import ch.loewenfels.issuetrackingsync.Logging
import ch.loewenfels.issuetrackingsync.app.AppState
import ch.loewenfels.issuetrackingsync.app.SyncApplicationProperties
import ch.loewenfels.issuetrackingsync.executor.SynchronizationFlowFactory
import ch.loewenfels.issuetrackingsync.logger
import ch.loewenfels.issuetrackingsync.syncclient.ClientFactory
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
    @PostConstruct
    fun afterPropertiesSet() {
        logger().info("Polling set for {}", syncApplicationProperties.pollingCron)
    }

    @Scheduled(cron = "\${sync.pollingCron:}")
    fun checkForUpdatedIssues() {
        settings.trackingApplications.filter { it.polling }.forEach { trackingApp ->
            logger().info("Checking for issues for {}", trackingApp.name)
            val issueTrackingClient = clientFactory.getClient(trackingApp)
            issueTrackingClient.changedIssuesSince(appState.lastPollingTimestamp ?: LocalDateTime.now())
                .forEach { ticket ->
                    if (synchronizationFlowFactory.getSynchronizationFlow(trackingApp.name, ticket) != null) {
                        scheduleSync(ticket)
                    }
                }
        }
        appState.lastPollingTimestamp = LocalDateTime.now()
        appState.persist(objectMapper)
    }

    private fun scheduleSync(issue: Issue) = syncRequestProducer.queue(issue)
}