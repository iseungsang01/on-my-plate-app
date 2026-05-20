package com.lss.onmyplate.nativeplanner.data.repository

import android.content.Context
import com.lss.onmyplate.nativeplanner.BuildConfig
import com.lss.onmyplate.nativeplanner.data.api.PlannerHttpClient
import com.lss.onmyplate.nativeplanner.data.model.AvailabilityGroupDto
import com.lss.onmyplate.nativeplanner.data.model.AvailabilityGroupDummyScheduleDto
import com.lss.onmyplate.nativeplanner.data.model.AvailabilityGroupMemberDto
import com.lss.onmyplate.nativeplanner.data.model.AvailabilityGroupProposalCommentDto
import com.lss.onmyplate.nativeplanner.data.model.AvailabilityGroupProposalDto
import com.lss.onmyplate.nativeplanner.data.model.AvailabilityGroupProposalResponseDto
import com.lss.onmyplate.nativeplanner.data.model.AvailabilityGroupSummaryDto
import com.lss.onmyplate.nativeplanner.data.model.AvailabilityResponseDto
import com.lss.onmyplate.nativeplanner.data.model.AvailabilitySlotDto
import com.lss.onmyplate.nativeplanner.data.model.AvailabilitySnapshotDto
import com.lss.onmyplate.nativeplanner.data.model.AvailabilitySort
import com.lss.onmyplate.nativeplanner.data.model.GroupRole
import com.lss.onmyplate.nativeplanner.data.model.MemberSummaryDto
import com.lss.onmyplate.nativeplanner.data.model.ProposalResponseSummaryDto
import com.lss.onmyplate.nativeplanner.data.model.ProposalResponseValue
import com.lss.onmyplate.nativeplanner.data.model.ProposalStatus
import com.lss.onmyplate.nativeplanner.data.model.SuggestionMode
import com.lss.onmyplate.nativeplanner.data.model.VisibilityMode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import java.time.Instant

class AvailabilityGroupRepository(context: Context) {
    private val appContext = context.applicationContext
    private val sessionPrefs = appContext.getSharedPreferences(BuildConfig.PLANNER_SESSION_PREFS_NAME, Context.MODE_PRIVATE)
    private val http = PlannerHttpClient(
        rawBaseUrl = BuildConfig.PLANNER_API_BASE_URL,
        notConfiguredMessage = "Planner availability group API is not configured.",
        onUnauthorized = { clearCachedSession() },
    )

    fun isConfigured(): Boolean = http.isConfigured()

    suspend fun listGroups(): List<AvailabilityGroupSummaryDto> = withContext(Dispatchers.IO) {
        val json = JSONObject(request("GET", "/api/planner/availability-groups"))
        json.optJSONArray("groups").toJsonObjects().map { it.toGroupSummary() }
    }

    suspend fun createAvailabilityGroup(
        title: String,
        scopeStart: Instant,
        scopeEnd: Instant,
        slotMinutes: Int = 60,
        searchStartTime: String = "08:00",
        searchEndTime: String = "24:00",
    ): AvailabilityGroupDto = withContext(Dispatchers.IO) {
        val body = JSONObject()
            .put("title", title)
            .put("scopeStart", scopeStart.toString())
            .put("scopeEnd", scopeEnd.toString())
            .put("slotMinutes", slotMinutes)
            .put("searchStartTime", searchStartTime)
            .put("searchEndTime", searchEndTime)
            .put("visibilityMode", VisibilityMode.BusyOnly.wire)
            .put("suggestionMode", SuggestionMode.Everyone.wire)
        JSONObject(request("POST", "/api/planner/availability-groups", body)).getJSONObject("group").toGroup()
    }

    suspend fun joinAvailabilityGroup(shareCode: String): AvailabilityGroupDto = withContext(Dispatchers.IO) {
        val json = JSONObject(request("POST", "/api/planner/availability-groups/join", JSONObject().put("shareCode", shareCode.trim().uppercase())))
        json.getJSONObject("group").toGroup()
    }

