package dev.ashrafali.maomao.data

import org.json.JSONObject

 data class ChatSession(
    val id: String,
    val title: String,
    val cwd: String,
    val updatedAt: Long,
    val interrupted: Boolean = false
)

data class ChatEntry(
    val id: String,
    val role: String,
    val text: String,
    val createdAt: Long = System.currentTimeMillis()
)

data class PermissionRequest(
    val requestId: String,
    val toolName: String,
    val input: JSONObject,
    val description: String
)

data class MemorySnapshot(
    val heapUsed: Long = 0,
    val heapTotal: Long = 0,
    val heapLimit: Long = 0,
    val rss: Long = 0,
    val ratio: Double = 0.0
) {
    val percent: Int get() = (ratio * 100).toInt().coerceIn(0, 100)
}