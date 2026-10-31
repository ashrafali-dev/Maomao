package dev.ashrafali.maomao.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

class SessionStore(context: Context) {
    private val prefs = context.getSharedPreferences("maomao_sessions", Context.MODE_PRIVATE)

    @Synchronized fun all(): List<ChatSession> {
        val raw = prefs.getString(KEY_SESSIONS, "[]") ?: "[]"
        return runCatching {
            val array = JSONArray(raw)
            (0 until array.length()).mapNotNull { i ->
                val o = array.optJSONObject(i) ?: return@mapNotNull null
                ChatSession(
                    id = o.optString("id"),
                    title = o.optString("title", "New chat"),
                    cwd = o.optString("cwd", DEFAULT_CWD),
                    updatedAt = o.optLong("updatedAt"),
                    interrupted = o.optBoolean("interrupted")
                ).takeIf { it.id.isNotBlank() }
            }.sortedByDescending { it.updatedAt }
        }.getOrDefault(emptyList())
    }

    @Synchronized fun save(session: ChatSession) {
        val items = all().filterNot { it.id == session.id }.toMutableList()
        items.add(session)
        val array = JSONArray()
        items.sortedByDescending { it.updatedAt }.take(100).forEach { s ->
            array.put(JSONObject().apply {
                put("id", s.id); put("title", s.title); put("cwd", s.cwd)
                put("updatedAt", s.updatedAt); put("interrupted", s.interrupted)
            })
        }
        prefs.edit().putString(KEY_SESSIONS, array.toString()).apply()
    }

    fun markInterrupted(id: String, interrupted: Boolean) {
        all().firstOrNull { it.id == id }?.let { save(it.copy(interrupted = interrupted, updatedAt = System.currentTimeMillis())) }
    }

    fun remove(id: String) {
        val array = JSONArray()
        all().filterNot { it.id == id }.forEach { s ->
            array.put(JSONObject().apply {
                put("id", s.id); put("title", s.title); put("cwd", s.cwd)
                put("updatedAt", s.updatedAt); put("interrupted", s.interrupted)
            })
        }
        prefs.edit().putString(KEY_SESSIONS, array.toString()).apply()
    }

    companion object {
        const val DEFAULT_CWD = "/data/data/com.termux/files/home"
        private const val KEY_SESSIONS = "sessions"
    }
}