    suspend fun getAvailabilityGroup(groupId: String): AvailabilityGroupSummaryDto = withContext(Dispatchers.IO) {
        val json = JSONObject(request("GET", "/api/planner/availability-groups/${url(groupId)}"))
        AvailabilityGroupSummaryDto(
            group = json.getJSONObject("group").toGroup(),
            membership = json.optJSONObject("membership")?.toMember(),
            members = json.optJSONObject("members").toMemberSummary(),
        )
    }

    suspend fun listAvailabilityGroupMembers(groupId: String): List<AvailabilityGroupMemberDto> = withContext(Dispatchers.IO) {
        JSONObject(request("GET", "/api/planner/availability-groups/${url(groupId)}/members"))
            .optJSONArray("members")
            .toJsonObjects()
            .map { it.toMember() }
    }

    suspend fun getAvailability(groupId: String, sort: AvailabilitySort = AvailabilitySort.Time): AvailabilityResponseDto = withContext(Dispatchers.IO) {
        JSONObject(request("GET", "/api/planner/availability-groups/${url(groupId)}/availability?sort=${sort.wire}")).toAvailability()
    }

    suspend fun listDummySchedules(groupId: String): List<AvailabilityGroupDummyScheduleDto> = withContext(Dispatchers.IO) {
        JSONObject(request("GET", "/api/planner/availability-groups/${url(groupId)}/dummy-schedules"))
            .optJSONArray("dummySchedules")
            .toJsonObjects()
            .map { it.toDummySchedule() }
    }

    suspend fun createDummySchedule(groupId: String, startAt: Instant, endAt: Instant, privateNote: String?): AvailabilityGroupDummyScheduleDto = withContext(Dispatchers.IO) {
        val body = JSONObject()
            .put("startAt", startAt.toString())
            .put("endAt", endAt.toString())
            .putNullable("privateNote", privateNote?.takeIf { it.isNotBlank() })
        JSONObject(request("POST", "/api/planner/availability-groups/${url(groupId)}/dummy-schedules", body)).getJSONObject("dummySchedule").toDummySchedule()
    }

    suspend fun deleteDummySchedule(groupId: String, dummyScheduleId: String): Unit = withContext(Dispatchers.IO) {
        request("DELETE", "/api/planner/availability-groups/${url(groupId)}/dummy-schedules/${url(dummyScheduleId)}")
    }

    suspend fun listProposals(groupId: String): List<AvailabilityGroupProposalDto> = withContext(Dispatchers.IO) {
        JSONObject(request("GET", "/api/planner/availability-groups/${url(groupId)}/proposals"))
            .optJSONArray("proposals")
            .toJsonObjects()
            .map { it.toProposal() }
    }

    suspend fun createProposal(groupId: String, title: String, startAt: Instant, endAt: Instant): AvailabilityGroupProposalDto = withContext(Dispatchers.IO) {
        val body = JSONObject()
            .put("title", title)
            .put("startAt", startAt.toString())
            .put("endAt", endAt.toString())
        JSONObject(request("POST", "/api/planner/availability-groups/${url(groupId)}/proposals", body)).getJSONObject("proposal").toProposal()
    }

    suspend fun respondToProposal(groupId: String, proposalId: String, response: ProposalResponseValue): AvailabilityGroupProposalResponseDto = withContext(Dispatchers.IO) {
        val wire = when (response) {
            ProposalResponseValue.Accepted, ProposalResponseValue.Rejected -> response.wire
            else -> error("Proposal response must be accepted or rejected.")
        }
        JSONObject(
            request(
                "POST",
                "/api/planner/availability-groups/${url(groupId)}/proposals/${url(proposalId)}/response",
                JSONObject().put("response", wire),
            ),
        ).getJSONObject("response").toProposalResponse()
    }

    suspend fun finalizeProposal(groupId: String, proposalId: String): AvailabilityGroupProposalDto = withContext(Dispatchers.IO) {
        JSONObject(request("POST", "/api/planner/availability-groups/${url(groupId)}/proposals/${url(proposalId)}/finalize"))
            .getJSONObject("proposal")
            .toProposal()
    }

