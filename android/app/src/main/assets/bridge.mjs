import net from 'node:net';
import { createHash } from 'node:crypto';
import fs from 'node:fs';
import path from 'node:path';
import { spawn, spawnSync } from 'node:child_process';

const [portArg, metricsPortArg, token, cliPath, home] = process.argv.slice(2);
const PORT = Number(portArg || 37645);
const METRICS_PORT = Number(metricsPortArg || 37646);
const clients = new Set();
const pendingToolRequests = new Map();
const agentCheckpoints = new Map();
const history = [];
const HISTORY_LIMIT = 180;
let child = null;
let currentSessionId = null;
let currentCwd = home;
let currentTitle = 'New chat';
let lastExit = null;
let currentMemoryLevel = 'normal';

function publish(event) {
  const line = JSON.stringify(event);
  history.push(line);
  if (history.length > HISTORY_LIMIT) history.shift();
  for (const client of clients) {
    if (!client.destroyed) client.write(line + '\n');
  }
}

function parseLines(socket, handler) {
  let buffer = '';
  socket.on('data', chunk => {
    buffer += chunk.toString('utf8');
    let index;
    while ((index = buffer.indexOf('\n')) >= 0) {
      const line = buffer.slice(0, index).trim();
      buffer = buffer.slice(index + 1);
      if (!line) continue;
      try { handler(JSON.parse(line)); }
      catch (error) { publish({ type: 'bridge_error', message: `Invalid bridge message: ${error.message}` }); }
    }
    if (buffer.length > 1024 * 1024) {
      publish({ type: 'bridge_error', message: 'Inbound message exceeded 1 MiB and was rejected.' });
      buffer = '';
    }
  });
}

function attachLineStream(stream, streamName, processRef) {
  let buffer = '';
  stream.setEncoding('utf8');
  stream.on('data', chunk => {
    buffer += chunk;
    let index;
    while ((index = buffer.indexOf('\n')) >= 0) {
      const line = buffer.slice(0, index);
      buffer = buffer.slice(index + 1);
      forwardCliLine(line, streamName, processRef);
    }
    // Avoid retaining an unbounded partial line. Large output is sent in bounded chunks.
    if (buffer.length > 2 * 1024 * 1024) {
      forwardCliLine(buffer.slice(0, 2 * 1024 * 1024), streamName, processRef, true);
      buffer = buffer.slice(2 * 1024 * 1024);
    }
  });
  stream.on('end', () => {
    if (buffer) forwardCliLine(buffer, streamName, processRef);
  });
}

function forwardCliLine(line, streamName, processRef, partial = false) {
  if (streamName === 'stdout' && !partial) {
    try {
      const parsed = JSON.parse(line);
      const sessionId = parsed.session_id || parsed.sessionId || parsed.message?.session_id;
      if (typeof sessionId === 'string' && sessionId.length > 10) {
        currentSessionId = sessionId;
      }
      if (parsed.type === 'control_request' && parsed.request?.subtype === 'can_use_tool') {
        const request = parsed.request;
        pendingToolRequests.set(String(parsed.request_id), {
          toolName: String(request.tool_name || ''),
          input: request.input || {},
          toolUseId: String(request.tool_use_id || '')
        });
        if (pendingToolRequests.size > 100) pendingToolRequests.delete(pendingToolRequests.keys().next().value);
      }
      if (parsed.type === 'user' && Array.isArray(parsed.message?.content)) {
        for (const block of parsed.message.content) {
          if (block?.type === 'tool_result' && block.tool_use_id) sealAgentCheckpoint(String(block.tool_use_id));
        }
      }
      publish({ type: 'cli_event', stream: streamName, event: parsed, raw: line, sessionId: currentSessionId, title: currentTitle, cwd: currentCwd });
      return;
    } catch { /* Plain text output is still surfaced below. */ }
  }
  publish({ type: 'cli_event', stream: streamName, raw: line, partial, sessionId: currentSessionId, title: currentTitle, cwd: currentCwd });
}

