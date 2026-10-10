package com.mrares095.stickynote

import org.junit.Assert.assertEquals
import org.junit.Test

class DriveStateMergerTest {
    private fun note(id: Long, title: String, updatedAt: Long, attachmentUri: String? = null) =
        Note(id = id, title = title, text = "text-$title", category = "Osobno",
            updatedAt = updatedAt, attachmentUri = attachmentUri)

    @Test fun tombstonesPreventDeletedNotesFromReturning() {
        val merged = DriveStateMerger.mergeNotes(
            listOf(note(1, "keep", 100), note(2, "deleted", 200)),
            listOf(note(2, "old copy", 150), note(3, "new", 300)),
            false, setOf(2)
        )
        assertEquals(setOf(1L, 3L), merged.map { it.id }.toSet())
    }

    @Test fun newerLocalEditWinsConflict() {
        val merged = DriveStateMerger.mergeNotes(
            listOf(note(1, "local", 5000)), listOf(note(1, "cloud", 1000)), false, emptySet()
        )
        assertEquals("local", merged.single().title)
    }

    @Test fun remoteEditWinsUnlessLocalIsMoreThanOneSecondNewer() {
        val merged = DriveStateMerger.mergeNotes(
            listOf(note(1, "local", 2000)), listOf(note(1, "cloud", 1500)), false, emptySet()
        )
        assertEquals("cloud", merged.single().title)
    }

    @Test fun localAttachmentSurvivesWhenRemoteCopyWins() {
        val merged = DriveStateMerger.mergeNotes(
            listOf(note(1, "local older", 1000, "content://local/file")),
            listOf(note(1, "cloud newer", 3000)), false, emptySet()
        )
        assertEquals("cloud newer", merged.single().title)
        assertEquals("content://local/file", merged.single().attachmentUri)
    }

    @Test fun firstSyncPrefersRemoteButHonorsTombstones() {
        val merged = DriveStateMerger.mergeNotes(
            listOf(note(1, "welcome", 100)),
            listOf(note(2, "cloud", 200), note(3, "deleted", 300)),
            true, setOf(3)
        )
        assertEquals(listOf(2L), merged.map { it.id })
    }

    @Test fun categoryTombstonesPreventDeletedCategoriesFromReturning() {
        assertEquals(
            listOf("Sve", "Osobno", "Putovanja"),
            DriveStateMerger.mergeCategories(
                listOf("Sve", "Osobno", "Posao"), listOf("Posao", "Putovanja"), setOf("Posao")
            )
        )
    }

    @Test fun categoryMergeKeepsFallbackWhenAllCategoriesWereDeleted() {
        assertEquals(
            listOf("Sve"),
            DriveStateMerger.mergeCategories(listOf("Posao"), listOf("Posao"), setOf("Posao"))
        )
    }
}
