package com.mediahub.mobile.domain

import org.junit.Assert.*
import org.junit.Test

class TransferPolicyTest {
    @Test fun copyNeverDeletesSource() {
        for (verification in Verification.entries)
            assertFalse(TransferPolicy.mayDeleteSource(TransferKind.COPY, verification, true))
    }

    @Test fun moveRequiresExplicitConsentAndVerifiedBytes() {
        assertFalse(TransferPolicy.mayDeleteSource(TransferKind.MOVE, Verification.CHECKSUM_MATCHED, false))
        assertFalse(TransferPolicy.mayDeleteSource(TransferKind.MOVE, Verification.SIZE_ONLY, true))
        assertFalse(TransferPolicy.mayDeleteSource(TransferKind.MOVE, Verification.NOT_CHECKED, true))
        assertTrue(TransferPolicy.mayDeleteSource(TransferKind.MOVE, Verification.BYTE_COMPARE_MATCHED, true))
        assertTrue(TransferPolicy.mayDeleteSource(TransferKind.MOVE, Verification.CHECKSUM_MATCHED, true))
    }

    @Test fun conflictsRequireApproval() {
        assertEquals(ConflictAction.ASK_USER, TransferPolicy.resolveConflict(true, false))
        assertEquals(ConflictAction.REPLACE_WITH_APPROVAL, TransferPolicy.resolveConflict(true, true))
    }
}