function startCli(message) {
  if (child && child.exitCode === null && !child.killed) {
    publish({ type: 'bridge_status', status: 'already_running', sessionId: currentSessionId, cwd: currentCwd });
    return;
  }
  if (!fs.existsSync(cliPath)) {
    publish({ type: 'bridge_error', message: `XINCLI launcher not found: ${cliPath}. Install XINCLI in Termux first.` });
    return;
  }
  const cwd = typeof message.cwd === 'string' && message.cwd.trim() ? message.cwd : home;
  if (!path.isAbsolute(cwd) || !fs.existsSync(cwd)) {
    publish({ type: 'bridge_error', message: `Working directory does not exist: ${cwd}` });
    return;
  }
  currentCwd = cwd;
  currentTitle = typeof message.title === 'string' ? message.title.slice(0, 120) : 'New chat';
  currentSessionId = typeof message.sessionId === 'string' ? message.sessionId : null;

  const args = ['-p', '--input-format', 'stream-json', '--output-format', 'stream-json', '--verbose', '--replay-user-messages'];
  if (currentSessionId) args.push('--resume', currentSessionId);
  if (message.fork === true && currentSessionId) args.push('--fork-session');
  if (typeof message.model === 'string' && message.model.trim()) args.push('--model', message.model.trim());
  if (['low', 'medium', 'high', 'max'].includes(message.effort)) args.push('--effort', message.effort);
  args.push('--name', currentTitle);

  const provider = String(message.provider || 'anthropic').toLowerCase();
  const apiKey = typeof message.apiKey === 'string' ? message.apiKey.trim() : '';
  const baseUrl = typeof message.baseUrl === 'string' ? message.baseUrl.trim() : '';
  const providerEnv = {};
  if (apiKey) {
    if (provider === 'anthropic') providerEnv.ANTHROPIC_API_KEY = apiKey;
    else if (provider === 'openai') providerEnv.OPENAI_API_KEY = apiKey;
    else if (provider === 'openai-compatible') {
      providerEnv.OPENAI_API_KEY = apiKey;
      if (baseUrl) providerEnv.OPENAI_BASE_URL = baseUrl;
    }
  }
  if (baseUrl && provider === 'anthropic') providerEnv.ANTHROPIC_BASE_URL = baseUrl;
  if (baseUrl && provider === 'openai') providerEnv.OPENAI_BASE_URL = baseUrl;

  const env = {
    ...process.env,
    ...providerEnv,
    HOME: home,
    PREFIX: '/data/data/com.termux/files/usr',
    PATH: `/data/data/com.termux/files/usr/bin:${home}/.local/bin:${process.env.PATH || ''}`,
    MAOMAO_METRICS_PORT: String(METRICS_PORT),
    MAOMAO_BRIDGE_TOKEN: token,
    NODE_OPTIONS: `${process.env.NODE_OPTIONS || ''} --require=${home}/.maomao/memory-guard.cjs`.trim()
  };

  try {
    child = spawn(cliPath, args, { cwd, env, stdio: ['pipe', 'pipe', 'pipe'] });
  } catch (error) {
    child = null;
    publish({ type: 'bridge_error', message: `Unable to launch XINCLI: ${error.message}` });
    return;
  }

  const processRef = child;
  publish({ type: 'process_started', sessionId: currentSessionId, cwd, title: currentTitle, pid: processRef.pid, resumed: Boolean(message.sessionId) });
  attachLineStream(processRef.stdout, 'stdout', processRef);
  attachLineStream(processRef.stderr, 'stderr', processRef);
  processRef.on('error', error => publish({ type: 'process_error', message: error.message, sessionId: currentSessionId }));
  processRef.on('exit', (code, signal) => {
    lastExit = { code, signal, at: Date.now() };
    publish({ type: 'process_exit', code, signal, sessionId: currentSessionId, cwd: currentCwd, oomLikely: code === 134 || signal === 'SIGABRT' });
    if (child === processRef) child = null;
    currentMemoryLevel = 'normal';
  });
}