    suspend fun listProposalComments(groupId: String, proposalId: String): List<AvailabilityGroupProposalCommentDto> = withContext(Dispatchers.IO) {
        JSONObject(request("GET", "/api/planner/availability-groups/${url(groupId)}/proposals/${url(proposalId)}/comments"))
            .optJSONArray("comments")
            .toJsonObjects()
            .map { it.toComment() }
    }

    suspend fun createProposalComment(groupId: String, proposalId: String, body: String): AvailabilityGroupProposalCommentDto = withContext(Dispatchers.IO) {
        JSONObject(
            request(
                "POST",
                "/api/planner/availability-groups/${url(groupId)}/proposals/${url(proposalId)}/comments",
                JSONObject().put("body", body),
            ),
        ).getJSONObject("comment").toComment()
    }

    suspend fun assignLeader(groupId: String, memberId: String): AvailabilityGroupMemberDto = withContext(Dispatchers.IO) {
        JSONObject(request("POST", "/api/planner/availability-groups/${url(groupId)}/members/${url(memberId)}/leader"))
            .getJSONObject("member")
            .toMember()
    }

    suspend fun unassignLeader(groupId: String, memberId: String): AvailabilityGroupMemberDto = withContext(Dispatchers.IO) {
        JSONObject(request("DELETE", "/api/planner/availability-groups/${url(groupId)}/members/${url(memberId)}/leader"))
            .getJSONObject("member")
            .toMember()
    }

    private fun request(method: String, path: String, body: JSONObject? = null): String =
        http.request(method, path, token = sessionToken(), body = body)

    private fun sessionToken(): String =
        sessionPrefs.getString(BuildConfig.PLANNER_SESSION_TOKEN_KEY, null)?.takeIf { it.isNotBlank() }
            ?: error("Login is required.")

    private fun clearCachedSession() {
        sessionPrefs.edit().remove(BuildConfig.PLANNER_SESSION_TOKEN_KEY).apply()
    }
}

private fun JSONObject.toGroupSummary(): AvailabilityGroupSummaryDto =
    AvailabilityGroupSummaryDto(
        group = getJSONObject("group").toGroup(),
        membership = optJSONObject("membership")?.toMember(),
        members = optJSONObject("members").toMemberSummary(),
    )

private fun JSONObject.toGroup(): AvailabilityGroupDto =
    AvailabilityGroupDto(
        id = optString("id", optString("groupId")),
        title = optString("title"),
        scopeStart = optString("scopeStart"),
        scopeEnd = optString("scopeEnd"),
        slotMinutes = optInt("slotMinutes", 60),
        searchStartTime = optString("searchStartTime", "08:00"),
        searchEndTime = optString("searchEndTime", "24:00"),
        visibilityMode = VisibilityMode.fromWire(optString("visibilityMode")),
        suggestionMode = SuggestionMode.fromWire(optString("suggestionMode")),
        status = optString("status", "active"),
        shareCode = optNullableString("shareCode"),
        createdAt = optNullableString("createdAt"),
        updatedAt = optNullableString("updatedAt"),
    )

private fun JSONObject?.toMemberSummary(): MemberSummaryDto =
    MemberSummaryDto(
        totalCount = this?.optInt("totalCount") ?: 0,
        ownerCount = this?.optInt("ownerCount") ?: 0,
        myRole = GroupRole.fromWire(this?.optNullableString("myRole")),
    )

private fun JSONObject.toMember(): AvailabilityGroupMemberDto =
    AvailabilityGroupMemberDto(
        id = optString("id"),
        groupId = optNullableString("groupId"),
        role = GroupRole.fromWire(optString("role")),
        isMe = optBoolean("isMe", false),
        joinedAt = optNullableString("joinedAt"),
    )

private fun JSONObject.toAvailability(): AvailabilityResponseDto =
    AvailabilityResponseDto(
        groupId = optString("groupId"),
        slotMinutes = optInt("slotMinutes", 60),
        visibilityMode = VisibilityMode.fromWire(optString("visibilityMode")),
        sort = if (optString("sort") == AvailabilitySort.Rank.wire) AvailabilitySort.Rank else AvailabilitySort.Time,
        totalMembers = optInt("totalMembers"),
        slots = optJSONArray("slots").toJsonObjects().map { it.toAvailabilitySlot() },
    )

