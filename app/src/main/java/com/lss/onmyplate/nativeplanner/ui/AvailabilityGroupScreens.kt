package com.lss.onmyplate.nativeplanner.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.lss.onmyplate.nativeplanner.data.model.AvailabilityGroupSummaryDto
import com.lss.onmyplate.nativeplanner.data.model.AvailabilityResponseDto
import com.lss.onmyplate.nativeplanner.data.model.AvailabilitySlotDto
import com.lss.onmyplate.nativeplanner.data.model.ProposalResponseValue
import com.lss.onmyplate.nativeplanner.data.model.ProposalStatus
import com.lss.onmyplate.nativeplanner.data.repository.AvailabilityGroupRepository
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.temporal.ChronoUnit

@Composable
fun AvailabilityGroupListScreen(
    repository: AvailabilityGroupRepository,
    onOpenGroup: (String) -> Unit,
    onCreate: () -> Unit,
    onJoin: () -> Unit,
    onBack: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var groups by remember { mutableStateOf<List<AvailabilityGroupSummaryDto>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }

    fun load() {
        scope.launch {
            loading = true
            error = null
            runCatching { repository.listGroups() }
                .onSuccess { groups = it }
                .onFailure { error = it.userMessage() }
            loading = false
        }
    }

    LaunchedEffect(Unit) {
        load()
    }

    AvailabilitySurface {
        Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            HeaderRow(title = "Appointment coordination", onBack = onBack)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onCreate, colors = ButtonDefaults.buttonColors(containerColor = FeedLoopColors.PrimaryDark)) { Text("Create group") }
                OutlinedButton(onClick = onJoin) { Text("Join") }
                OutlinedButton(onClick = {
                    load()
                }) { Text("Refresh") }
            }
            if (loading) LinearProgressIndicator(Modifier.fillMaxWidth())
            error?.let { ErrorCard(it) }
            if (!loading && groups.isEmpty() && error == null) {
                InfoCard("No groups yet", "Create a group or join with a share code.")
            }
            LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                items(groups.size) { index ->
                    val item = groups[index]
                    Card(
                        modifier = Modifier.fillMaxWidth().clickable { onOpenGroup(item.group.id) },
                        colors = CardDefaults.cardColors(containerColor = FeedLoopColors.Surface),
                        border = BorderStroke(1.dp, FeedLoopColors.Border),
                    ) {
                        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(item.group.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                            Text("${item.members.totalCount} members · my role ${item.members.myRole.wire}", color = FeedLoopColors.Secondary)
                            Text("${item.group.scopeStart} – ${item.group.scopeEnd}", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun AvailabilityGroupCreateScreen(repository: AvailabilityGroupRepository, onCreated: (String) -> Unit, onBack: () -> Unit) {
    val scope = rememberCoroutineScope()
    var title by remember { mutableStateOf("") }
    var message by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(false) }
    AvailabilitySurface {
        Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            HeaderRow("Create availability group", onBack)
            OutlinedTextField(value = title, onValueChange = { title = it }, label = { Text("Group title") }, modifier = Modifier.fillMaxWidth())
            Text("Default: tomorrow through the next 7 days, 60-minute slots, busy-only privacy.", color = FeedLoopColors.Secondary)
            Button(
                enabled = title.isNotBlank() && !loading,
                onClick = {
                    loading = true
                    message = null
                    scope.launch {
                        val now = Instant.now().truncatedTo(ChronoUnit.HOURS)
                        runCatching {
                            repository.createAvailabilityGroup(
                                title = title.trim(),
                                scopeStart = now.plus(1, ChronoUnit.DAYS),
                                scopeEnd = now.plus(8, ChronoUnit.DAYS),
                            )
                        }.onSuccess {
                            message = "Share code: ${it.shareCode ?: "(owner only)"}"
                            onCreated(it.id)
                        }.onFailure { message = it.userMessage() }
                        loading = false
                    }
                },
                colors = ButtonDefaults.buttonColors(containerColor = FeedLoopColors.PrimaryDark),
            ) { Text(if (loading) "Creating..." else "Create") }
            message?.let { Text(it, color = FeedLoopColors.Secondary) }
        }
    }
}

@Composable
fun AvailabilityGroupJoinScreen(repository: AvailabilityGroupRepository, onJoined: (String) -> Unit, onBack: () -> Unit) {
    val scope = rememberCoroutineScope()
    var shareCode by remember { mutableStateOf("") }
    var message by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(false) }
    AvailabilitySurface {
        Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            HeaderRow("Join availability group", onBack)
            OutlinedTextField(value = shareCode, onValueChange = { shareCode = it.uppercase() }, label = { Text("Share code") }, modifier = Modifier.fillMaxWidth())
            Button(
                enabled = shareCode.isNotBlank() && !loading,
                onClick = {
                    loading = true
                    message = null
                    scope.launch {
                        runCatching { repository.joinAvailabilityGroup(shareCode) }
                            .onSuccess { onJoined(it.id) }
                            .onFailure { message = it.userMessage() }
                        loading = false
                    }
                },
                colors = ButtonDefaults.buttonColors(containerColor = FeedLoopColors.PrimaryDark),
            ) { Text(if (loading) "Joining..." else "Join") }
            message?.let { ErrorCard(it) }
        }
    }
}

