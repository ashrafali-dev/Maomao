package dev.ashrafali.maomao.bridge

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.InetSocketAddress
import java.net.Socket
import java.util.UUID

class TermuxBridge(private val context: Context) {
    private val prefs = context.getSharedPreferences("maomao_bridge", Context.MODE_PRIVATE)
    private val token: String = prefs.getString("token", null) ?: UUID.randomUUID().toString().replace("-", "").also {
        prefs.edit().putString("token", it).apply()
    }

    @Volatile private var socket: Socket? = null
    @Volatile private var writer: BufferedWriter? = null

    @Suppress("DEPRECATION")
    fun isTermuxInstalled(): Boolean = runCatching {
        if (Build.VERSION.SDK_INT >= 33) {
            context.packageManager.getPackageInfo("com.termux", PackageManager.PackageInfoFlags.of(0))
        } else {
            context.packageManager.getPackageInfo("com.termux", 0)
        }
        true
    }.getOrDefault(false)

    fun startRuntime(): Result<Unit> = runCatching {
        val script = context.assets.open("bridge.mjs").bufferedReader().use { it.readText() }
        val preload = context.assets.open("memory-guard.cjs").bufferedReader().use { it.readText() }
        val payload = buildString {
            append("set -eu\n")
            append("mkdir -p \"\$HOME/.maomao\"\n")
            append("cat > \"\$HOME/.maomao/bridge.mjs\" <<'MAOMAO_BRIDGE_EOF'\n")
            append(script); append("\nMAOMAO_BRIDGE_EOF\n")
            append("cat > \"\$HOME/.maomao/memory-guard.cjs\" <<'MAOMAO_GUARD_EOF'\n")
            append(preload); append("\nMAOMAO_GUARD_EOF\n")
            append("chmod 700 \"\$HOME/.maomao\"\n")
            append("if [ -f \"\$HOME/.maomao/bridge.pid\" ] && kill -0 \"$(cat \"\$HOME/.maomao/bridge.pid\")\" 2>/dev/null; then exit 0; fi\n")
            append("nohup node \"\$HOME/.maomao/bridge.mjs\" 37645 37646 '$token' \"\$HOME/.local/bin/xincli\" \"\$HOME\" </dev/null >>\"\$HOME/.maomao/bridge.log\" 2>&1 &\n")
            append("echo \$! > \"\$HOME/.maomao/bridge.pid\"\n")
        }
        val intent = Intent("com.termux.RUN_COMMAND").apply {
            setClassName("com.termux", "com.termux.app.RunCommandService")
            putExtra("com.termux.RUN_COMMAND_PATH", "/data/data/com.termux/files/usr/bin/sh")
            putExtra("com.termux.RUN_COMMAND_ARGUMENTS", arrayOf("-s"))
            putExtra("com.termux.RUN_COMMAND_STDIN", payload)
            putExtra("com.termux.RUN_COMMAND_WORKDIR", "/data/data/com.termux/files/home")
            putExtra("com.termux.RUN_COMMAND_BACKGROUND", true)
            putExtra("com.termux.RUN_COMMAND_LABEL", "Start Maomao bridge")
            putExtra("com.termux.RUN_COMMAND_DESCRIPTION", "Starts the local-only Maomao to XINCLI bridge")
        }
        context.startService(intent)
        Unit
    }

    suspend fun connect(onEvent: (String) -> Unit): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            disconnect()
            val s = Socket()
            s.connect(InetSocketAddress("127.0.0.1", 37645), 2500)
            s.soTimeout = 0
            socket = s
            val out = BufferedWriter(OutputStreamWriter(s.getOutputStream(), Charsets.UTF_8))
            writer = out
            out.write(BridgeProtocol.line("hello", "token" to token)); out.flush()
            val reader = BufferedReader(InputStreamReader(s.getInputStream(), Charsets.UTF_8))
            while (!s.isClosed) {
                val line = reader.readLine() ?: break
                onEvent(line)
            }
            if (!s.isClosed) throw IllegalStateException("Bridge connection closed")
        }
    }

    fun send(rawLine: String): Result<Unit> = runCatching {
        val out = writer ?: error("Not connected to Termux bridge")
        synchronized(out) { out.write(rawLine); out.flush() }
    }

    fun disconnect() {
        runCatching { writer?.close() }
        runCatching { socket?.close() }
        writer = null; socket = null
    }
}