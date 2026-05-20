package com.lss.onmyplate.nativeplanner.data.model

import org.junit.Assert.assertEquals
import org.junit.Test

class AvailabilityGroupModelsTest {
    @Test
    fun unknownEnumValuesMapToUnknown() {
        assertEquals(GroupRole.Unknown, GroupRole.fromWire("admin"))
        assertEquals(ProposalStatus.Unknown, ProposalStatus.fromWire("proposed"))
        assertEquals(ProposalResponseValue.Unknown, ProposalResponseValue.fromWire("maybe"))
        assertEquals(SuggestionMode.Unknown, SuggestionMode.fromWire("leader_only"))
        assertEquals(VisibilityMode.Unknown, VisibilityMode.fromWire("title_visible"))
    }

    @Test
    fun canonicalEnumValuesParse() {
        assertEquals(GroupRole.Owner, GroupRole.fromWire("owner"))
        assertEquals(ProposalStatus.Pending, ProposalStatus.fromWire("pending"))
        assertEquals(ProposalResponseValue.Accepted, ProposalResponseValue.fromWire("accepted"))
        assertEquals(SuggestionMode.OwnerLeader, SuggestionMode.fromWire("owner_leader"))
        assertEquals(VisibilityMode.BusyOnly, VisibilityMode.fromWire("busy_only"))
    }
}
