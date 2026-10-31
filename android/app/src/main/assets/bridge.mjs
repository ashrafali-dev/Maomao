import net from 'node:net';
import fs from 'node:fs';
import path from 'node:path';
import { spawn } from 'node:child_process';

const [portArg, metricsPortArg, token, cliPath, home] = process.argv.slice(2);
const PORT = Number(portArg || 37645);
const METRICS_PORT = Number(metricsPortArg || 37646);
const clients = new Set();
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
      case 'git_status': gitStatus(message); break;
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