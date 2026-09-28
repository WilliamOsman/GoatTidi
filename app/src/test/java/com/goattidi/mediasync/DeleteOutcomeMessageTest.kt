package com.goattidi.mediasync

import com.goattidi.mediasync.ui.deleteOutcomeMessage
import org.junit.Assert.assertEquals
import org.junit.Test

class DeleteOutcomeMessageTest {

    @Test
    fun `all deleted`() {
        assertEquals("Deleted 3 files — space reclaimed", deleteOutcomeMessage(requested = 3, notDeleted = 0, skipped = 0))
        assertEquals("Deleted 1 file — space reclaimed", deleteOutcomeMessage(requested = 1, notDeleted = 0, skipped = 0))
    }

    @Test
    fun `nothing deleted never claims space was reclaimed`() {
        // Android 8–10 without storage access: every delete fails silently
        assertEquals("Nothing was deleted — Android didn't allow it", deleteOutcomeMessage(requested = 2, notDeleted = 2, skipped = 0))
    }

    @Test
    fun `partial deletes and safety skips are both reported`() {
        assertEquals(
            "Deleted 2 of 3 files — 1 couldn't be deleted · 4 skipped by the safety re-check",
            deleteOutcomeMessage(requested = 3, notDeleted = 1, skipped = 4)
        )
    }
}
