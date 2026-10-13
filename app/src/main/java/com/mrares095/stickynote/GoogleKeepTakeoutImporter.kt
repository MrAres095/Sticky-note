package com.mrares095.stickynote

import android.content.Context
import android.net.Uri
import org.json.JSONObject
import java.util.zip.ZipInputStream

/**
 * Reads Google Takeout's Google Keep JSON exports locally on-device.
 * The archive is never uploaded. Existing notes are retained and exact
 * title/body duplicates are skipped by the caller.
 */
object GoogleKeepTakeoutImporter {
    data class ImportResult(val notes: List<Note>, val skippedFiles: Int, val errors: List<String>)

    fun read(context: Context, uri: Uri, existing: List<Note>, defaultCategory: String = "Uvezeno iz Keepa"): ImportResult {
        val imported = mutableListOf<Note>()
        val errors = mutableListOf<String>()
        var skipped = 0
        val existingKeys = existing.map { key(it.title, it.text) }.toMutableSet()
        var nextId = maxOf(System.currentTimeMillis(), (existing.maxOfOrNull { it.id } ?: 0L) + 1L)
        context.contentResolver.openInputStream(uri)?.use { raw ->
            ZipInputStream(raw).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    if (!entry.isDirectory && entry.name.endsWith(".json", true)) {
                        try {
                            val content = String(zip.readBytes(), Charsets.UTF_8)
                            val obj = JSONObject(content)
                            if (!obj.has("title") && !obj.has("textContent") && !obj.has("listContent")) {
                                skipped++
                                zip.closeEntry()
                                continue
                            }
                            val title = obj.optString("title").trim()
                            val body = buildString {
                                val text = obj.optString("textContent").trim()
                                if (text.isNotEmpty() && text != "null") append(text)
                                val items = obj.optJSONArray("listContent")
                                if (items != null) for (i in 0 until items.length()) {
                                    val item = items.optJSONObject(i) ?: continue
                                    val itemText = item.optString("text").trim()
                                    if (itemText.isNotEmpty()) {
                                        if (isNotEmpty()) append('\n')
                                        append(if (item.optBoolean("isChecked", false)) "☑ " else "☐ ")
                                        append(itemText)
                                    }
                                }
                            }.trim()
                            if (title.isBlank() && body.isBlank()) {
                                skipped++
                            } else if (key(title, body) in existingKeys) {
                                skipped++
                            } else {
                                val labels = obj.optJSONArray("labels")
                                val label = labels?.optJSONObject(0)?.optString("name")?.trim().orEmpty()
                                val category = label.takeIf { it.isNotBlank() && it != "null" } ?: defaultCategory
                                val color = parseColor(obj.optString("color"))
                                val editedMicros = obj.optLong("userEditedTimestampUsec", 0L)
                                val updated = if (editedMicros > 0L) editedMicros / 1000L else System.currentTimeMillis()
                                imported += Note(
                                    id = nextId++,
                                    title = title,
                                    text = body,
                                    category = category,
                                    color = color,
                                    pinned = obj.optBoolean("isPinned", false),
                                    updatedAt = updated,
                                    archived = obj.optBoolean("isArchived", false)
                                )
                                existingKeys += key(title, body)
                            }
                        } catch (e: Exception) {
                            errors += "${entry.name}: ${e.message ?: "neispravan JSON"}"
                        }
                    }
                    zip.closeEntry()
                }
            }
        } ?: throw IllegalArgumentException("ZIP datoteku nije moguće otvoriti.")
        return ImportResult(imported, skipped, errors)
    }

    private fun key(title: String, body: String) = "${title.trim().lowercase()}\u0000${body.trim().lowercase()}"

    private fun parseColor(value: String): Long = when (value.uppercase()) {
        "RED" -> 0xFFB3261E
        "ORANGE" -> 0xFFE66A00
        "YELLOW" -> 0xFFF9AB00
        "GREEN" -> 0xFF188038
        "TEAL" -> 0xFF12A594
        "BLUE" -> 0xFF1A73E8
        "DARK_BLUE" -> 0xFF174EA6
        "PURPLE" -> 0xFF9334E6
        "PINK" -> 0xFFD01884
        "GRAY", "GREY" -> 0xFF5F6368
        else -> 0xFF252525
    }
}
