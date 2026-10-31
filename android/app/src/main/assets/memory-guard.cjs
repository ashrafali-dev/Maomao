'use strict';

// Preloaded only into the XINCLI child process. It reports memory telemetry to
// the loopback-only Maomao bridge; it never terminates or mutates the CLI.
const net = require('node:net');
const v8 = require('node:v8');
const port = Number(process.env.MAOMAO_METRICS_PORT || 0);
const token = process.env.MAOMAO_BRIDGE_TOKEN || '';
let socket = null;
let retryTimer = null;

function connect() {
  if (!port || !token || (socket && !socket.destroyed)) return;
  socket = net.createConnection({ host: '127.0.0.1', port });
  socket.on('error', () => { try { socket?.destroy(); } catch {} socket = null; scheduleReconnect(); });
  socket.on('close', () => { socket = null; scheduleReconnect(); });
  socket.on('connect', sendSnapshot);
  socket.unref();
}

function scheduleReconnect() {
  if (retryTimer) return;
  retryTimer = setTimeout(() => { retryTimer = null; connect(); }, 5000);
  retryTimer.unref();
}

function sendSnapshot() {
  if (!socket || socket.destroyed) return;
  try {
    const mem = process.memoryUsage();
    const heapLimit = v8.getHeapStatistics().heap_size_limit;
    const ratio = heapLimit > 0 ? mem.heapUsed / heapLimit : 0;
    const level = ratio >= 0.88 ? 'critical' : ratio >= 0.80 ? 'high' : ratio >= 0.70 ? 'watch' : 'normal';
    socket.write(JSON.stringify({
      type: 'memory_metrics', token,
      pid: process.pid, at: Date.now(), level,
      heapUsed: mem.heapUsed, heapTotal: mem.heapTotal,
      heapLimit, rss: mem.rss, external: mem.external,
      arrayBuffers: mem.arrayBuffers, ratio
    }) + '\n');
  } catch { /* Diagnostics must never affect the CLI. */ }
}

connect();
const interval = setInterval(() => {
  if (!socket || socket.destroyed) connect();
  else sendSnapshot();
}, 4000);
interval.unref();