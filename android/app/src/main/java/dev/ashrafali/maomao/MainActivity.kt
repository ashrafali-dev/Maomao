package dev.ashrafali.maomao

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.AccountTree
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ChatBubbleOutline
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import dev.ashrafali.maomao.bridge.BridgeProtocol
import dev.ashrafali.maomao.bridge.TermuxBridge
import dev.ashrafali.maomao.data.ChatEntry
import dev.ashrafali.maomao.data.ChatSession
import dev.ashrafali.maomao.data.ChatStore
import dev.ashrafali.maomao.data.MemorySnapshot
import dev.ashrafali.maomao.data.PermissionRequest
import dev.ashrafali.maomao.data.SessionStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.util.UUID

private enum class Page(val label: String) {
    CHAT("Chat"), FILES("Files"), GIT("Git"), TERMINAL("Terminal"), SETTINGS("Settings")
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { MaomaoApp() }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MaomaoApp() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val bridge = remember { TermuxBridge(context.applicationContext) }
    val sessionStore = remember { SessionStore(context) }
    val chatStore = remember { ChatStore(context) }
    val runtimePrefs = remember { context.getSharedPreferences("maomao_runtime", android.content.Context.MODE_PRIVATE) }
    val sessions = remember { mutableStateListOf<ChatSession>().apply { addAll(sessionStore.all()) } }
    val entries = remember { mutableStateListOf<ChatEntry>() }