function writeToCli(message) {
  if (!child || child.exitCode !== null || child.killed) {
    publish({ type: 'bridge_error', message: 'There is no active XINCLI process. Resume the session to continue.' });
    return;
  }
  if (message.type === 'input') {
    if (currentMemoryLevel === 'critical') {
      publish({ type: 'memory_guard_block', level: currentMemoryLevel, action: 'input', message: 'New prompt blocked because V8 heap usage is at the critical threshold. Stop or wait for memory to fall before continuing.' });
      return;
    }
    const line = JSON.stringify({
      type: 'user', session_id: currentSessionId || '',
      message: { role: 'user', content: String(message.content || '') },
      parent_tool_use_id: null
    });
    child.stdin.write(line + '\n');
    return;
  }
  if (message.type === 'control_response' && message.response) {
    const response = message.response?.response;
    const requestId = response?.request_id;
    const allow = response?.behavior === 'allow';
    if (allow && requestId) {
      const prepared = approvedFilesystemTool(requestId);
      if (!prepared.ok) {
        publish({ type: 'agent_checkpoint_error', message: prepared.reason, requestId });
        publish({ type: 'bridge_error', message: prepared.reason });
        return;
      }
    }
    if (requestId) pendingToolRequests.delete(String(requestId));
    child.stdin.write(JSON.stringify(message) + '\n');
  }
}

function runTerminal(message) {
  const command = String(message.command || '').trim();
  const cwd = typeof message.cwd === 'string' && path.isAbsolute(message.cwd) ? message.cwd : currentCwd;
  const id = String(message.id || Date.now());
  if (currentMemoryLevel === 'critical') return publish({ type: 'terminal_result', id, code: 75, stdout: '', stderr: 'Terminal command paused: V8 heap usage is at the critical threshold.' });
  if (!command) return publish({ type: 'terminal_result', id, code: 2, stdout: '', stderr: 'Command is empty.' });
  if (!fs.existsSync(cwd)) return publish({ type: 'terminal_result', id, code: 2, stdout: '', stderr: `Directory not found: ${cwd}` });
  const proc = spawn('/data/data/com.termux/files/usr/bin/sh', ['-lc', command], {
    cwd,
    env: { ...process.env, HOME: home, PREFIX: '/data/data/com.termux/files/usr', PATH: `/data/data/com.termux/files/usr/bin:${home}/.local/bin:${process.env.PATH || ''}` },
    stdio: ['ignore', 'pipe', 'pipe']
  });
  let stdout = '', stderr = '';
  const cap = 256 * 1024;
  proc.stdout.setEncoding('utf8'); proc.stderr.setEncoding('utf8');
  proc.stdout.on('data', chunk => { if (stdout.length < cap) stdout += chunk.slice(0, cap - stdout.length); });
  proc.stderr.on('data', chunk => { if (stderr.length < cap) stderr += chunk.slice(0, cap - stderr.length); });
  proc.on('error', error => publish({ type: 'terminal_result', id, code: 127, stdout, stderr: `${stderr}\n${error.message}` }));
  proc.on('close', code => publish({ type: 'terminal_result', id, code: code ?? -1, stdout, stderr }));
  publish({ type: 'terminal_started', id, cwd, command });
}

function listFiles(message) {
  const dir = typeof message.path === 'string' && path.isAbsolute(message.path) ? message.path : currentCwd;
  try {
    const entries = fs.readdirSync(dir, { withFileTypes: true }).slice(0, 500).map(entry => {
      const full = path.join(dir, entry.name);
      let size = 0, modified = 0;
      try { const stat = fs.statSync(full); size = stat.size; modified = stat.mtimeMs; } catch {}
      return { name: entry.name, directory: entry.isDirectory(), symlink: entry.isSymbolicLink(), size, modified };
    }).sort((a, b) => Number(b.directory) - Number(a.directory) || a.name.localeCompare(b.name));
    publish({ type: 'files_result', path: dir, entries });
  } catch (error) {
    publish({ type: 'files_result', path: dir, entries: [], error: error.message });
  }
}

function gitStatus(message) {
  const cwd = typeof message.cwd === 'string' && path.isAbsolute(message.cwd) ? message.cwd : currentCwd;
  const proc = spawn('/data/data/com.termux/files/usr/bin/git', ['-C', cwd, 'status', '--short', '--branch'], { stdio: ['ignore', 'pipe', 'pipe'] });
  let stdout = '', stderr = '';
  proc.stdout.setEncoding('utf8'); proc.stderr.setEncoding('utf8');
  proc.stdout.on('data', x => { stdout += x; }); proc.stderr.on('data', x => { stderr += x; });
  proc.on('close', code => publish({ type: 'git_status', cwd, code: code ?? -1, stdout, stderr }));
}


