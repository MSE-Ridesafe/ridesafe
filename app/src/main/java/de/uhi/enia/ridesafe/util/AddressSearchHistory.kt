package de.uhi.enia.ridesafe.util

import android.content.Context
import androidx.core.content.edit
import org.json.JSONArray

private const val SEARCH_HISTORY_PREFS = "saved_address_search"
private const val SEARCH_HISTORY_KEY = "recent_queries"
internal const val SEARCH_SUGGESTION_LIMIT = 5

/** The last few address searches, newest first — the empty-query suggestions (ADR-05). */
fun loadRecentAddressSearches(context: Context): List<String> =
    runCatching {
        val encoded =
            context
                .getSharedPreferences(SEARCH_HISTORY_PREFS, Context.MODE_PRIVATE)
                .getString(SEARCH_HISTORY_KEY, null) ?: return@runCatching emptyList()
        val array = JSONArray(encoded)
        (0 until array.length()).mapNotNull { array.optString(it).trim().ifBlank { null } }.take(SEARCH_SUGGESTION_LIMIT)
    }.getOrDefault(emptyList())

/** Record a executed search on top of the history; returns the updated list. */
fun recordRecentAddressSearch(
    context: Context,
    query: String,
): List<String> {
    val trimmed = query.trim()
    val updated =
        (listOf(trimmed) + loadRecentAddressSearches(context).filterNot { it.equals(trimmed, ignoreCase = true) })
            .filter(String::isNotBlank)
            .take(SEARCH_SUGGESTION_LIMIT)
    context
        .getSharedPreferences(SEARCH_HISTORY_PREFS, Context.MODE_PRIVATE)
        .edit {
            putString(SEARCH_HISTORY_KEY, JSONArray(updated).toString())
        }
    return updated
}
