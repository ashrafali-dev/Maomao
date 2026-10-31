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
    @Test fun fileWriteIncludesOptimisticConflictHash() {
        val value = JSONObject(BridgeProtocol.fileWrite("/home/project/Main.kt", "class Main", "abc123").trim())
        assertEquals("file_write", value.getString("type"))
        assertEquals("/home/project/Main.kt", value.getString("path"))
        assertEquals("abc123", value.getString("expectedHash"))
        assertEquals("class Main", value.getString("content"))
    }

    @Test fun gitPushRequestCarriesConfirmation() {
        val value = JSONObject(BridgeProtocol.gitAction("push", "/home/project", confirmed = true).trim())
        assertEquals("git_action", value.getString("type"))
        assertEquals("push", value.getString("action"))
        assertEquals("/home/project", value.getString("cwd"))
        assertEquals(true, value.getBoolean("confirmed"))
    }

    @Test fun checkpointRestoreUsesStableId() {
        val value = JSONObject(BridgeProtocol.checkpointRestore("checkpoint-123").trim())
        assertEquals("checkpoint_restore", value.getString("type"))
        assertEquals("checkpoint-123", value.getString("checkpointId"))
    }

}