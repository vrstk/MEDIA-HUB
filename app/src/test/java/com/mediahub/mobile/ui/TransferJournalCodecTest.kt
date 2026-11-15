package com.mediahub.mobile.ui

import org.junit.Assert.*
import org.junit.Test

class TransferJournalCodecTest {
    @Test fun preservesUnicodeAndDistinctOutcomes() {
        val entries = listOf(
            TransferJournalEntry("a", "Галерея 🍓", "Mail", "Drive", 100L, 200L,
                TransferJournalStatus.VERIFIED, fileCount = 15, byteCount = 1500),
            TransferJournalEntry("b", "Проверка", "Drive", "Mail", 300L, 400L,
                TransferJournalStatus.NEEDS_REVIEW, note = "Последний файл не подтверждён")
        )
        assertEquals(entries, TransferJournalCodec.decode(TransferJournalCodec.encode(entries)))
    }

    @Test fun onlyRunningEntriesBecomeInterrupted() {
        val original = listOf(
            TransferJournalEntry("a", "Test", "Mail", "Drive", 100, null,
                TransferJournalStatus.RUNNING),
            TransferJournalEntry("b", "Done", "Mail", "Drive", 101, 102,
                TransferJournalStatus.VERIFIED)
        )
        val changed = TransferJournalCodec.markInterrupted(original, 200)
        assertEquals(TransferJournalStatus.INTERRUPTED, changed.first().status)
        assertEquals(200L, changed.first().finishedAt)
        assertEquals(TransferJournalStatus.VERIFIED, changed.last().status)
    }

    @Test fun historyIsBoundedAndMalformedEntriesIgnored() {
        val many = (0..70).map { index ->
            TransferJournalEntry(index.toString(), "File", "Mail", "Drive", 1, 2,
                TransferJournalStatus.VERIFIED)
        }
        assertEquals(50, TransferJournalCodec.decode(TransferJournalCodec.encode(many)).size)
        assertTrue(TransferJournalCodec.decode("not valid json").isEmpty())
        assertTrue(TransferJournalCodec.decode("[{\"id\":\"valid\",\"status\":\"UNKNOWN\"}]").isEmpty())
    }
}