private fun JSONObject.toAvailabilitySlot(): AvailabilitySlotDto =
    AvailabilitySlotDto(
        startsAt = optString("startsAt"),
        endsAt = optString("endsAt"),
        availableCount = optInt("availableCount"),
        unavailableCount = optInt("unavailableCount"),
        totalCount = optInt("totalCount"),
        rankScore = optInt("rankScore"),
    )

private fun JSONObject.toDummySchedule(): AvailabilityGroupDummyScheduleDto =
    AvailabilityGroupDummyScheduleDto(
        id = optString("id"),
        groupId = optString("groupId"),
        startAt = optString("startAt"),
        endAt = optString("endAt"),
        isMine = optBoolean("isMine"),
        privateNote = optNullableString("privateNote"),
        createdAt = optNullableString("createdAt"),
        updatedAt = optNullableString("updatedAt"),
    )

private fun JSONObject.toProposal(): AvailabilityGroupProposalDto =
    AvailabilityGroupProposalDto(
        id = optString("id"),
        groupId = optString("groupId"),
        title = optString("title"),
        startAt = optString("startAt"),
        endAt = optString("endAt"),
        visibilityMode = VisibilityMode.fromWire(optString("visibilityMode")),
        availabilitySnapshot = optJSONObject("availabilitySnapshot").toAvailabilitySnapshot(),
        responseSummary = optJSONObject("responseSummary").toProposalResponseSummary(),
        myResponse = optJSONObject("myResponse")?.toProposalResponse(),
        status = ProposalStatus.fromWire(optString("status")),
        finalizedAt = optNullableString("finalizedAt"),
        createdAt = optNullableString("createdAt"),
        updatedAt = optNullableString("updatedAt"),
    )

private fun JSONObject?.toAvailabilitySnapshot(): AvailabilitySnapshotDto =
    AvailabilitySnapshotDto(
        availableCount = this?.optInt("availableCount") ?: 0,
        unavailableCount = this?.optInt("unavailableCount") ?: 0,
        totalCount = this?.optInt("totalCount") ?: 0,
    )

private fun JSONObject?.toProposalResponseSummary(): ProposalResponseSummaryDto =
    ProposalResponseSummaryDto(
        pendingCount = this?.optInt("pendingCount") ?: 0,
        acceptedCount = this?.optInt("acceptedCount") ?: 0,
        rejectedCount = this?.optInt("rejectedCount") ?: 0,
        totalCount = this?.optInt("totalCount") ?: 0,
    )

private fun JSONObject.toProposalResponse(): AvailabilityGroupProposalResponseDto =
    AvailabilityGroupProposalResponseDto(
        id = optString("id"),
        proposalId = optString("proposalId"),
        response = ProposalResponseValue.fromWire(optString("response")),
        isMine = optBoolean("isMine"),
        respondedAt = optNullableString("respondedAt"),
        createdAt = optNullableString("createdAt"),
        updatedAt = optNullableString("updatedAt"),
    )

private fun JSONObject.toComment(): AvailabilityGroupProposalCommentDto =
    AvailabilityGroupProposalCommentDto(
        id = optString("id"),
        proposalId = optString("proposalId"),
        groupId = optNullableString("groupId"),
        body = optString("body"),
        isMine = optJSONObject("author")?.optBoolean("isMe") ?: false,
        createdAt = optNullableString("createdAt"),
        updatedAt = optNullableString("updatedAt"),
    )

private fun JSONArray?.toJsonObjects(): List<JSONObject> = buildList {
    if (this@toJsonObjects == null) return@buildList
    for (index in 0 until length()) add(getJSONObject(index))
}

private fun JSONObject.optNullableString(name: String): String? =
    if (has(name) && !isNull(name)) optString(name).takeIf { it.isNotBlank() && it != "null" } else null

private fun JSONObject.putNullable(name: String, value: String?): JSONObject =
    put(name, value ?: JSONObject.NULL)

private fun url(value: String): String = URLEncoder.encode(value, Charsets.UTF_8.name())