const MAX_EDITOR_BYTES = 1024 * 1024;
const MAX_CHECKPOINT_BYTES = 2 * 1024 * 1024;
const CHECKPOINT_DIR = path.join(home, '.maomao', 'checkpoints');
const GIT = '/data/data/com.termux/files/usr/bin/git';

function hashBytes(data) {
  return createHash('sha256').update(data).digest('hex');
}
function isInside(parent, target) {
  const rel = path.relative(parent, target);
  return rel === '' || (rel !== '..' && !rel.startsWith('..' + path.sep) && !path.isAbsolute(rel));
}
function safeWorkspacePath(input, mustExist = true) {
  if (typeof input !== 'string' || !input.trim()) throw new Error('A file path is required.');
  const full = path.resolve(input.startsWith('/') ? input : path.join(currentCwd, input));
  if (!isInside(home, full)) throw new Error('Path is outside the Termux home workspace.');
  if (mustExist) {
    const st = fs.lstatSync(full);
    if (st.isSymbolicLink()) throw new Error('Symbolic links are not opened by the editor.');
    const real = fs.realpathSync(full);
    if (!isInside(home, real)) throw new Error('Resolved path is outside the workspace.');
  } else {
    const parent = fs.realpathSync(path.dirname(full));
    if (!isInside(home, parent)) throw new Error('Parent directory is outside the workspace.');
  }
  return full;
}

function approvedFilesystemTool(requestId) {
  const request = pendingToolRequests.get(String(requestId));
  if (!request) return { ok: false, reason: 'Tool approval request is no longer available.' };
  const name = request.toolName.toLowerCase();
  if (!['write', 'edit', 'multiedit', 'notebookedit', 'createfile', 'filewrite'].includes(name)) {
    pendingToolRequests.delete(String(requestId));
    return { ok: true, checkpointId: null };
  }
  const input = request.input || {};
  const inputPath = input.file_path || input.path || input.filePath || input.filename;
  if (typeof inputPath !== 'string' || !inputPath.trim()) {
    return { ok: false, reason: 'File-writing tool did not provide a recognized file path; approval was blocked because a safety checkpoint could not be created.' };
  }
  try {
    const exists = fs.existsSync(inputPath);
    const full = safeWorkspacePath(inputPath, exists);
    const id = createFileCheckpoint(full, 'XINCLI agent ' + request.toolName);
    const toolUseId = request.toolUseId;
    if (!toolUseId) throw new Error('XINCLI tool-use ID was missing; safety checkpoint could not be attached.');
    agentCheckpoints.set(toolUseId, { id, path: full, toolName: request.toolName });
    pendingToolRequests.delete(String(requestId));
    publish({ type: 'agent_checkpoint_created', checkpointId: id, path: full, toolName: request.toolName, toolUseId });
    return { ok: true, checkpointId: id };
  } catch (error) {
    return { ok: false, reason: 'Could not create pre-edit safety checkpoint: ' + error.message };
  }
}

function sealAgentCheckpoint(toolUseId) {
  const item = agentCheckpoints.get(String(toolUseId));
  if (!item) return;
  try {
    const exists = fs.existsSync(item.path);
    const current = exists ? fs.readFileSync(item.path) : Buffer.alloc(0);
    const loaded = loadCheckpoint(item.id);
    const data = loaded.data;
    data.entries[0].afterHash = exists ? hashBytes(current) : 'NEW';
    data.sealed = true;
    saveCheckpoint(item.id, data);
    publish({ type: 'agent_checkpoint_saved', checkpointId: item.id, path: item.path, toolName: item.toolName });
  } catch (error) {
    publish({ type: 'agent_checkpoint_error', checkpointId: item.id, path: item.path, message: error.message });
  } finally {
    agentCheckpoints.delete(String(toolUseId));
  }
}

