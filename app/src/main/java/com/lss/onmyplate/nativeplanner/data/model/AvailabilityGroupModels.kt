package com.lss.onmyplate.nativeplanner.data.model

enum class GroupRole(val wire: String) {
    Owner("owner"),
    Leader("leader"),
    Member("member"),
    Unknown("unknown");

    companion object {
        fun fromWire(value: String?): GroupRole = entries.firstOrNull { it.wire == value } ?: Unknown
    }
}

enum class ProposalStatus(val wire: String) {
    Pending("pending"),
    Finalized("finalized"),
    Cancelled("cancelled"),
    Unknown("unknown");

    companion object {
        fun fromWire(value: String?): ProposalStatus = entries.firstOrNull { it.wire == value } ?: Unknown
    }
}

enum class ProposalResponseValue(val wire: String) {
    Pending("pending"),
    Accepted("accepted"),
    Rejected("rejected"),
    Unknown("unknown");

    companion object {
        fun fromWire(value: String?): ProposalResponseValue = entries.firstOrNull { it.wire == value } ?: Unknown
    }
}

enum class SuggestionMode(val wire: String) {
    Everyone("everyone"),
    OwnerLeader("owner_leader"),
    OwnerOnly("owner_only"),
    Unknown("unknown");

    companion object {
        fun fromWire(value: String?): SuggestionMode = entries.firstOrNull { it.wire == value } ?: Unknown
    }
}

enum class VisibilityMode(val wire: String) {
    BusyOnly("busy_only"),
    ExpandedLimited("expanded_limited"),
    Unknown("unknown");

    companion object {
        fun fromWire(value: String?): VisibilityMode = entries.firstOrNull { it.wire == value } ?: Unknown
    }
}

enum class AvailabilitySort(val wire: String) {
    Time("time"),
    Rank("rank"),
}

data class AvailabilityGroupDto(
    val id: String,
    val title: String,
    val scopeStart: String,
    val scopeEnd: String,
    val slotMinutes: Int,
    val searchStartTime: String,
    val searchEndTime: String,
    val visibilityMode: VisibilityMode,
    val suggestionMode: SuggestionMode,
    val status: String,
    val shareCode: String?,
    val createdAt: String?,
    val updatedAt: String?,
)

data class AvailabilityGroupMemberDto(
    val id: String,
    val groupId: String?,
    val role: GroupRole,
    val isMe: Boolean,
    val joinedAt: String?,
)

data class AvailabilityGroupSummaryDto(
    val group: AvailabilityGroupDto,
    val membership: AvailabilityGroupMemberDto?,
    val members: MemberSummaryDto,
)

data class MemberSummaryDto(
    val totalCount: Int,
    val ownerCount: Int,
    val myRole: GroupRole,
)

data class AvailabilityResponseDto(
    val groupId: String,
    val slotMinutes: Int,
    val visibilityMode: VisibilityMode,
    val sort: AvailabilitySort,
    val totalMembers: Int,
    val slots: List<AvailabilitySlotDto>,
)

data class AvailabilitySlotDto(
    val startsAt: String,
    val endsAt: String,
    val availableCount: Int,
    val unavailableCount: Int,
    val totalCount: Int,
    val rankScore: Int,
)

data class AvailabilityGroupDummyScheduleDto(
    val id: String,
    val groupId: String,
    val startAt: String,
    val endAt: String,
    val isMine: Boolean,
    val privateNote: String?,
    val createdAt: String?,
    val updatedAt: String?,
)

data class AvailabilityGroupProposalDto(
    val id: String,
    val groupId: String,
    val title: String,
    val startAt: String,
    val endAt: String,
    val visibilityMode: VisibilityMode,
    val availabilitySnapshot: AvailabilitySnapshotDto,
    val responseSummary: ProposalResponseSummaryDto,
    val myResponse: AvailabilityGroupProposalResponseDto?,
    val status: ProposalStatus,
    val finalizedAt: String?,
    val createdAt: String?,
    val updatedAt: String?,
)

data class AvailabilitySnapshotDto(
    val availableCount: Int,
    val unavailableCount: Int,
    val totalCount: Int,
)

data class ProposalResponseSummaryDto(
    val pendingCount: Int,
    val acceptedCount: Int,
    val rejectedCount: Int,
    val totalCount: Int,
)

data class AvailabilityGroupProposalResponseDto(
    val id: String,
    val proposalId: String,
    val response: ProposalResponseValue,
    val isMine: Boolean,
    val respondedAt: String?,
    val createdAt: String?,
    val updatedAt: String?,
)

data class AvailabilityGroupProposalCommentDto(
    val id: String,
    val proposalId: String,
    val groupId: String?,
    val body: String,
    val isMine: Boolean,
    val createdAt: String?,
    val updatedAt: String?,
)
