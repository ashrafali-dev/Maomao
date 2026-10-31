package dev.ashrafali.maomao.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

class ChatStore(context: Context) {
    private val prefs = context.getSharedPreferences("maomao_chat_cache", Context.MODE_PRIVATE)

    fun load(sessionId: String): List<ChatEntry> = runCatching {
        val array = JSONArray(prefs.getString(sessionId, "[]") ?: "[]")
        (0 until array.length()).mapNotNull { i ->
            val o = array.optJSONObject(i) ?: return@mapNotNull null
            ChatEntry(o.optString("id"), o.optString("role"), o.optString("text"), o.optLong("createdAt"))
        }
    }.getOrDefault(emptyList())

    @Synchronized fun append(sessionId: String, entry: ChatEntry) {
        val items = load(sessionId).toMutableList()
        items.add(entry)
        val array = JSONArray()
        items.takeLast(300).forEach { e ->
            array.put(JSONObject().apply {
                put("id", e.id); put("role", e.role); put("text", e.text); put("createdAt", e.createdAt)
            })
        }
        prefs.edit().putString(sessionId, array.toString()).apply()
    }
}