function readFileForEditor(message) {
  try {
    const full = safeWorkspacePath(message.path, true);
    const st = fs.statSync(full);
    if (!st.isFile()) throw new Error('Only regular files can be opened in the editor.');
    if (st.size > MAX_EDITOR_BYTES) throw new Error('File is larger than 1 MiB. Use the terminal for large files.');
    const data = fs.readFileSync(full);
    const content = data.toString('utf8');
    if (content.includes('\uFFFD')) throw new Error('This file is not valid UTF-8 text and cannot be edited here.');
    publish({ type: 'file_read_result', path: full, content, hash: hashBytes(data), size: st.size });
  } catch (error) {
    publish({ type: 'file_read_result', path: String(message.path || ''), error: error.message });
  }
}
function loadCheckpoint(id) {
  if (!/^[a-f0-9-]{16,64}$/i.test(String(id || ''))) throw new Error('Invalid checkpoint ID.');
  const file = path.join(CHECKPOINT_DIR, String(id) + '.json');
  if (!isInside(CHECKPOINT_DIR, file) || !fs.existsSync(file)) throw new Error('Checkpoint not found.');
  return { file, data: JSON.parse(fs.readFileSync(file, 'utf8')) };
}
function saveCheckpoint(id, data) {
  fs.mkdirSync(CHECKPOINT_DIR, { recursive: true, mode: 0o700 });
  const file = path.join(CHECKPOINT_DIR, id + '.json');
  const temp = file + '.tmp';
  fs.writeFileSync(temp, JSON.stringify(data), { mode: 0o600 });
  fs.renameSync(temp, file);
}
function createFileCheckpoint(fullPath, purpose) {
  const existed = fs.existsSync(fullPath);
  let original = Buffer.alloc(0);
  if (existed) {
    const st = fs.lstatSync(fullPath);
    if (!st.isFile() || st.isSymbolicLink()) throw new Error('Checkpoint only supports regular files.');
    if (st.size > MAX_EDITOR_BYTES) throw new Error('File is too large for a safe editor checkpoint.');
    original = fs.readFileSync(fullPath);
  }
  const id = createHash('sha256').update(Date.now() + '-' + Math.random() + '-' + process.pid).digest('hex').slice(0, 32);
  const data = {
    id, purpose: String(purpose || 'file edit').slice(0, 80), createdAt: Date.now(), sealed: false,
    entries: [{ path: fullPath, existed, beforeBase64: existed ? original.toString('base64') : null,
      beforeHash: existed ? hashBytes(original) : null, afterHash: null }]
  };
  if (JSON.stringify(data).length > MAX_CHECKPOINT_BYTES) throw new Error('Checkpoint exceeds the 2 MiB safety limit.');
  saveCheckpoint(id, data);
  return id;
}
function writeFileFromEditor(message) {
  try {
    const proposed = String(message.path || '');
    const existsAtStart = fs.existsSync(proposed);
    const full = safeWorkspacePath(proposed, existsAtStart);
    const exists = fs.existsSync(full);
    const current = exists ? fs.readFileSync(full) : Buffer.alloc(0);
    const actualHash = exists ? hashBytes(current) : 'NEW';
    if (String(message.expectedHash || '') !== actualHash) {
      publish({ type: 'file_write_result', path: full, error: 'File changed since it was opened. Reopen it before saving; no data was overwritten.', conflict: true });
      return;
    }
    const content = String(message.content ?? '');
    const data = Buffer.from(content, 'utf8');
    if (data.length > MAX_EDITOR_BYTES) throw new Error('Editor content exceeds 1 MiB.');
    if (content.includes('\u0000')) throw new Error('Binary data is not supported in the text editor.');
    const id = createFileCheckpoint(full, 'Maomao editor save');
    const temp = full + '.maomao-tmp-' + id;
    fs.writeFileSync(temp, data, { mode: exists ? fs.statSync(full).mode : 0o600 });
    fs.renameSync(temp, full);
    const checkpoint = loadCheckpoint(id).data;
    checkpoint.entries[0].afterHash = hashBytes(data);
    checkpoint.sealed = true;
    saveCheckpoint(id, checkpoint);
    publish({ type: 'file_write_result', path: full, hash: hashBytes(data), checkpointId: id, size: data.length });
  } catch (error) {
    publish({ type: 'file_write_result', path: String(message.path || ''), error: error.message });
  }
}
function checkpointCurrentHash(filePath) {
  if (!fs.existsSync(filePath)) return 'NEW';
  const st = fs.lstatSync(filePath);
  if (st.isSymbolicLink()) return 'SYMLINK';
  if (!st.isFile()) return 'NOT_FILE';
  return hashBytes(fs.readFileSync(filePath));
}

