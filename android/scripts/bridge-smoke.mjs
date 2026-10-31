import assert from 'node:assert/strict';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import net from 'node:net';
import { spawn, execFileSync } from 'node:child_process';

const repoRoot = process.cwd();
const bridgeScript = path.join(repoRoot, 'android/app/src/main/assets/bridge.mjs');
const home = fs.mkdtempSync(path.join(os.tmpdir(), 'maomao-bridge-test-'));
const token = 'test-token-0123456789abcdef';
const cliPath = path.join(home, 'fake-xincli.mjs');
const memoryGuard = path.join(home, '.maomao', 'memory-guard.cjs');
fs.mkdirSync(path.dirname(memoryGuard), { recursive: true });
fs.writeFileSync(memoryGuard, '// test-only no-op preload\n');
const agentFile = path.join(home, 'agent-edit.txt');
fs.writeFileSync(agentFile, 'before-agent\n');

const fakeCli = String.raw`#!/usr/bin/env node
import fs from 'node:fs';
import readline from 'node:readline';
const file = process.env.TEST_WRITE_FILE;
console.error('FAKE_XINCLI_STARTED', file);
console.log(JSON.stringify({
  type: 'control_request',
  request_id: 'agent-request-1',
  request: {
    subtype: 'can_use_tool',
    tool_name: 'Write',
    tool_use_id: 'agent-tool-use-1',
    input: { file_path: file, content: 'after-agent' },
    description: 'Write a file'
  }
}));
console.error('FAKE_XINCLI_PERMISSION_SENT');
const input = readline.createInterface({ input: process.stdin });
input.on('line', (line) => {
  let message;
  try { message = JSON.parse(line); } catch { return; }
  const decision = message.response && message.response.response && message.response.response.behavior;
  if (message.type === 'control_response' && decision === 'allow') {
    fs.writeFileSync(file, 'after-agent\\n');
    console.log(JSON.stringify({
      type: 'user',
      message: { content: [{ type: 'tool_result', tool_use_id: 'agent-tool-use-1', content: 'saved' }] }
    }));
    input.close();
    setTimeout(() => process.exit(0), 50);
  }
});
`;
fs.writeFileSync(cliPath, fakeCli, { mode: 0o700 });
fs.chmodSync(cliPath, 0o700);

function freePort() {
  return new Promise((resolve, reject) => {
    const server = net.createServer();
    server.once('error', reject);
    server.listen(0, '127.0.0.1', () => {
      const port = server.address().port;
      server.close(error => error ? reject(error) : resolve(port));
    });
  });
}

const port = await freePort();
const metricsPort = await freePort();
const gitBin = execFileSync('which', ['git'], { encoding: 'utf8' }).trim();
const bridge = spawn(process.execPath, [bridgeScript, String(port), String(metricsPort), token, cliPath, home], {
  cwd: home,
  env: { ...process.env, MAOMAO_GIT_BIN: gitBin, TEST_WRITE_FILE: agentFile },
  stdio: ['ignore', 'pipe', 'pipe']
});
let bridgeStderr = '';
let bridgeStdout = '';
let currentStep = 'boot';
bridge.stdout.setEncoding('utf8');
bridge.stdout.on('data', chunk => { bridgeStdout += chunk; });
bridge.stderr.setEncoding('utf8');
bridge.stderr.on('data', chunk => { bridgeStderr += chunk; });