@Composable
fun AvailabilityGroupDetailScreen(
    repository: AvailabilityGroupRepository,
    groupId: String,
    onOpenProposal: (String) -> Unit,
    onBack: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var summary by remember { mutableStateOf<AvailabilityGroupSummaryDto?>(null) }
    var availability by remember { mutableStateOf<AvailabilityResponseDto?>(null) }
    var proposals by remember { mutableStateOf(emptyList<com.lss.onmyplate.nativeplanner.data.model.AvailabilityGroupProposalDto>()) }
    var loading by remember { mutableStateOf(true) }
    var message by remember { mutableStateOf<String?>(null) }

    fun refresh() {
        scope.launch {
            loading = true
            message = null
            runCatching {
                Triple(repository.getAvailabilityGroup(groupId), repository.getAvailability(groupId), repository.listProposals(groupId))
            }.onSuccess {
                summary = it.first
                availability = it.second
                proposals = it.third
            }.onFailure { message = it.userMessage() }
            loading = false
        }
    }

    LaunchedEffect(groupId) { refresh() }

    AvailabilitySurface {
        Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            HeaderRow(summary?.group?.title ?: "Availability group", onBack)
            if (loading) LinearProgressIndicator(Modifier.fillMaxWidth())
            message?.let { ErrorCard(it) }
            summary?.let {
                InfoCard("Members", "${it.members.totalCount} members · my role ${it.members.myRole.wire}${it.group.shareCode?.let { code -> " · share $code" } ?: ""}")
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { refresh() }) { Text("Refresh") }
                OutlinedButton(onClick = {
                    val slot = availability?.slots?.firstOrNull()
                    if (slot != null) {
                        scope.launch {
                            runCatching {
                                repository.createDummySchedule(groupId, Instant.parse(slot.startsAt), Instant.parse(slot.endsAt), "Unavailable")
                            }.onSuccess { refresh() }.onFailure { message = it.userMessage() }
                        }
                    }
                }) { Text("Block first slot") }
                Button(onClick = {
                    val slot = availability?.slots?.firstOrNull { it.availableCount > 0 } ?: availability?.slots?.firstOrNull()
                    if (slot != null) {
                        scope.launch {
                            runCatching {
                                repository.createProposal(groupId, "Meeting proposal", Instant.parse(slot.startsAt), Instant.parse(slot.endsAt))
                            }.onSuccess { refresh() }.onFailure { message = it.userMessage() }
                        }
                    }
                }, colors = ButtonDefaults.buttonColors(containerColor = FeedLoopColors.PrimaryDark)) { Text("Propose") }
            }
            LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                item { Text("Availability", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold) }
                val slots = availability?.slots.orEmpty()
                items(minOf(slots.size, 12)) { index -> AvailabilitySlotRow(slots[index]) }
                item { Text("Proposals", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold) }
                items(proposals.size) { index ->
                    val proposal = proposals[index]
                    Card(
                        Modifier.fillMaxWidth().clickable { onOpenProposal(proposal.id) },
                        colors = CardDefaults.cardColors(containerColor = FeedLoopColors.Surface),
                        border = BorderStroke(1.dp, FeedLoopColors.Border),
                    ) {
                        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(proposal.title, fontWeight = FontWeight.SemiBold)
                            Text("${proposal.startAt} – ${proposal.endAt}", style = MaterialTheme.typography.bodySmall)
                            Text("Status ${proposal.status.wire} · ${proposal.responseSummary.acceptedCount}/${proposal.responseSummary.totalCount} accepted")
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun AvailabilityProposalDetailScreen(repository: AvailabilityGroupRepository, groupId: String, proposalId: String, onBack: () -> Unit) {
    val scope = rememberCoroutineScope()
    var proposal by remember { mutableStateOf<com.lss.onmyplate.nativeplanner.data.model.AvailabilityGroupProposalDto?>(null) }
    var message by remember { mutableStateOf<String?>(null) }

    fun refresh() {
        scope.launch {
            runCatching { repository.listProposals(groupId).firstOrNull { it.id == proposalId } }
                .onSuccess { proposal = it }
                .onFailure { message = it.userMessage() }
        }
    }
    LaunchedEffect(groupId, proposalId) { refresh() }

    AvailabilitySurface {
        Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            HeaderRow("Proposal", onBack)
            message?.let { ErrorCard(it) }
            proposal?.let { item ->
                InfoCard(item.title, "${item.startAt} – ${item.endAt}\n${item.availabilitySnapshot.availableCount}/${item.availabilitySnapshot.totalCount} available")
                Text("My response: ${item.myResponse?.response?.wire ?: "none"}")
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = {
                        scope.launch {
                            runCatching { repository.respondToProposal(groupId, proposalId, ProposalResponseValue.Accepted) }
                                .onSuccess { refresh() }
                                .onFailure { message = it.userMessage() }
                        }
                    }, enabled = item.status == ProposalStatus.Pending) { Text("Accept") }
                    OutlinedButton(onClick = {
                        scope.launch {
                            runCatching { repository.respondToProposal(groupId, proposalId, ProposalResponseValue.Rejected) }
                                .onSuccess { refresh() }
                                .onFailure { message = it.userMessage() }
                        }
                    }, enabled = item.status == ProposalStatus.Pending) { Text("Reject") }
                    Button(onClick = {
                        scope.launch {
                            runCatching { repository.finalizeProposal(groupId, proposalId) }
                                .onSuccess { refresh() }
                                .onFailure { message = it.userMessage() }
                        }
                    }, enabled = item.status == ProposalStatus.Pending, colors = ButtonDefaults.buttonColors(containerColor = FeedLoopColors.PrimaryDark)) {
                        Text("Finalize")
                    }
                }
            } ?: Text("Proposal not found.")
        }
    }
}