function checkpointPreview(message) {
  try {
    const loaded = loadCheckpoint(message.checkpointId);
    const data = loaded.data;
    const conflicts = [], restorable = [];
    for (const entry of data.entries) {
      const currentHash = checkpointCurrentHash(entry.path);
      if (entry.afterHash && currentHash === entry.afterHash) restorable.push(entry.path);
      else conflicts.push(entry.path);
    }
    publish({ type: 'checkpoint_preview_result', checkpointId: data.id, purpose: data.purpose, restorable, conflicts, safe: data.sealed && conflicts.length === 0 });
  } catch (error) {
    publish({ type: 'checkpoint_preview_result', checkpointId: message.checkpointId, error: error.message });
  }
}
function restoreCheckpoint(message) {
  try {
    const loaded = loadCheckpoint(message.checkpointId);
    const file = loaded.file, data = loaded.data;
    const conflicts = [];
    for (const entry of data.entries) {
      const currentHash = checkpointCurrentHash(entry.path);
      if (!entry.afterHash || currentHash !== entry.afterHash) conflicts.push(entry.path);
    }
    if (!data.sealed || conflicts.length) {
      publish({ type: 'checkpoint_restore_result', checkpointId: data.id, restored: false, conflicts, error: 'Restore blocked: files changed after the checkpoint. Nothing was overwritten.' });
      return;
    }
    for (const entry of data.entries) {
      if (entry.existed) {
        const original = Buffer.from(entry.beforeBase64 || '', 'base64');
        const temp = entry.path + '.maomao-restore-' + data.id;
        fs.writeFileSync(temp, original, { mode: 0o600 });
        fs.renameSync(temp, entry.path);
      } else {
        fs.unlinkSync(entry.path);
      }
    }
    fs.renameSync(file, file + '.restored');
    publish({ type: 'checkpoint_restore_result', checkpointId: data.id, restored: true, conflicts: [], paths: data.entries.map(entry => entry.path) });
  } catch (error) {
    publish({ type: 'checkpoint_restore_result', checkpointId: message.checkpointId, restored: false, conflicts: [], error: error.message });
  }
}
function gitRun(cwd, args, timeout = 120000) {
  const result = spawnSync(GIT, ['-C', cwd, ...args], {
    encoding: 'utf8', timeout, maxBuffer: 3 * 1024 * 1024,
    env: { ...process.env, HOME: home, PREFIX: '/data/data/com.termux/files/usr',
      PATH: '/data/data/com.termux/files/usr/bin:' + home + '/.local/bin:' + (process.env.PATH || '') }
  });
  return { code: result.status ?? (result.error ? 124 : 1),
    stdout: String(result.stdout || '').slice(0, 256 * 1024),
    stderr: String(result.stderr || (result.error && result.error.message) || '').slice(0, 32 * 1024) };
}
function gitRoot(cwd) {
  const result = gitRun(cwd, ['rev-parse', '--show-toplevel'], 10000);
  if (result.code !== 0) throw new Error(result.stderr || 'Not inside a Git repository.');
  const root = fs.realpathSync(result.stdout.trim());
  if (!isInside(home, root)) throw new Error('Git repository is outside the Termux home workspace.');
  return root;
}
function hasDirtyGitTree(cwd) {
  const result = gitRun(cwd, ['status', '--porcelain']);
  if (result.code !== 0) throw new Error(result.stderr || 'Unable to read Git status.');
  return result.stdout.trim().length > 0;
}
function runGitAction(message) {
  const action = String(message.action || '');
  let cwd = currentCwd;
  try {
    cwd = safeWorkspacePath(String(message.cwd || currentCwd), true);
    gitRoot(cwd);
    let result = { code: 0, stdout: '', stderr: '' };
    if (action === 'status') {
      result = gitRun(cwd, ['status', '--short', '--branch']);
    } else if (action === 'branches') {
      const current = gitRun(cwd, ['branch', '--show-current']);
      const branches = gitRun(cwd, ['branch', '--format=%(refname:short)']);
      result = { code: branches.code, stdout: JSON.stringify({ current: current.stdout.trim(), branches: branches.stdout.split('\n').map(x => x.trim()).filter(Boolean) }), stderr: branches.stderr };
    } else if (action === 'diff') {
      result = gitRun(cwd, ['diff', 'HEAD', '--no-ext-diff', '--']);
    } else if (action === 'create_branch') {
      const name = String(message.name || '').trim();
      if (!/^[A-Za-z0-9][A-Za-z0-9._/-]{0,79}$/.test(name) || name.includes('..') || name.endsWith('/')) throw new Error('Invalid branch name.');
      result = gitRun(cwd, ['switch', '-c', name]);
    } else if (action === 'switch_branch') {
      if (message.confirmed !== true) throw new Error('Confirmation required before switching branch.');
      if (hasDirtyGitTree(cwd)) throw new Error('Branch switch blocked: commit or back up your working-tree changes first.');
      const name = String(message.name || '').trim();
      if (!name || name.startsWith('-')) throw new Error('Invalid branch name.');
      result = gitRun(cwd, ['switch', name]);
    } else if (action === 'commit') {
      if (message.confirmed !== true) throw new Error('Confirmation required before committing all changes.');
      const msg = String(message.message || '').trim();
      if (!msg) throw new Error('Commit message is required.');
      result = gitRun(cwd, ['add', '-A']);
      if (result.code === 0) result = gitRun(cwd, ['commit', '-m', msg]);
    } else if (action === 'fetch') {
      result = gitRun(cwd, ['fetch', '--all', '--prune']);
    } else if (action === 'pull') {
      if (message.confirmed !== true) throw new Error('Confirmation required before pulling changes.');
      if (hasDirtyGitTree(cwd)) throw new Error('Pull blocked: commit or stash local changes first.');
      result = gitRun(cwd, ['pull', '--ff-only']);
    } else if (action === 'push') {
      if (message.confirmed !== true) throw new Error('Confirmation required before pushing changes.');
      result = gitRun(cwd, ['push']);
    } else {
      throw new Error('Unsupported Git action: ' + action);
    }
    publish({ type: 'git_result', action, cwd, code: result.code, stdout: result.stdout, stderr: result.stderr });
  } catch (error) {
    publish({ type: 'git_result', action, cwd, code: 2, stdout: '', stderr: error.message });
  }
}
function buildRepositoryMap(message) {
  const ignored = new Set(['.git', 'node_modules', '.gradle', 'build', 'dist', 'target', '.idea', '.next', 'vendor']);
  const supported = /\.(kt|kts|java|js|mjs|cjs|ts|tsx|jsx|py|go|rs|swift|c|cc|cpp|h|hpp|rb|php|cs|scala|md)$/i;
  const root = gitRoot(safeWorkspacePath(String(message.cwd || currentCwd), true));
  const files = [], symbols = [], dependencyEdges = [];
  let visited = 0;
  function walk(dir, depth) {
    if (depth > 14 || files.length >= 1800) return;
    let entries = [];
    try { entries = fs.readdirSync(dir, { withFileTypes: true }); } catch { return; }
    for (const entry of entries) {
      if (ignored.has(entry.name) || entry.name.startsWith('.maomao-')) continue;
      const full = path.join(dir, entry.name);
      if (entry.isSymbolicLink()) continue;
      if (entry.isDirectory()) { walk(full, depth + 1); continue; }
      if (!entry.isFile() || !supported.test(entry.name)) continue;
      visited++;
      const rel = path.relative(root, full);
      let stat;
      try { stat = fs.statSync(full); } catch { continue; }
      const fileItem = { path: rel, bytes: stat.size, symbolCount: 0, importCount: 0 };
      files.push(fileItem);
      if (stat.size > 512 * 1024 || (symbols.length >= 500 && dependencyEdges.length >= 1200)) continue;
      let source = '';
      try { source = fs.readFileSync(full, 'utf8'); } catch { continue; }
      const lines = source.split(/\r?\n/);
      const symbolPattern = /^\s*(?:export\s+)?(?:async\s+)?(?:abstract\s+)?(?:class|interface|type|enum|function|const|def|fn|struct|trait|object|fun|data\s+class|sealed\s+class)\s+([A-Za-z_$][\w$]*)/;
      const importPatterns = [
        /^\s*import\s+.*?\s+from\s+['"]([^'"]+)['"]/,
        /^\s*import\s+['"]([^'"]+)['"]/,
        /^\s*from\s+([A-Za-z0-9_./-]+)\s+import\b/,
        /\brequire\(\s*['"]([^'"]+)['"]\s*\)/,
        /^\s*import\s+([A-Za-z0-9_.*]+);/,
        /^\s*import\s+([A-Za-z0-9_.*]+)\b/
      ];
      for (let i = 0; i < lines.length; i++) {
        const symbolMatch = lines[i].match(symbolPattern);
        if (symbolMatch && symbols.length < 500) {
          symbols.push({ name: symbolMatch[1], path: rel, line: i + 1 });
          fileItem.symbolCount++;
        }
        if (dependencyEdges.length < 1200) {
          for (const importPattern of importPatterns) {
            const importMatch = lines[i].match(importPattern);
            if (importMatch) {
              dependencyEdges.push({ from: rel, to: importMatch[1], line: i + 1 });
              fileItem.importCount++;
              break;
            }
          }
        }
      }
    }
  }
  walk(root, 0);
  const keyFiles = files.map(item => {
    const base = path.basename(item.path).toLowerCase();
    let score = item.symbolCount * 3 + item.importCount;
    if (['readme.md', 'package.json', 'settings.gradle.kts', 'build.gradle.kts', 'pyproject.toml', 'cargo.toml', 'go.mod'].includes(base)) score += 50;
    if (/^(main|index|app|server|cli)\./i.test(base)) score += 20;
    return { ...item, score };
  }).sort((a, b) => b.score - a.score).slice(0, 40);
  publish({ type: 'repo_map_result', root, fileCount: files.length, visited,
    truncated: files.length >= 1800 || dependencyEdges.length >= 1200,
    files: files.slice(0, 400), symbols, dependencyEdges, keyFiles });
}

