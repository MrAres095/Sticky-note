package com.mrares095.stickynote

/**
 * Deterministic merge rules shared by Google Drive sync and unit tests.
 * Deletion tombstones always win, so a permanently deleted note/category
 * cannot be resurrected by an older copy on another device.
 */
object DriveStateMerger {
    fun mergeNotes(
        localNotes: List<Note>,
        remoteNotes: List<Note>,
        preferRemoteOnFirstSync: Boolean,
        deletedNoteIds: Set<Long>
    ): List<Note> {
        // Always start with cloud entries, then add local-only notes. On first
        // sync the cloud copy wins conflicts, but it must never erase notes that
        // exist only on this device.
        val byId = LinkedHashMap<Long, Note>()
        remoteNotes.forEach { byId[it.id] = it }
        localNotes.forEach { local ->
            val cloud = byId[local.id]
            when {
                cloud == null -> byId[local.id] = local
                preferRemoteOnFirstSync -> byId[local.id] = cloud.copy(
                    attachmentUri = local.attachmentUri ?: cloud.attachmentUri
                )
                local.updatedAt > cloud.updatedAt + 1000L -> byId[local.id] = local
                else -> byId[local.id] = cloud.copy(
                    // Attachment URIs belong to this device and are not portable Drive links.
                    attachmentUri = local.attachmentUri ?: cloud.attachmentUri
                )
            }
        }
        return byId.values.filterNot { it.id in deletedNoteIds }
    }

    fun mergeCategories(
        localCategories: List<String>,
        remoteCategories: List<String>,
        deletedCategories: Set<String>
    ): List<String> =
        (localCategories + remoteCategories)
            .distinct()
            .filterNot { it in deletedCategories }
            .ifEmpty { listOf("Sve") }
}
