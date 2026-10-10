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
        val merged = if (preferRemoteOnFirstSync && remoteNotes.isNotEmpty()) {
            remoteNotes
        } else {
            val byId = LinkedHashMap<Long, Note>()
            remoteNotes.forEach { byId[it.id] = it }
            localNotes.forEach { local ->
                val cloud = byId[local.id]
                when {
                    cloud == null -> byId[local.id] = local
                    local.updatedAt > cloud.updatedAt + 1000L -> byId[local.id] = local
                    else -> byId[local.id] = cloud.copy(
                        // Attachment URIs belong to this device and are not portable Drive links.
                        attachmentUri = local.attachmentUri ?: cloud.attachmentUri
                    )
                }
            }
            byId.values.toList()
        }
        return merged.filterNot { it.id in deletedNoteIds }
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