const server = net.createServer(socket => {
  socket.setNoDelay(true);
  let authenticated = false;
  parseLines(socket, message => {
    if (!authenticated) {
      if (message.type !== 'hello' || message.token !== token) {
        socket.destroy();
        return;
      }
      authenticated = true;
      clients.add(socket);
      socket.write(JSON.stringify({ type: 'welcome', connected: true, sessionId: currentSessionId, cwd: currentCwd, running: Boolean(child && child.exitCode === null), lastExit }) + '\n');
      for (const item of history) socket.write(item + '\n');
      return;
    }
    switch (message.type) {
      case 'ping': socket.write(JSON.stringify({ type: 'pong', at: Date.now() }) + '\n'); break;
      case 'start': startCli(message); break;
      case 'input':
      case 'control_response': writeToCli(message); break;
      case 'stop': if (child) child.kill('SIGTERM'); break;
      case 'terminal': runTerminal(message); break;
      case 'files_list': listFiles(message); break;
      case 'file_read': readFileForEditor(message); break;
      case 'file_write': writeFileFromEditor(message); break;
      case 'checkpoint_preview': checkpointPreview(message); break;
      case 'checkpoint_restore': restoreCheckpoint(message); break;
      case 'git_status': gitStatus(message); break;
      case 'git_action': runGitAction(message); break;
      case 'repo_map': buildRepositoryMap(message); break;
      default: publish({ type: 'bridge_error', message: `Unknown bridge action: ${message.type}` });
    }
  });
  socket.on('close', () => clients.delete(socket));
  socket.on('error', () => clients.delete(socket));
});

const metricsServer = net.createServer(socket => {
  parseLines(socket, message => {
    if (message.token !== token || message.type !== 'memory_metrics') return socket.destroy();
    delete message.token;
    if (['normal', 'watch', 'high', 'critical'].includes(message.level)) currentMemoryLevel = message.level;
    publish(message);
  });
});

server.on('error', error => { console.error(`[maomao-bridge] server error: ${error.message}`); process.exitCode = 1; });
metricsServer.on('error', error => console.error(`[maomao-bridge] metrics error: ${error.message}`));
server.listen(PORT, '127.0.0.1', () => console.log(`[maomao-bridge] listening on 127.0.0.1:${PORT}`));
metricsServer.listen(METRICS_PORT, '127.0.0.1', () => console.log(`[maomao-bridge] metrics on 127.0.0.1:${METRICS_PORT}`));