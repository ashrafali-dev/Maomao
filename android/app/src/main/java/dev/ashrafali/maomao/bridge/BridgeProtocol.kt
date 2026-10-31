package dev.ashrafali.maomao.bridge

import org.json.JSONObject

object BridgeProtocol {
    fun line(type: String, vararg fields: Pair<String, Any?>): String = JSONObject().apply {
        put("type", type)
        fields.forEach { (key, value) -> put(key, value) }
    }.toString() + "\n"

    fun userMessage(text: String, sessionId: String?): String = JSONObject().apply {
        put("type", "input")
        put("content", text)
        if (!sessionId.isNullOrBlank()) put("sessionId", sessionId)
    }.toString() + "\n"

    fun controlResponse(requestId: String, allow: Boolean, originalInput: JSONObject): String = JSONObject().apply {
        put("type", "control_response")
        put("response", JSONObject().apply {
            put("subtype", "success")
            put("request_id", requestId)
            put("response", JSONObject().apply {
                put("behavior", if (allow) "allow" else "deny")
                if (allow) {
                    put("updatedInput", originalInput)
                    put("updatedPermissions", org.json.JSONArray())
                } else {
                    put("message", "Denied by the user in Maomao")
                }
            })
        })
    }.toString() + "\n"
}