@Composable
private fun AvailabilitySurface(content: @Composable () -> Unit) {
    Box(
        Modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(listOf(FeedLoopColors.Background, FeedLoopColors.PrimaryLight.copy(alpha = 0.35f)))),
    ) { content() }
}

@Composable
private fun HeaderRow(title: String, onBack: () -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
        OutlinedButton(onClick = onBack) { Text("Back") }
    }
}

@Composable
private fun AvailabilitySlotRow(slot: AvailabilitySlotDto) {
    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = FeedLoopColors.Surface), border = BorderStroke(1.dp, FeedLoopColors.Border)) {
        Column(Modifier.padding(10.dp)) {
            Text("${slot.availableCount}/${slot.totalCount} available", fontWeight = FontWeight.SemiBold)
            Text("${slot.startsAt} – ${slot.endsAt}", style = MaterialTheme.typography.bodySmall, color = FeedLoopColors.Secondary)
        }
    }
}

@Composable
private fun InfoCard(title: String, message: String) {
    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = FeedLoopColors.Surface), border = BorderStroke(1.dp, FeedLoopColors.Border)) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, fontWeight = FontWeight.SemiBold)
            Text(message, color = FeedLoopColors.Secondary)
        }
    }
}

@Composable
private fun ErrorCard(message: String) {
    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = FeedLoopColors.ErrorBg), border = BorderStroke(1.dp, FeedLoopColors.Error.copy(alpha = 0.35f))) {
        Text(message, color = FeedLoopColors.Error, modifier = Modifier.padding(12.dp))
    }
}

private fun Throwable.userMessage(): String =
    generateSequence(this) { it.cause }.mapNotNull { it.message?.takeIf(String::isNotBlank) }.firstOrNull()
        ?: this::class.java.simpleName
