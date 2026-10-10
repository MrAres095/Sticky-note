package com.mrares095.stickynote

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DriveStateMergerTest {
    @Test
    fun permanentDeletionTombstonePreventsRemoteResurrection() {
        val local = listOf(Note(2L, "Local", "text", "Osobno"))
        val remote = listOf(Note(2L, "Old cloud copy", "old text", "Osobno"))

        val merged = DriveStateMerger.mergeNotes(
            localNotes = local,
            remoteNotes = remote,
            preferRemoteOnFirstSync = false,
            deletedNoteIds = setOf(2L)
        )

        assertTrue(merged.isEmpty())
    }

    @Test
    fun newerLocalNoteWinsConflict() {
        val local = Note(7L, "Local title", "local edit", "Osobno", updatedAt = 5000L)
        val remote = Note(7L, "Cloud title", "cloud edit", "Osobno", updatedAt = 2000L)

        val merged = DriveStateMerger.mergeNotes(listOf(local), listOf(remote), false, emptySet())

        assertEquals(1, merged.size)
        assertEquals("Local title", merged.single().title)
        assertEquals("local edit", merged.single().text)
    }

    @Test
    fun cloudWinnerDoesNotLoseDeviceLocalAttachment() {
        val local = Note(
            8L, "Same note", "older local text", "Osobno",
            updatedAt = 1000L,
            attachmentUri = "content://local/document/8"
        )
        val remote = Note(8L, "Same note", "newer cloud text", "Osobno", updatedAt = 5000L)

        val merged = DriveStateMerger.mergeNotes(listOf(local), listOf(remote), false, emptySet())

        assertEquals("newer cloud text", merged.single().text)
        assertEquals("content://local/document/8", merged.single().attachmentUri)
    }

    @Test
    fun firstSyncUsesCloudNotesButStillHonorsDeletionTombstones() {
        val local = listOf(Note(1L, "Welcome", "fresh install", "Osobno"))
        val remote = listOf(
            Note(1L, "Welcome", "fresh install", "Osobno"),
            Note(9L, "Cloud note", "should stay", "Osobno"),
            Note(10L, "Deleted elsewhere", "should not return", "Osobno")
        )

        val merged = DriveStateMerger.mergeNotes(local, remote, true, setOf(10L))

        assertEquals(setOf(1L, 9L), merged.map { it.id }.toSet())
        assertFalse(merged.any { it.id == 10L })
    }

    @Test
    fun deletedCategoriesDoNotReappearDuringMerge() {
        val merged = DriveStateMerger.mergeCategories(
            localCategories = listOf("Sve", "Osobno"),
            remoteCategories = listOf("Sve", "Osobno", "Staro"),
            deletedCategories = setOf("Staro", "Osobno")
        )

        assertEquals(listOf("Sve"), merged)
    }
}