    var page by remember { mutableStateOf(Page.CHAT) }
    var connected by remember { mutableStateOf(false) }
    var connecting by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("Checking Termux…") }
    var currentSession by remember { mutableStateOf<ChatSession?>(null) }
    var currentSessionId by remember { mutableStateOf<String?>(null) }
    var isRunning by remember { mutableStateOf(false) }
    var input by remember { mutableStateOf("") }
    var cwd by remember { mutableStateOf(SessionStore.DEFAULT_CWD) }
    var provider by remember { mutableStateOf(runtimePrefs.getString("provider", "anthropic") ?: "anthropic") }
    var apiKey by remember { mutableStateOf(ApiKeyVault.get(context)) }
    var baseUrl by remember { mutableStateOf(runtimePrefs.getString("baseUrl", "") ?: "") }
    var model by remember { mutableStateOf(runtimePrefs.getString("model", "") ?: "") }
    var effort by remember { mutableStateOf(runtimePrefs.getString("effort", "high") ?: "high") }
    var pendingPrompt by remember { mutableStateOf<String?>(null) }
    var permission by remember { mutableStateOf<PermissionRequest?>(null) }
    var memory by remember { mutableStateOf(MemorySnapshot()) }
    var memoryWarning by remember { mutableStateOf<String?>(null) }
    var showHistory by remember { mutableStateOf(false) }
    var showCrashDialog by remember { mutableStateOf(false) }
    var terminalCommand by remember { mutableStateOf("") }
    var terminalOutput by remember { mutableStateOf("Termux command output will appear here.") }
    var filesPath by remember { mutableStateOf(SessionStore.DEFAULT_CWD) }
    var files by remember { mutableStateOf<List<JSONObject>>(emptyList()) }
    var fileError by remember { mutableStateOf<String?>(null) }
    var editorPath by remember { mutableStateOf<String?>(null) }
    var editorText by remember { mutableStateOf("") }
    var editorHash by remember { mutableStateOf("NEW") }
    var editorError by remember { mutableStateOf<String?>(null) }
    var editorCheckpointId by remember { mutableStateOf<String?>(null) }
    var showFileEditor by remember { mutableStateOf(false) }
    var gitOutput by remember { mutableStateOf("Open a Git repository to inspect status and manage branches.") }
    var gitBranches by remember { mutableStateOf<List<String>>(emptyList()) }
    var currentBranch by remember { mutableStateOf("") }
    var diffText by remember { mutableStateOf("") }
    var commitMessage by remember { mutableStateOf("Update project") }
    var pendingGitAction by remember { mutableStateOf<String?>(null) }
    var pendingGitValue by remember { mutableStateOf("") }
    var repoMapFiles by remember { mutableStateOf<List<JSONObject>>(emptyList()) }
    var repoMapSymbols by remember { mutableStateOf<List<JSONObject>>(emptyList()) }
    var repoMapCount by remember { mutableStateOf(0) }
    var showRepoMap by remember { mutableStateOf(false) }
    var checkpointSafe by remember { mutableStateOf(false) }
    var checkpointConflicts by remember { mutableStateOf<List<String>>(emptyList()) }
    var showCheckpointPreview by remember { mutableStateOf(false) }
    var toast by remember { mutableStateOf<String?>(null) }
    var lastSessionWasOom by remember { mutableStateOf(false) }

    fun persistCurrent() {
        val id = currentSessionId ?: return
        val old = currentSession
        val latest = ChatSession(
            id = id,
            title = old?.title ?: "New chat",
            cwd = old?.cwd ?: cwd,
            updatedAt = System.currentTimeMillis(),
            interrupted = false
        )
        currentSession = latest
        sessionStore.save(latest)
        val index = sessions.indexOfFirst { it.id == id }
        if (index >= 0) sessions[index] = latest else sessions.add(0, latest)
    }

    fun addEntry(role: String, text: String) {
        if (text.isBlank()) return
        val entry = ChatEntry(UUID.randomUUID().toString(), role, text)
        entries.add(entry)
        currentSessionId?.let { chatStore.append(it, entry) }
    }

    fun processBridgeEvent(raw: String) {
        val event = runCatching { JSONObject(raw) }.getOrNull() ?: return
        when (event.optString("type")) {
            "welcome" -> {
                connected = true
                connecting = false
                status = "Connected to Termux"
                val bridgeSessionId = event.optString("sessionId").takeIf { it.isNotBlank() && it != "null" }
                if (bridgeSessionId != null) {
                    currentSessionId = bridgeSessionId
                    val saved = sessions.firstOrNull { it.id == bridgeSessionId }
                    if (saved != null) {
                        val restored = saved.copy(interrupted = !event.optBoolean("running"))
                        currentSession = restored
                        cwd = event.optString("cwd").takeIf { it.isNotBlank() && it != "null" } ?: restored.cwd
                        if (entries.isEmpty()) entries.addAll(chatStore.load(bridgeSessionId))
                        sessionStore.save(restored)
                    }
                }
                if (event.optBoolean("running")) {
                    isRunning = true
                    runtimePrefs.edit().putBoolean("wasRunning", true).apply()
                    currentSession?.let { session ->
                        val restored = session.copy(interrupted = false)
                        currentSession = restored
                        sessionStore.save(restored)
                    }
                }
            }
            "process_started" -> {
                isRunning = true
                runtimePrefs.edit().putBoolean("wasRunning", true).apply()
                val id = event.optString("sessionId").takeIf { it.isNotBlank() && it != "null" }
                if (id != null) {
                    val isNewSession = currentSessionId != id
                    currentSessionId = id
                    val sessionTitle = event.optString("title").takeIf { it.isNotBlank() && it != "null" }
                        ?: currentSession?.title ?: "New chat"
                    val sessionCwd = event.optString("cwd").takeIf { it.isNotBlank() && it != "null" } ?: cwd
                    currentSession = ChatSession(
                        id = id, title = sessionTitle, cwd = sessionCwd,
                        updatedAt = System.currentTimeMillis(), interrupted = false
                    )
                    cwd = sessionCwd
                    if (isNewSession && chatStore.load(id).isEmpty()) entries.forEach { chatStore.append(id, it) }
                    persistCurrent()
                }
                status = "XINCLI running"
                pendingPrompt?.let { prompt ->
                    pendingPrompt = null
                    bridge.send(BridgeProtocol.userMessage(prompt, currentSessionId))
                }
            }
            "cli_event" -> {
                val cliEvent = event.optJSONObject("event")
                val id = event.optString("sessionId").takeIf { it.isNotBlank() && it != "null" }
                if (id != null) {
                    val isNewSession = id != currentSessionId
                    currentSessionId = id
                    val sessionTitle = event.optString("title").takeIf { it.isNotBlank() && it != "null" }
                        ?: currentSession?.title ?: "New chat"
                    val sessionCwd = event.optString("cwd").takeIf { it.isNotBlank() && it != "null" } ?: cwd
                    currentSession = ChatSession(
                        id = id, title = sessionTitle, cwd = sessionCwd,
                        updatedAt = System.currentTimeMillis(), interrupted = false
                    )
                    cwd = sessionCwd
                    if (isNewSession && chatStore.load(id).isEmpty()) entries.forEach { chatStore.append(id, it) }
                    persistCurrent()
                }
                if (cliEvent != null) handleCliEvent(cliEvent, ::addEntry) { req -> permission = req }
                else {
                    val line = event.optString("raw")
                    if (event.optString("stream") == "stderr" && line.isNotBlank()) addEntry("system", line)
                }
                persistCurrent()
            }
            "memory_metrics" -> {
                val ratio = event.optDouble("ratio", 0.0)
                memory = MemorySnapshot(
                    heapUsed = event.optLong("heapUsed"), heapTotal = event.optLong("heapTotal"),
                    heapLimit = event.optLong("heapLimit"), rss = event.optLong("rss"), ratio = ratio
                )
                memoryWarning = when (event.optString("level")) {
                    "critical" -> "Memory is critical (${(ratio * 100).toInt()}% of the V8 heap limit). Pause new work and save your progress."
                    "high" -> "Memory is high (${(ratio * 100).toInt()}%). Avoid starting additional workers or large file operations."
                    "watch" -> "Memory use is rising (${(ratio * 100).toInt()}%)."
                    else -> null
                }
            }
            "process_exit" -> {
                isRunning = false
                runtimePrefs.edit().putBoolean("wasRunning", false).apply()
                val code = event.optInt("code", -1)
                lastSessionWasOom = event.optBoolean("oomLikely")
                status = if (lastSessionWasOom) "Stopped: possible memory crash" else "XINCLI stopped (exit $code)"
                currentSessionId?.let { id ->
                    val old = currentSession ?: sessions.firstOrNull { it.id == id }
                    if (old != null) {
                        val updated = old.copy(interrupted = code != 0, updatedAt = System.currentTimeMillis())
                        sessionStore.save(updated)
                        val idx = sessions.indexOfFirst { it.id == id }
                        if (idx >= 0) sessions[idx] = updated else sessions.add(0, updated)
                        currentSession = updated
                    }
                }
                if (lastSessionWasOom) showCrashDialog = true
            }
            "bridge_error", "process_error" -> {
                status = event.optString("message", "Bridge error")
                toast = status
                connecting = false
            }
            "memory_guard_block" -> {
                val message = event.optString("message", "New work was paused because memory is critical.")
                toast = message
                addEntry("system", message)
            }
            "terminal_result" -> terminalOutput = buildString {
                append("$ ").append(terminalCommand).append("\n")
                append(event.optString("stdout"))
                if (event.optString("stderr").isNotBlank()) append("\n[stderr]\n").append(event.optString("stderr"))
                append("\n\nExit code: ").append(event.optInt("code", -1))
            }
            "files_result" -> {
                filesPath = event.optString("path", filesPath)
                fileError = event.optString("error").takeIf { it.isNotBlank() && it != "null" }
                val array = event.optJSONArray("entries")
                files = if (array == null) emptyList() else (0 until array.length()).mapNotNull { array.optJSONObject(it) }
            }
            "file_read_result" -> {
                val error = event.optString("error").takeIf { it.isNotBlank() && it != "null" }
                if (error != null) {
                    editorError = error
                    toast = error
                } else {
                    editorPath = event.optString("path")
                    editorText = event.optString("content")
                    editorHash = event.optString("hash", "NEW")
                    editorError = null
                    editorCheckpointId = null
                    showFileEditor = true
                }
            }
            "file_write_result" -> {
                val error = event.optString("error").takeIf { it.isNotBlank() && it != "null" }
                if (error != null) {
                    editorError = error
                    toast = error
                } else {
                    editorHash = event.optString("hash", editorHash)
                    editorCheckpointId = event.optString("checkpointId").takeIf { it.isNotBlank() && it != "null" }
                    editorError = null
                    toast = "File saved. A pre-save checkpoint is available for safe undo."
                    bridge.send(BridgeProtocol.line("files_list", "path" to filesPath))
                }
            }
            "git_result" -> {
                val action = event.optString("action")
                val code = event.optInt("code", -1)
                val stdout = event.optString("stdout")
                val stderr = event.optString("stderr")
                if (action == "branches" && code == 0) {
                    val parsed = runCatching { JSONObject(stdout) }.getOrNull()
                    currentBranch = parsed?.optString("current").orEmpty()
                    val array = parsed?.optJSONArray("branches")
                    gitBranches = if (array == null) emptyList() else (0 until array.length()).map { array.optString(it) }
                } else if (action == "diff") {
                    diffText = if (code == 0) stdout.ifBlank { "No unstaged changes." } else stderr
                } else {
                    gitOutput = if (code == 0) stdout.ifBlank { "Git $action completed." } else stderr.ifBlank { stdout.ifBlank { "Git $action failed (exit $code)." } }
                    if (code == 0 && action in listOf("switch_branch", "create_branch", "commit", "pull", "fetch", "push")) {
                        bridge.send(BridgeProtocol.line("git_action", "action" to "status", "cwd" to cwd))
                        bridge.send(BridgeProtocol.line("git_action", "action" to "branches", "cwd" to cwd))
                        bridge.send(BridgeProtocol.line("git_action", "action" to "diff", "cwd" to cwd))
                    }
                }
                if (code != 0) toast = stderr.ifBlank { "Git $action failed." }
            }
            "repo_map_result" -> {
                repoMapCount = event.optInt("fileCount")
                val fa = event.optJSONArray("files")
                val sa = event.optJSONArray("symbols")
                repoMapFiles = if (fa == null) emptyList() else (0 until fa.length()).mapNotNull { fa.optJSONObject(it) }
                repoMapSymbols = if (sa == null) emptyList() else (0 until sa.length()).mapNotNull { sa.optJSONObject(it) }
                showRepoMap = true
            }
            "checkpoint_preview_result" -> {
                checkpointSafe = event.optBoolean("safe")
                val conflicts = event.optJSONArray("conflicts")
                checkpointConflicts = if (conflicts == null) emptyList() else (0 until conflicts.length()).map { conflicts.optString(it) }
                val error = event.optString("error").takeIf { it.isNotBlank() && it != "null" }
                if (error != null) toast = error
                showCheckpointPreview = true
            }
            "checkpoint_restore_result" -> {
                val restored = event.optBoolean("restored")
                showCheckpointPreview = false
                toast = if (restored) "Undo completed. Original file content restored." else event.optString("error", "Undo blocked because the file changed.")
                if (restored) {
                    editorCheckpointId = null
                    editorPath?.let { bridge.send(BridgeProtocol.line("file_read", "path" to it)) }
                    bridge.send(BridgeProtocol.line("files_list", "path" to filesPath))
                }
            }
            "git_status" -> gitOutput = if (event.optInt("code") == 0) event.optString("stdout").ifBlank { "Working tree clean." } else event.optString("stderr").ifBlank { "Not a Git repository or Git could not run." }
        }
    }

    fun connectBridge() {
        if (connecting || connected) return
        if (!bridge.isTermuxInstalled()) {
            status = "Termux is not installed"
            toast = "Install Termux first. No Linux distro is required."
            return
        }
        connecting = true
        status = "Starting local bridge…"
        val startResult = bridge.startRuntime()
        if (startResult.isFailure) {
            connecting = false
            status = startResult.exceptionOrNull()?.message ?: "Could not start Termux service"
            return
        }
        scope.launch {
            delay(900)
            var attempt = 0
            var lastError = "connection closed"
            while (attempt < 5) {
                val result = bridge.connect { raw ->
                    scope.launch(Dispatchers.Main) { processBridgeEvent(raw) }
                }
                connected = false
                isRunning = false
                lastError = result.exceptionOrNull()?.message ?: "connection closed"
                attempt += 1
                if (attempt >= 5) break
                connecting = true
                status = "Bridge disconnected · retry ${attempt}/4"
                delay((1000L shl (attempt - 1)).coerceAtMost(8000L))
            }
            connecting = false
            status = "Bridge disconnected: $lastError"
        }
    }

    fun startOrResume(prompt: String?, session: ChatSession?, fork: Boolean = false) {
        if (!connected) {
            toast = "Connect to Termux first."
            return
        }
        if (isRunning) {
            if (!prompt.isNullOrBlank()) {
                bridge.send(BridgeProtocol.userMessage(prompt, currentSessionId))
                addEntry("user", prompt)
            }
            return
        }
        if (session != null) {
            currentSession = session
            currentSessionId = session.id
            cwd = session.cwd
            entries.clear(); entries.addAll(chatStore.load(session.id))
        }
        val title = session?.title ?: prompt?.take(80) ?: "New chat"
        val start = JSONObject().apply {
            put("type", "start")
            put("cwd", session?.cwd ?: cwd)
            put("title", title)
            put("provider", provider)
            put("apiKey", apiKey)
            put("baseUrl", baseUrl)
            put("model", model)
            put("effort", effort)
            if (session != null) put("sessionId", session.id)
            if (fork) put("fork", true)
        }
        pendingPrompt = prompt
        if (!prompt.isNullOrBlank()) addEntry("user", prompt)
        bridge.send(start.toString() + "\n")
        if (session != null && prompt.isNullOrBlank()) status = "Restoring saved session…"
    }

    fun sendGitAction(action: String, value: String = "", confirmed: Boolean = false) {
        val request = JSONObject().apply {
            put("type", "git_action")
            put("action", action)
            put("cwd", cwd)
            put("confirmed", confirmed)
            if (action == "commit") put("message", value)
            else if (value.isNotBlank()) put("name", value)
        }
        bridge.send(request.toString() + "\n")
    }

    LaunchedEffect(Unit) {
        connectBridge()
        val mostRecent = sessions.firstOrNull()
        if (mostRecent != null) {
            val wasRunning = runtimePrefs.getBoolean("wasRunning", false)
            val restored = if (wasRunning) mostRecent.copy(interrupted = true) else mostRecent
            currentSession = restored
            currentSessionId = restored.id
            cwd = restored.cwd
            if (wasRunning) {
                sessionStore.save(restored)
                sessions[0] = restored
                runtimePrefs.edit().putBoolean("wasRunning", false).apply()
            }
            entries.addAll(chatStore.load(mostRecent.id))
        }
    }

    LaunchedEffect(page, connected) {
        if (connected && page == Page.FILES && files.isEmpty()) {
            bridge.send(JSONObject().put("type", "files_list").put("path", filesPath).toString() + "\n")
        }
        if (connected && page == Page.GIT) {
            sendGitAction("status")
            sendGitAction("branches")
            sendGitAction("diff")
        }
    }

    MaterialTheme(colorScheme = darkColorScheme(
        primary = Color(0xFFB8A3FF), secondary = Color(0xFF8FD7C7),
        background = Color(0xFF101116), surface = Color(0xFF191B23),
        onBackground = Color(0xFFF1F0F7), onSurface = Color(0xFFF1F0F7)
    )) {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = {
                        Column {
                            Text("Maomao", fontWeight = FontWeight.Bold)
                            Text(status, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    },
                    actions = {
                        if (page == Page.CHAT) {
                            if (!isRunning) IconButton(onClick = {
                                currentSession = null
                                currentSessionId = null
                                entries.clear()
                                pendingPrompt = null
                                lastSessionWasOom = false
                                status = "New chat · ready"
                            }) { Icon(Icons.Default.Add, "New chat") }
                            IconButton(onClick = { showHistory = true }) { Icon(Icons.Default.History, "Chat history") }
                        }
                        IconButton(onClick = { connectBridge() }, enabled = !connecting && !connected) { Icon(Icons.Default.Refresh, "Reconnect") }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface)
                )
            },
            bottomBar = {
                NavigationBar(containerColor = MaterialTheme.colorScheme.surface) {
                    Page.entries.forEach { item ->
                        val icon = when (item) {
                            Page.CHAT -> Icons.Default.ChatBubbleOutline
                            Page.FILES -> Icons.Default.FolderOpen
                            Page.GIT -> Icons.Default.AccountTree
                            Page.TERMINAL -> Icons.Default.Terminal
                            Page.SETTINGS -> Icons.Default.Settings
                        }
                        NavigationBarItem(
                            selected = page == item,
                            onClick = { page = item },
                            icon = { Icon(icon, contentDescription = item.label) },
                            label = { Text(item.label, fontSize = 10.sp) }
                        )
                    }
                }
            },
            snackbarHost = { SnackbarHost(remember { SnackbarHostState() }) }
        ) { padding ->
            Box(Modifier.fillMaxSize().padding(padding).background(MaterialTheme.colorScheme.background)) {
                when (page) {
                    Page.CHAT -> ChatScreen(
                        entries = entries, input = input, onInput = { input = it },
                        onSend = {
                            val message = input.trim()
                            if (memory.ratio >= 0.88) {
                                toast = "New prompts are paused at critical memory usage. Stop or wait for memory to fall."
                            } else if (message.isNotBlank()) {
                                input = ""
                                if (currentSessionId == null && !isRunning) startOrResume(message, null)
                                else startOrResume(message, currentSession, false)
                            }
                        },
                        isRunning = isRunning, connected = connected, memory = memory,
                        memoryWarning = memoryWarning, permission = permission,
                        onPermission = { allow ->
                            permission?.let { req ->
                                bridge.send(BridgeProtocol.controlResponse(req.requestId, allow, req.input))
                                permission = null
                                addEntry("system", if (allow) "Approved: ${req.toolName}" else "Denied: ${req.toolName}")
                            }
                        },
                        resumeSession = currentSession?.takeIf { it.interrupted },
                        onResume = { session -> startOrResume(null, session) },
                        onStop = { bridge.send(BridgeProtocol.line("stop")) }
                    )
                    Page.FILES -> FilesScreen(
                        path = filesPath, entries = files, error = fileError,
                        onLoad = { path -> bridge.send(BridgeProtocol.line("files_list", "path" to path)) },
                        onOpen = { name, isDirectory ->
                            if (isDirectory) {
                                val next = if (filesPath.endsWith('/')) filesPath + name else "$filesPath/$name"
                                bridge.send(BridgeProtocol.line("files_list", "path" to next))
                            }
                        },
                        onSelectFile = { name ->
                            val full = if (filesPath.endsWith('/')) filesPath + name else "$filesPath/$name"
                            bridge.send(BridgeProtocol.line("file_read", "path" to full))
                        }
                    )
                    Page.GIT -> GitScreen(
                        cwd = cwd, output = gitOutput, currentBranch = currentBranch, branches = gitBranches,
                        diffText = diffText, commitMessage = commitMessage, onCommitMessage = { commitMessage = it },
                        onRefresh = { sendGitAction("status"); sendGitAction("branches") },
                        onDiff = { sendGitAction("diff") },
                        onCreateBranch = { name -> sendGitAction("create_branch", name) },
                        onSwitchBranch = { name -> pendingGitAction = "switch_branch"; pendingGitValue = name },
                        onCommit = { pendingGitAction = "commit"; pendingGitValue = commitMessage },
                        onFetch = { sendGitAction("fetch") },
                        onPull = { pendingGitAction = "pull"; pendingGitValue = "" },
                        onPush = { pendingGitAction = "push"; pendingGitValue = "" },
                        onRepositoryMap = { bridge.send(BridgeProtocol.line("repo_map", "cwd" to cwd)) }
                    )
                    Page.TERMINAL -> TerminalScreen(
                        command = terminalCommand, output = terminalOutput, blocked = memory.ratio >= 0.88,
                        onCommand = { terminalCommand = it },
                        onRun = {
                            if (terminalCommand.isNotBlank()) {
                                bridge.send(JSONObject().apply {
                                    put("type", "terminal"); put("id", UUID.randomUUID().toString())
                                    put("command", terminalCommand); put("cwd", cwd)
                                }.toString() + "\n")
                            }
                        }
                    )
                    Page.SETTINGS -> SettingsScreen(
                        cwd = cwd, provider = provider, apiKey = apiKey, baseUrl = baseUrl,
                        model = model, effort = effort,
                        connected = connected, termuxInstalled = bridge.isTermuxInstalled(),
                        onCwd = { cwd = it },
                        onProvider = { provider = it; runtimePrefs.edit().putString("provider", it).apply() },
                        onApiKey = { apiKey = it; ApiKeyVault.put(context, it) },
                        onBaseUrl = { baseUrl = it; runtimePrefs.edit().putString("baseUrl", it).apply() },
                        onModel = { model = it; runtimePrefs.edit().putString("model", it).apply() },
                        onEffort = { effort = it; runtimePrefs.edit().putString("effort", it).apply() },
                        onReconnect = { connectBridge() }
                    )
                }
            }
        }

        if (showFileEditor && editorPath != null) {
            FileEditorDialog(
                path = editorPath!!, content = editorText, error = editorError,
                canUndo = editorCheckpointId != null,
                onContent = { editorText = it },
                onSave = {
                    bridge.send(BridgeProtocol.line(
                        "file_write", "path" to (editorPath ?: ""), "content" to editorText, "expectedHash" to editorHash
                    ))
                },
                onUndo = {
                    editorCheckpointId?.let { id ->
                        bridge.send(BridgeProtocol.line("checkpoint_preview", "checkpointId" to id))
                    }
                },
                onDismiss = { showFileEditor = false }
            )
        }
        if (showCheckpointPreview) {
            AlertDialog(
                onDismissRequest = { showCheckpointPreview = false },
                title = { Text(if (checkpointSafe) "Undo last save?" else "Undo blocked") },
                text = {
                    Text(if (checkpointSafe) {
                        "The file still matches the saved version. Maomao can safely restore the exact pre-save bytes."
                    } else {
                        "The file has changed since the checkpoint or the checkpoint is invalid. Nothing will be overwritten. Conflicts: " + checkpointConflicts.joinToString()
                    })
                },
                confirmButton = {
                    if (checkpointSafe) TextButton(onClick = {
                        editorCheckpointId?.let { bridge.send(BridgeProtocol.line("checkpoint_restore", "checkpointId" to it)) }
                    }) { Text("Undo save") }
                    else TextButton(onClick = { showCheckpointPreview = false }) { Text("Close") }
                },
                dismissButton = { if (checkpointSafe) TextButton(onClick = { showCheckpointPreview = false }) { Text("Cancel") } }
            )
        }
        if (pendingGitAction != null) {
            val action = pendingGitAction!!
            val description = when (action) {
                "commit" -> "This stages all current changes and creates a commit with message: " + pendingGitValue
                "pull" -> "This runs git pull --ff-only. The operation is blocked if the working tree is dirty."
                "push" -> "This pushes the current branch to its configured remote."
                else -> "This switches the working tree to branch " + pendingGitValue + ". Dirty changes block the operation."
            }
            AlertDialog(
                onDismissRequest = { pendingGitAction = null },
                title = { Text("Confirm Git action") },
                text = { Text(description) },
                confirmButton = { TextButton(onClick = {
                    sendGitAction(action, pendingGitValue, true)
                    pendingGitAction = null
                }) { Text("Confirm") } },
                dismissButton = { TextButton(onClick = { pendingGitAction = null }) { Text("Cancel") } }
            )
        }
        if (showRepoMap) {
            AlertDialog(
                onDismissRequest = { showRepoMap = false },
                title = { Text("Repository Map · $repoMapCount files") },
                text = {
                    Column(Modifier.fillMaxWidth().heightIn(max = 460.dp).verticalScroll(rememberScrollState())) {
                        Text("Key symbols", fontWeight = FontWeight.SemiBold)
                        repoMapSymbols.take(120).forEach { item ->
                            Text(item.optString("name") + " · " + item.optString("path") + ":" + item.optInt("line"), fontSize = 11.sp)
                        }
                        Spacer(Modifier.height(8.dp))
                        Text("Source files", fontWeight = FontWeight.SemiBold)
                        repoMapFiles.take(100).forEach { item ->
                            Text(item.optString("path") + " · " + formatBytes(item.optLong("bytes")), fontSize = 11.sp)
                        }
                    }
                },
                confirmButton = { TextButton(onClick = { showRepoMap = false }) { Text("Close") } }
            )
        }
        if (showHistory) {
            Dialog(onDismissRequest = { showHistory = false }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
                Surface(Modifier.fillMaxWidth().fillMaxHeight(.88f).padding(14.dp), shape = RoundedCornerShape(24.dp), color = MaterialTheme.colorScheme.surface) {
                    Column(Modifier.padding(18.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("Chat history", fontSize = 21.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                            TextButton(onClick = { showHistory = false }) { Text("Close") }
                        }
                        Text("Tap a saved chat to resume it. Fork keeps the original transcript unchanged.", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
                        Spacer(Modifier.height(12.dp))
                        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            items(sessions, key = { it.id }) { session ->
                                Surface(shape = RoundedCornerShape(14.dp), color = MaterialTheme.colorScheme.background, modifier = Modifier.fillMaxWidth()) {
                                    Column(Modifier.padding(12.dp)) {
                                        Text(session.title, fontWeight = FontWeight.SemiBold)
                                        Text("${session.cwd}\n${java.text.DateFormat.getDateTimeInstance().format(java.util.Date(session.updatedAt))}${if (session.interrupted) " · interrupted" else ""}", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                        Row {
                                            TextButton(onClick = {
                                                showHistory = false
                                                startOrResume(null, session)
                                            }) { Text("Resume") }
                                            TextButton(onClick = {
                                                showHistory = false
                                                startOrResume(null, session, true)
                                            }) { Text("Fork chat") }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        if (showCrashDialog) {
            AlertDialog(
                onDismissRequest = { showCrashDialog = false },
                title = { Text("Session stopped unexpectedly") },
                text = { Text(if (lastSessionWasOom) "XINCLI exited in a way commonly associated with an out-of-memory crash. Your saved transcript is still available. Resume loads the saved session; it does not automatically repeat the last tool operation." else "The XINCLI process stopped. The latest saved transcript is available in Chat history.") },
                confirmButton = { TextButton(onClick = {
                    showCrashDialog = false
                    currentSession?.let { startOrResume(null, it) }
                }) { Text("Resume session") } },
                dismissButton = { TextButton(onClick = { showCrashDialog = false }) { Text("Later") } }
            )
        }
    }
}

private fun handleCliEvent(
    event: JSONObject,
    addEntry: (String, String) -> Unit,
    onPermission: (PermissionRequest) -> Unit
) {
    val type = event.optString("type")
    when (type) {
        "control_request" -> {
            val request = event.optJSONObject("request") ?: return
            if (request.optString("subtype") == "can_use_tool") {
                onPermission(PermissionRequest(
                    requestId = event.optString("request_id"),
                    toolName = request.optString("tool_name", "Tool"),
                    input = request.optJSONObject("input") ?: JSONObject(),
                    description = request.optString("description", "XINCLI wants to run this tool.")
                ))
            }
        }
        "assistant" -> {
            val message = event.optJSONObject("message") ?: return
            val content = message.optJSONArray("content")
            val text = buildString {
                if (content != null) for (i in 0 until content.length()) {
                    val part = content.optJSONObject(i) ?: continue
                    when (part.optString("type")) {
                        "text" -> append(part.optString("text"))
                        "tool_use" -> append("\n[Tool] ").append(part.optString("name"))
                    }
                }
            }.trim()
            if (text.isNotBlank()) addEntry("assistant", text)
        }
        "system" -> {
            val subtype = event.optString("subtype")
            if (subtype == "init") {
                val sid = event.optString("session_id")
                if (sid.isNotBlank()) addEntry("system", "Session ready · ${sid.take(8)}")
            } else if (subtype.contains("hook", true)) {
                addEntry("activity", event.optString("hook_name", "Hook activity"))
            }
        }
        "result" -> {
            val result = event.optString("result")
            if (result.isNotBlank() && result != "null") addEntry("assistant", result)
        }
    }
}

@Composable
private fun ChatScreen(
    entries: SnapshotStateList<ChatEntry>, input: String, onInput: (String) -> Unit,
    onSend: () -> Unit, isRunning: Boolean, connected: Boolean, memory: MemorySnapshot,
    memoryWarning: String?, permission: PermissionRequest?, onPermission: (Boolean) -> Unit,
    resumeSession: ChatSession?, onResume: (ChatSession) -> Unit, onStop: () -> Unit
) {
    Column(Modifier.fillMaxSize().padding(horizontal = 14.dp)) {
        if (!connected) StatusBanner("Termux bridge is disconnected. Tap the reconnect icon after completing Termux setup.", warning = true)
        if (memory.heapLimit > 0) {
            Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("XINCLI memory", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.width(8.dp))
                LinearProgressIndicator(progress = { memory.ratio.toFloat().coerceIn(0f, 1f) }, modifier = Modifier.weight(1f).height(6.dp))
                Spacer(Modifier.width(8.dp))
                Text("${memory.percent}%", fontSize = 11.sp)
            }
        }
        if (memoryWarning != null) StatusBanner(memoryWarning, warning = true)
        if (resumeSession != null && !isRunning) {
            Card(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
                Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Session interrupted", fontWeight = FontWeight.SemiBold)
                        Text("Saved transcript can be resumed.", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Button(onClick = { onResume(resumeSession) }) { Text("Resume") }
                }
            }
        }
        LazyColumn(Modifier.weight(1f).fillMaxWidth(), reverseLayout = false, verticalArrangement = Arrangement.spacedBy(9.dp), contentPadding = PaddingValues(vertical = 12.dp)) {
            items(entries, key = { it.id }) { entry ->
                MessageBubble(entry)
            }
        }
        if (permission != null) {
            Card(Modifier.fillMaxWidth().padding(bottom = 8.dp), colors = CardDefaults.cardColors(containerColor = Color(0xFF30271D))) {
                Column(Modifier.padding(12.dp)) {
                    Text("Permission required · ${permission.toolName}", fontWeight = FontWeight.Bold, color = Color(0xFFFFD6A2))
                    Text(permission.description.ifBlank { "XINCLI is requesting permission to use this tool." }, fontSize = 12.sp, color = Color(0xFFFFE7C9))
                    Text(permission.input.toString().take(500), fontSize = 10.sp, color = Color(0xFFFFE7C9))
                    Row {
                        TextButton(onClick = { onPermission(false) }) { Text("Deny", color = Color(0xFFFFA5A5)) }
                        Spacer(Modifier.weight(1f))
                        Button(onClick = { onPermission(true) }) { Text("Allow once") }
                    }
                }
            }
        }
        Row(Modifier.fillMaxWidth().padding(bottom = 10.dp), verticalAlignment = Alignment.Bottom) {
            OutlinedTextField(
                value = input, onValueChange = onInput, modifier = Modifier.weight(1f),
                placeholder = { Text("Message Maomao…") }, shape = RoundedCornerShape(20.dp),
                maxLines = 5, keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                keyboardActions = KeyboardActions(onSend = { onSend() })
            )
            Spacer(Modifier.width(8.dp))
            if (isRunning) {
                FilledTonalButton(onClick = onStop, modifier = Modifier.height(52.dp)) { Text("Stop") }
            } else {
                IconButton(onClick = onSend, enabled = connected && input.isNotBlank(), modifier = Modifier.size(52.dp).background(MaterialTheme.colorScheme.primary, CircleShape)) {
                    Icon(Icons.AutoMirrored.Filled.Send, "Send", tint = Color(0xFF171522))
                }
            }
        }
    }
}

@Composable
private fun MessageBubble(entry: ChatEntry) {
    val user = entry.role == "user"
    val system = entry.role == "system" || entry.role == "activity"
    Row(Modifier.fillMaxWidth(), horizontalArrangement = if (user) Arrangement.End else Arrangement.Start) {
        Surface(
            color = when { user -> Color(0xFF39304E); system -> Color(0xFF242630); else -> Color(0xFF1C2029) },
            shape = RoundedCornerShape(16.dp),
            modifier = Modifier.widthIn(max = 340.dp)
        ) {
            Column(Modifier.padding(horizontal = 13.dp, vertical = 10.dp)) {
                if (!user) Text(if (system) "MAOMAO · SYSTEM" else "XINCLI", fontSize = 9.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.secondary)
                Text(entry.text, fontSize = 14.sp, lineHeight = 20.sp, color = MaterialTheme.colorScheme.onSurface)
            }
        }
    }
}

@Composable
private fun FilesScreen(
    path: String, entries: List<JSONObject>, error: String?,
    onLoad: (String) -> Unit, onOpen: (String, Boolean) -> Unit, onSelectFile: (String) -> Unit
) {
    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Text("Workspace files", fontSize = 20.sp, fontWeight = FontWeight.Bold)
        Text(path, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row {
            TextButton(onClick = { onLoad(path) }) { Text("Refresh") }
            TextButton(onClick = { onLoad(SessionStore.DEFAULT_CWD) }) { Text("Termux home") }
            TextButton(onClick = { onLoad(java.io.File(path).parent ?: SessionStore.DEFAULT_CWD) }) { Text("Up") }
        }
        Text("Tap a folder to browse or a text file to open the editor.", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (error != null) StatusBanner(error, true)
        LazyColumn(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            items(entries, key = { it.optString("name") }) { item ->
                val name = item.optString("name")
                val dir = item.optBoolean("directory")
                Surface(
                    Modifier.fillMaxWidth().clickable { if (dir) onOpen(name, true) else onSelectFile(name) },
                    color = MaterialTheme.colorScheme.surface, shape = RoundedCornerShape(10.dp)
                ) {
                    Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(if (dir) Icons.Default.FolderOpen else Icons.Default.Terminal, null, tint = MaterialTheme.colorScheme.secondary)
                        Spacer(Modifier.width(10.dp))
                        Text(name, modifier = Modifier.weight(1f), fontWeight = if (dir) FontWeight.SemiBold else FontWeight.Normal)
                        if (!dir) Text(formatBytes(item.optLong("size")), fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
}

@Composable
private fun FileEditorDialog(
    path: String, content: String, error: String?, canUndo: Boolean,
    onContent: (String) -> Unit, onSave: () -> Unit, onUndo: () -> Unit, onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Column {
                Text(java.io.File(path).name, fontSize = 18.sp)
                Text(path, fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
        text = {
            Column(Modifier.fillMaxWidth()) {
                if (error != null) StatusBanner(error, true)
                OutlinedTextField(
                    value = content, onValueChange = onContent, modifier = Modifier.fillMaxWidth().heightIn(min = 280.dp, max = 520.dp),
                    minLines = 14, maxLines = 24, textStyle = androidx.compose.ui.text.TextStyle(
                        fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace, fontSize = 12.sp, lineHeight = 17.sp
                    ),
                    placeholder = { Text("File content") }
                )
                Text("1 MiB text editing limit · SHA-256 conflict check · automatic pre-save checkpoint",
                    fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 5.dp))
            }
        },
        confirmButton = { TextButton(onClick = onSave) { Text("Save") } },
        dismissButton = {
            Row {
                if (canUndo) TextButton(onClick = onUndo) { Text("Undo last save") }
                TextButton(onClick = onDismiss) { Text("Close") }
            }
        }
    )
}

@Composable
private fun GitScreen(
    cwd: String, output: String, currentBranch: String, branches: List<String>, diffText: String,
    commitMessage: String, onCommitMessage: (String) -> Unit,
    onRefresh: () -> Unit, onDiff: () -> Unit, onCreateBranch: (String) -> Unit,
    onSwitchBranch: (String) -> Unit, onCommit: () -> Unit, onFetch: () -> Unit,
    onPull: () -> Unit, onPush: () -> Unit, onRepositoryMap: () -> Unit
) {
    var newBranch by remember { mutableStateOf("") }
    var showBranches by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize().padding(16.dp).verticalScroll(rememberScrollState())) {
        Text("Git workspace", fontSize = 20.sp, fontWeight = FontWeight.Bold)
        Text(cwd, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Branch: " + currentBranch.ifBlank { "(detached / unknown)" }, modifier = Modifier.weight(1f), fontWeight = FontWeight.SemiBold)
            TextButton(onClick = { showBranches = true }) { Text("Switch") }
            DropdownMenu(expanded = showBranches, onDismissRequest = { showBranches = false }) {
                branches.forEach { branch ->
                    DropdownMenuItem(text = { Text(branch) }, onClick = {
                        showBranches = false
                        if (branch != currentBranch) onSwitchBranch(branch)
                    })
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            OutlinedTextField(newBranch, { newBranch = it }, Modifier.weight(1f), label = { Text("New branch") }, singleLine = true)
            Button(onClick = { val name = newBranch.trim(); if (name.isNotEmpty()) { onCreateBranch(name); newBranch = "" } }, enabled = newBranch.isNotBlank()) { Text("Create") }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            OutlinedTextField(commitMessage, onCommitMessage, Modifier.weight(1f), label = { Text("Commit message") }, singleLine = true)
            Button(onClick = onCommit, enabled = commitMessage.isNotBlank()) { Text("Commit all") }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
            OutlinedButton(onClick = onRefresh) { Text("Status") }
            OutlinedButton(onClick = onDiff) { Text("Diff") }
            OutlinedButton(onClick = onRepositoryMap) { Text("Repo map") }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
            OutlinedButton(onClick = onFetch) { Text("Fetch") }
            OutlinedButton(onClick = onPull) { Text("Pull") }
            OutlinedButton(onClick = onPush) { Text("Push") }
        }
        HorizontalDivider(Modifier.padding(vertical = 8.dp))
        Text("Git output", fontWeight = FontWeight.SemiBold)
        Surface(Modifier.fillMaxWidth(), color = MaterialTheme.colorScheme.surface, shape = RoundedCornerShape(14.dp)) {
            Text(output, Modifier.padding(12.dp), fontSize = 12.sp, lineHeight = 17.sp)
        }
        Spacer(Modifier.height(10.dp))
        Text("Unstaged diff", fontWeight = FontWeight.SemiBold)
        Surface(Modifier.fillMaxWidth(), color = Color(0xFF090B10), shape = RoundedCornerShape(14.dp)) {
            Text(diffText.ifBlank { "Tap Diff to inspect unstaged changes." },
                Modifier.padding(12.dp), color = Color(0xFFD6E1F2), fontSize = 10.sp, lineHeight = 14.sp,
                fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace)
        }
        Text("Safety: switch and pull refuse dirty working trees. Commit stages all changes. Push, pull, switch and commit require confirmation.",
            fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 8.dp))
    }
}

@Composable
private fun TerminalScreen(command: String, output: String, blocked: Boolean, onCommand: (String) -> Unit, onRun: () -> Unit) {
    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Text("Termux terminal", fontSize = 20.sp, fontWeight = FontWeight.Bold)
        Text(if (blocked) "Terminal commands are paused until critical heap pressure falls." else "Commands run in your Termux user environment.", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row(Modifier.fillMaxWidth().padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(command, onCommand, Modifier.weight(1f), placeholder = { Text("git status, ls, …") }, singleLine = true)
            Spacer(Modifier.width(8.dp))
            Button(onClick = onRun, enabled = !blocked) { Text(if (blocked) "Paused" else "Run") }
        }
        Surface(Modifier.fillMaxWidth().weight(1f), color = Color(0xFF090B10), shape = RoundedCornerShape(14.dp)) {
            Text(output, Modifier.padding(12.dp), color = Color(0xFFB8E6C2), fontSize = 12.sp, lineHeight = 17.sp)
        }
    }
}

@Composable
private fun SettingsScreen(
    cwd: String, provider: String, apiKey: String, baseUrl: String, model: String, effort: String,
    connected: Boolean, termuxInstalled: Boolean,
    onCwd: (String) -> Unit, onProvider: (String) -> Unit, onApiKey: (String) -> Unit,
    onBaseUrl: (String) -> Unit, onModel: (String) -> Unit, onEffort: (String) -> Unit,
    onReconnect: () -> Unit
) {
    Column(Modifier.fillMaxSize().padding(16.dp).verticalScroll(rememberScrollState())) {
        Text("Settings", fontSize = 20.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(12.dp))
        Text("AI Models & Providers", fontWeight = FontWeight.SemiBold)
        Text("Choose the API provider used when a new XINCLI session starts.", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 8.dp)) {
            listOf("anthropic" to "Anthropic", "openai" to "OpenAI", "openai-compatible" to "OpenAI-compatible").forEach { (id, label) ->
                FilterChip(selected = provider == id, onClick = { onProvider(id) }, label = { Text(label) }, modifier = Modifier.padding(end = 5.dp))
            }
        }
        OutlinedTextField(
            value = apiKey, onValueChange = onApiKey, modifier = Modifier.fillMaxWidth(),
            label = { Text("API Key") }, placeholder = { Text("Paste provider API key") },
            singleLine = true, visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation()
        )
        OutlinedTextField(
            value = baseUrl, onValueChange = onBaseUrl, modifier = Modifier.fillMaxWidth(),
            label = { Text("Base URL (optional)") },
            placeholder = { Text(if (provider == "anthropic") "https://api.anthropic.com" else "https://api.openai.com/v1") },
            singleLine = true
        )
        OutlinedTextField(
            value = model, onValueChange = onModel, modifier = Modifier.fillMaxWidth(),
            label = { Text("Model ID (exact provider model name)") },
            placeholder = { Text("Enter the model ID from your API provider") }, singleLine = true
        )
        Text("API key is stored encrypted on this device. Provider credentials are passed to XINCLI only when a new session starts.", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 6.dp))
        Spacer(Modifier.height(8.dp))
        Text("Effort", fontWeight = FontWeight.SemiBold)
        Row(verticalAlignment = Alignment.CenterVertically) {
            listOf("low", "medium", "high", "max").forEach { choice ->
                FilterChip(selected = effort == choice, onClick = { onEffort(choice) }, label = { Text(choice) }, modifier = Modifier.padding(end = 4.dp))
            }
        }
        HorizontalDivider(Modifier.padding(vertical = 10.dp))
        Text("Runtime", fontWeight = FontWeight.SemiBold)
        StatusBanner(if (termuxInstalled) "Termux installed" else "Termux not detected", !termuxInstalled)
        StatusBanner(if (connected) "Local bridge connected" else "Bridge disconnected", !connected)
        OutlinedTextField(cwd, onCwd, Modifier.fillMaxWidth(), label = { Text("Working directory") }, singleLine = true)
        Button(onClick = onReconnect, modifier = Modifier.padding(vertical = 8.dp)) { Text("Reconnect bridge") }
        HorizontalDivider(Modifier.padding(vertical = 10.dp))
        Text("Permissions & safety", fontWeight = FontWeight.SemiBold)
        Text("Tool access stays approval-based. Maomao never adds --dangerously-skip-permissions automatically.", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text("One-time Termux setup: set allow-external-apps=true in ~/.termux/termux.properties and grant Maomao the Run commands in Termux permission in Android App Info.", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 8.dp))
    }
}

@Composable
private fun StatusBanner(text: String, warning: Boolean) {
    Surface(Modifier.fillMaxWidth().padding(vertical = 4.dp), color = if (warning) Color(0xFF332A20) else Color(0xFF1E2C29), shape = RoundedCornerShape(12.dp)) {
        Text(text, Modifier.padding(10.dp), color = if (warning) Color(0xFFFFD9A7) else Color(0xFFB5E6D5), fontSize = 11.sp)
    }
}

private fun formatBytes(value: Long): String = when {
    value >= 1024L * 1024L -> "%.1f MB".format(value / 1024.0 / 1024.0)
    value >= 1024L -> "%.1f KB".format(value / 1024.0)
    else -> "$value B"
}
private object ApiKeyVault {
    private const val PREFS = "maomao_secrets"
    private const val KEY_ALIAS = "maomao_api_key_v1"

    private fun secretKey(): java.security.Key {
        val store = java.security.KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        store.getKey(KEY_ALIAS, null)?.let { return it }
        val generator = javax.crypto.KeyGenerator.getInstance(
            android.security.keystore.KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore"
        )
        val spec = android.security.keystore.KeyGenParameterSpec.Builder(
            KEY_ALIAS,
            android.security.keystore.KeyProperties.PURPOSE_ENCRYPT or android.security.keystore.KeyProperties.PURPOSE_DECRYPT
        ).setBlockModes(android.security.keystore.KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(android.security.keystore.KeyProperties.ENCRYPTION_PADDING_NONE)
            .setRandomizedEncryptionRequired(true)
            .build()
        generator.init(spec)
        return generator.generateKey()
    }

    fun put(context: android.content.Context, value: String) {
        val prefs = context.getSharedPreferences(PREFS, android.content.Context.MODE_PRIVATE)
        if (value.isBlank()) {
            prefs.edit().remove("api_key").apply()
            return
        }
        runCatching {
            val cipher = javax.crypto.Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(javax.crypto.Cipher.ENCRYPT_MODE, secretKey())
            val encrypted = cipher.doFinal(value.toByteArray(Charsets.UTF_8))
            val combined = cipher.iv + encrypted
            prefs.edit().putString("api_key", android.util.Base64.encodeToString(combined, android.util.Base64.NO_WRAP)).apply()
        }
    }

    fun get(context: android.content.Context): String {
        val encoded = context.getSharedPreferences(PREFS, android.content.Context.MODE_PRIVATE).getString("api_key", null) ?: return ""
        return runCatching {
            val combined = android.util.Base64.decode(encoded, android.util.Base64.NO_WRAP)
            val iv = combined.copyOfRange(0, 12)
            val encrypted = combined.copyOfRange(12, combined.size)
            val cipher = javax.crypto.Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(javax.crypto.Cipher.DECRYPT_MODE, secretKey(), javax.crypto.spec.GCMParameterSpec(128, iv))
            String(cipher.doFinal(encrypted), Charsets.UTF_8)
        }.getOrDefault("")
    }
}