let socket;
const backlog = [];
const waiters = [];
function dispatch(event) {
  const index = waiters.findIndex(waiter => waiter.predicate(event));
  if (index >= 0) {
    const waiter = waiters.splice(index, 1)[0];
    clearTimeout(waiter.timer);
    waiter.resolve(event);
  } else {
    backlog.push(event);
  }
}
function nextEvent(predicate, timeoutMs = 10000) {
  const index = backlog.findIndex(predicate);
  if (index >= 0) return Promise.resolve(backlog.splice(index, 1)[0]);
  return new Promise((resolve, reject) => {
    const waiter = { predicate, resolve, timer: null };
    waiter.timer = setTimeout(() => {
      const i = waiters.indexOf(waiter);
      if (i >= 0) waiters.splice(i, 1);
      reject(new Error('Timed out waiting for bridge event at step=' + currentStep + '; queued=' + JSON.stringify(backlog.map(event => ({ type: event.type, stream: event.stream, subtype: event.event?.type, code: event.code, raw: String(event.raw || '').slice(0, 200), stderr: event.stderr }))) + '; stdout=' + bridgeStdout + '; stderr=' + bridgeStderr));
    }, timeoutMs);
    waiters.push(waiter);
  });
}
function send(message) {
  socket.write(JSON.stringify(message) + '\n');
}
try {
  async function connectWithRetry() {
    const deadline = Date.now() + 10000;
    let lastError = null;
    while (Date.now() < deadline) {
      const candidate = net.createConnection({ host: '127.0.0.1', port });
      candidate.setEncoding('utf8');
      let buffer = '';
      candidate.on('data', chunk => {
        buffer += chunk;
        let index;
        while ((index = buffer.indexOf('\n')) >= 0) {
          const line = buffer.slice(0, index);
          buffer = buffer.slice(index + 1);
          if (line.trim()) {
            try { dispatch(JSON.parse(line)); } catch {}
          }
        }
      });
      try {
        await new Promise((resolve, reject) => {
          candidate.once('connect', resolve);
          candidate.once('error', reject);
        });
        return candidate;
      } catch (error) {
        lastError = error;
        candidate.destroy();
        await new Promise(resolve => setTimeout(resolve, 100));
      }
    }
    throw new Error('Bridge did not listen. Last error=' + (lastError?.message || 'unknown') + '. stderr=' + bridgeStderr);
  }
  socket = await connectWithRetry();
  send({ type: 'hello', token });
  await nextEvent(event => event.type === 'welcome');

  // File read + stale-write conflict detection.
  const sample = path.join(home, 'sample.txt');
  fs.writeFileSync(sample, 'original\n');
  send({ type: 'file_read', path: sample });
  const read = await nextEvent(event => event.type === 'file_read_result' && event.path === sample);
  assert.equal(read.content, 'original\n');
  send({ type: 'file_write', path: sample, content: 'must-not-write', expectedHash: 'stale-hash' });
  const stale = await nextEvent(event => event.type === 'file_write_result' && event.conflict === true);
  assert.match(stale.error, /changed since it was opened/i);
  assert.equal(fs.readFileSync(sample, 'utf8'), 'original\n');

  // Atomic editor write + safe checkpoint preview + restore.
  send({ type: 'file_write', path: sample, content: 'edited\n', expectedHash: read.hash });
  const saved = await nextEvent(event => event.type === 'file_write_result' && event.checkpointId);
  assert.equal(fs.readFileSync(sample, 'utf8'), 'edited\n');
  send({ type: 'checkpoint_preview', checkpointId: saved.checkpointId });
  const preview = await nextEvent(event => event.type === 'checkpoint_preview_result');
  assert.equal(preview.safe, true);
  send({ type: 'checkpoint_restore', checkpointId: saved.checkpointId });
  const restored = await nextEvent(event => event.type === 'checkpoint_restore_result');
  assert.equal(restored.restored, true);
  assert.equal(fs.readFileSync(sample, 'utf8'), 'original\n');

  // Workspace traversal is rejected.
  send({ type: 'file_read', path: path.join(home, '..', 'outside.txt') });
  const traversal = await nextEvent(event => event.type === 'file_read_result' && event.error);
  assert.match(traversal.error, /outside/i);

  // Git branch, commit, status, diff and dirty-tree protection.
  const project = path.join(home, 'project');
  fs.mkdirSync(project);
  execFileSync(gitBin, ['-C', project, 'init', '-b', 'main']);
  execFileSync(gitBin, ['-C', project, 'config', 'user.name', 'Maomao Test']);
  execFileSync(gitBin, ['-C', project, 'config', 'user.email', 'maomao-test@example.invalid']);
  fs.writeFileSync(path.join(project, 'README.md'), '# test\n');
  execFileSync(gitBin, ['-C', project, 'add', 'README.md']);
  execFileSync(gitBin, ['-C', project, 'commit', '-m', 'initial']);
  send({ type: 'git_action', action: 'branches', cwd: project });
  const branches = await nextEvent(event => event.type === 'git_result' && event.action === 'branches');
  assert.equal(JSON.parse(branches.stdout).current, 'main');
  send({ type: 'git_action', action: 'create_branch', cwd: project, name: 'feature/editor' });
  const created = await nextEvent(event => event.type === 'git_result' && event.action === 'create_branch');
  assert.equal(created.code, 0);
  send({ type: 'git_action', action: 'switch_branch', cwd: project, name: 'main', confirmed: false });
  const unconfirmed = await nextEvent(event => event.type === 'git_result' && event.action === 'switch_branch');
  assert.match(unconfirmed.stderr, /confirmation required/i);
  send({ type: 'git_action', action: 'switch_branch', cwd: project, name: 'main', confirmed: true });
  const switched = await nextEvent(event => event.type === 'git_result' && event.action === 'switch_branch');
  assert.equal(switched.code, 0);
  fs.writeFileSync(path.join(project, 'changed.txt'), 'dirty\n');
  send({ type: 'git_action', action: 'pull', cwd: project, confirmed: true });
  const pullBlocked = await nextEvent(event => event.type === 'git_result' && event.action === 'pull');
  assert.match(pullBlocked.stderr, /commit or stash local changes first/i);

  // Repository map includes files, symbols, key files and dependencies.
  send({ type: 'repo_map', cwd: project });
  const map = await nextEvent(event => event.type === 'repo_map_result');
  assert.ok(map.fileCount >= 1);
  assert.ok(Array.isArray(map.keyFiles));
  assert.ok(Array.isArray(map.dependencyEdges));

  // XINCLI Write tool: snapshot before permission is forwarded, seal after tool_result.
  currentStep = 'start stub CLI';
  send({ type: 'start', cwd: project, title: 'checkpoint test', model: 'stub' });
  currentStep = 'wait for agent permission event';
  const permission = await nextEvent(event => event.type === 'cli_event' && event.event?.type === 'control_request');
  const request = permission.event;
  const requestId = request.request_id || 'agent-request-1';
  currentStep = 'send agent tool approval';
  send({
    type: 'control_response',
    response: {
      subtype: 'success',
      request_id: requestId,
      response: { behavior: 'allow', updatedInput: request.request.input, updatedPermissions: [] }
    }
  });
  currentStep = 'wait for pre-edit checkpoint';
  const checkpointCreated = await nextEvent(event => event.type === 'agent_checkpoint_created');
  currentStep = 'wait for sealed agent checkpoint';
  const checkpointSaved = await nextEvent(event => event.type === 'agent_checkpoint_saved');
  assert.equal(checkpointSaved.checkpointId, checkpointCreated.checkpointId);
  assert.equal(fs.readFileSync(agentFile, 'utf8'), 'after-agent\n');
  currentStep = 'preview agent checkpoint';
  send({ type: 'checkpoint_preview', checkpointId: checkpointSaved.checkpointId });
  const agentPreview = await nextEvent(event => event.type === 'checkpoint_preview_result');
  assert.equal(agentPreview.safe, true);
  currentStep = 'restore agent checkpoint';
  send({ type: 'checkpoint_restore', checkpointId: checkpointSaved.checkpointId });
  const agentRestore = await nextEvent(event => event.type === 'checkpoint_restore_result');
  assert.equal(agentRestore.restored, true);
  assert.equal(fs.readFileSync(agentFile, 'utf8'), 'before-agent\n');

  console.log('PASS: bridge file editor, hash conflicts, checkpoint undo, path confinement, Git controls and repo map.');
} finally {
  if (socket && !socket.destroyed) socket.destroy();
  if (bridge && bridge.exitCode === null) bridge.kill('SIGTERM');
  await new Promise(resolve => {
    if (bridge.exitCode !== null) return resolve();
    bridge.once('exit', resolve);
    setTimeout(resolve, 1500);
  });
  fs.rmSync(home, { recursive: true, force: true });
}
