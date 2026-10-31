package dev.ashrafali.maomao

import dev.ashrafali.maomao.bridge.BridgeProtocol
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test

class BridgeProtocolTest {
    @Test fun userMessageUsesXIncliStreamJsonShape() {
        val value = JSONObject(BridgeProtocol.userMessage("hello", "session-123").trim())
        assertEquals("input", value.getString("type"))
        assertEquals("hello", value.getString("content"))
        assertEquals("session-123", value.getString("sessionId"))
    }

    @Test fun permissionDenyResponseIsExplicit() {
        val value = JSONObject(BridgeProtocol.controlResponse("req-1", false, JSONObject()).trim())
        val response = value.getJSONObject("response")
        assertEquals("success", response.getString("subtype"))
        assertEquals("req-1", response.getString("request_id"))
        assertEquals("deny", response.getJSONObject("response").getString("behavior"))
    }
}