#!/usr/bin/env node
/**
 * DaiDaiFish-DSH-Bridge — DSH 掌上控制台 · 中转服务（正式版）
 * ============================================================
 * 一个进程，三条链路：
 *
 *   ① 手机  ⇄  本服务(WebSocket :8080)   发 run / 收事件流 / 点审批
 *   ② 本服务 ⇄  dsh headless 子进程       spawn + 逐行 JSON 事件转发
 *   ③ dsh 插件 ⇄ 本服务(TCP :8787)        approval/request 的应答通道
 *
 * 审批链路（打通验证过的闭环）：
 *   dsh 沙箱拒绝 → 模型提权 → 插件 dsh-approval-bridge
 *   → TCP :8787 发 {type:'approval-request', id, toolName}
 *   → 本服务转发手机 {type:'approval-request', data:{id, toolName, reason, timeoutMs}}
 *   → 手机回 {action:'approval', id, decision:'allowed-once'|'rejected'}
 *   → 本服务回插件 {type:'approval-answer', outcome}
 *
 * 手机不在线时的安全策略（fail-closed）：
 *   遇到审批请求直接回 'rejected'，不阻塞、不放行。
 *
 * 运行：
 *   cd bridge
 *   node server.js
 *   （DEEPSEEK_API_KEY 从项目根 .env 读取，不硬编码）
 *
 * 自测：
 *   curl http://127.0.0.1:8080/health
 */

'use strict';

const http = require('http');
const net = require('net');
const fs = require('fs');
const path = require('path');
const crypto = require('crypto');
const { spawn } = require('child_process');

// ============================================================
// 〇、配置
// ============================================================
const ROOT = path.resolve(__dirname, '..'); // 项目根（.env 所在）
const PORT = Number(process.env.PORT || 8080); // 手机端 HTTP/WebSocket
const APPROVAL_PORT = Number(process.env.APPROVAL_PORT || 8787); // 插件 TCP 通道
const WS_GUID = '258EAFA5-E914-47DA-95CA-C5AB0DC85B11';
const DEFAULT_BASE_URL = 'https://www.tokenav.ai/v1';
const APPROVAL_TIMEOUT_MS = Number(process.env.APPROVAL_TIMEOUT_MS || 15000); // 与插件侧 15s 对齐

// ---- .env 读取（KEY 只从环境或 .env 来，绝不硬编码） ----
function loadDotenv() {
  const out = {};
  const file = path.join(ROOT, '.env');
  if (!fs.existsSync(file)) return out;
  for (const raw of fs.readFileSync(file, 'utf8').split(/\r?\n/)) {
    const line = raw.trim();
    if (!line || line.startsWith('#')) continue;
    const m = line.match(/^([A-Za-z_][A-Za-z0-9_]*)\s*=\s*(.*)$/);
    if (!m) continue;
    let v = m[2].trim();
    if ((v.startsWith('"') && v.endsWith('"')) || (v.startsWith("'") && v.endsWith("'"))) {
      v = v.slice(1, -1);
    }
    out[m[1]] = v;
  }
  return out;
}

const dotenv = loadDotenv();
const apiKey = process.env.DEEPSEEK_API_KEY || dotenv.DEEPSEEK_API_KEY || '';
const baseUrl = process.env.DEEPSEEK_BASE_URL || dotenv.DSH_TEST_BASE_URL || DEFAULT_BASE_URL;

function log(msg) {
  console.log(`[${new Date().toLocaleTimeString('zh-CN', { hour12: false })}] ${msg}`);
}

// ============================================================
// 一、运行态
// ============================================================
const phones = new Set(); // 在线手机：{ id, send(obj) }
const pending = new Map(); // 审批 id → { socket, request, timer }
let current = null; // 正在跑的任务：{ proc, prompt, startedAt }

function phoneBroadcast(obj) {
  for (const p of phones) {
    try {
      p.send(obj);
    } catch {
      /* 单部手机发送失败不影响其他 */
    }
  }
}

// ============================================================
// 二、审批通道（dsh 插件 ⇄ 本服务，TCP :8787）
// ============================================================
function answerPlugin(socket, outcome) {
  try {
    socket.write(JSON.stringify({ type: 'approval-answer', outcome }) + '\n');
    socket.end();
  } catch (err) {
    log(`[审批] 回写插件失败: ${err.message}`);
  }
}

function handleApprovalRequest(msg, socket) {
  // 插件发来的 id 是 callId（sandbox 提权请求没有独立 id）
  const id = msg.id || msg.callId || `apr-${Date.now()}`;
  const request = {
    id,
    toolName: msg.toolName || '?',
    reason: msg.reason || '',
    createdAt: Date.now(),
  };
  log(`[审批] 收到请求 id=${id} tool=${request.toolName}`);

  // —— fail-closed：手机不在线，直接拒绝，不让 headless 干等 ——
  if (phones.size === 0) {
    log('[审批] 手机不在线 → 自动 rejected（fail-closed）');
    answerPlugin(socket, 'rejected');
    return;
  }

  // 记录挂起审批，转发给所有在线手机（谁先回谁生效）
  const entry = { socket, request, timer: null };
  pending.set(id, entry);
  phoneBroadcast({
    type: 'approval-request',
    data: { ...request, timeoutMs: APPROVAL_TIMEOUT_MS },
  });

  // 插件侧 15s 会自行超时 rejected；这边只做挂起清理，避免泄漏
  entry.timer = setTimeout(() => {
    if (pending.has(id)) {
      pending.delete(id);
      log(`[审批] id=${id} 手机 ${APPROVAL_TIMEOUT_MS}ms 未回复（插件侧将超时 rejected）`);
    }
  }, APPROVAL_TIMEOUT_MS + 5000);
}

const approvalServer = net.createServer((socket) => {
  let buffer = '';
  socket.on('data', (chunk) => {
    buffer += chunk.toString('utf8');
    let idx;
    while ((idx = buffer.indexOf('\n')) >= 0) {
      const line = buffer.slice(0, idx).trim();
      buffer = buffer.slice(idx + 1);
      if (!line) continue;
      let msg;
      try {
        msg = JSON.parse(line);
      } catch {
        continue;
      }
      if (msg.type === 'approval-request') handleApprovalRequest(msg, socket);
    }
  });
  socket.on('close', () => {
    // 插件超时/断开时清理对应挂起项
    for (const [id, entry] of pending) {
      if (entry.socket === socket) {
        clearTimeout(entry.timer);
        pending.delete(id);
        log(`[审批] 插件连接已断开，清理挂起审批 id=${id}`);
      }
    }
  });
  socket.on('error', () => {});
});

// ============================================================
// 三、任务执行（spawn dsh headless --json，事件流实时推手机）
// ============================================================
function startRun(prompt, reply) {
  if (current) {
    return reply({ type: 'error', message: '已有任务在跑，先发 stop 或等它结束' });
  }
  if (!apiKey) {
    return reply({ type: 'error', message: '缺少 DEEPSEEK_API_KEY（请写入项目根 .env）' });
  }

  const childEnv = { ...process.env, DEEPSEEK_API_KEY: apiKey, DEEPSEEK_BASE_URL: baseUrl };
  const proc = spawn('dsh', ['--profile', 'headless', '--json', prompt], {
    cwd: ROOT,
    env: childEnv,
    shell: true,
  });
  current = { proc, prompt, startedAt: Date.now() };

  log(`[任务] 启动 pid=${proc.pid}: ${prompt.slice(0, 80)}`);
  reply({ type: 'task-started', data: { prompt, pid: proc.pid, cwd: ROOT } });
  phoneBroadcast({ type: 'task-started', data: { prompt, pid: proc.pid, cwd: ROOT } });
  phoneBroadcast({ type: 'status', phase: 'task_started', data: { prompt } });

  // stdout：逐行 JSON → 透传手机
  let buffer = '';
  proc.stdout.on('data', (chunk) => {
    buffer += chunk.toString('utf8');
    let idx;
    while ((idx = buffer.indexOf('\n')) >= 0) {
      const line = buffer.slice(0, idx).trim();
      buffer = buffer.slice(idx + 1);
      if (!line) continue;
      let event;
      try {
        event = JSON.parse(line);
      } catch {
        phoneBroadcast({ type: 'stdout', data: line.slice(0, 4000) });
        continue;
      }
      phoneBroadcast({ type: 'event', data: event });
    }
  });

  // stderr：诊断信息（截断后转发）
  proc.stderr.on('data', (chunk) => {
    const text = chunk.toString('utf8').trim();
    if (text) phoneBroadcast({ type: 'stderr', data: text.slice(0, 4000) });
  });

  proc.on('error', (err) => {
    log(`[任务] spawn 失败: ${err.message}`);
    phoneBroadcast({ type: 'error', message: `spawn 失败: ${err.message}` });
    current = null;
  });

  proc.on('close', (code) => {
    log(`[任务] 结束 code=${code}`);
    phoneBroadcast({ type: 'task-done', data: { code, prompt } });
    current = null;
  });

  return null;
}

function stopRun(reply) {
  if (!current) return reply({ type: 'error', message: '当前没有在跑的任务' });
  const pid = current.proc.pid;
  try {
    if (process.platform === 'win32') {
      // shell:true 下 proc 只是 cmd.exe 包装，必须 taskkill /T 连孙子进程一起杀
      spawn('taskkill', ['/pid', String(pid), '/T', '/F'], { stdio: 'ignore' });
    } else {
      current.proc.kill('SIGTERM');
    }
    log(`[任务] 停止指令已发 pid=${pid}`);
  } catch (err) {
    log(`[任务] kill 失败: ${err.message}`);
  }
  phoneBroadcast({ type: 'status', phase: 'task_stopping', data: { pid } });
  return reply({ type: 'task-stopping' });
}

// ============================================================
// 四、HTTP 接口（只保留健康检查，Mock 全部删除）
// ============================================================
function sendHttpJson(res, statusCode, obj) {
  const body = JSON.stringify(obj);
  res.writeHead(statusCode, {
    'Content-Type': 'application/json; charset=utf-8',
    'Content-Length': Buffer.byteLength(body),
    'Access-Control-Allow-Origin': '*',
    'Access-Control-Allow-Headers': 'Content-Type',
    'Access-Control-Allow-Methods': 'GET,POST,OPTIONS',
  });
  res.end(body);
}

const server = http.createServer((req, res) => {
  const url = new URL(req.url, `http://${req.headers.host || '127.0.0.1'}`);

  if (req.method === 'OPTIONS') {
    res.writeHead(204, {
      'Access-Control-Allow-Origin': '*',
      'Access-Control-Allow-Headers': 'Content-Type',
      'Access-Control-Allow-Methods': 'GET,POST,OPTIONS',
    });
    return res.end();
  }

  if (req.method === 'GET' && url.pathname === '/health') {
    return sendHttpJson(res, 200, {
      ok: true,
      service: 'dai-daifish-dsh-bridge',
      port: PORT,
      approvalPort: APPROVAL_PORT,
      phones: phones.size,
      pendingApprovals: pending.size,
      taskRunning: Boolean(current),
    });
  }

  return sendHttpJson(res, 404, { error: 'not_found' });
});

// ============================================================
// 五、WebSocket（零依赖，手写 RFC 6455 最小实现；手机端）
// ============================================================
function parseFrame(buf) {
  if (buf.length < 2) return null;
  const b0 = buf[0];
  const b1 = buf[1];
  const opcode = b0 & 0x0f;
  const masked = (b1 & 0x80) !== 0;
  let len = b1 & 0x7f;
  let offset = 2;

  if (len === 126) {
    if (buf.length < 4) return null;
    len = buf.readUInt16BE(2);
    offset = 4;
  } else if (len === 127) {
    if (buf.length < 10) return null;
    const big = buf.readBigUInt64BE(2);
    if (big > 0x7fffffff) return null;
    len = Number(big);
    offset = 10;
  }

  let maskKey = null;
  if (masked) {
    if (buf.length < offset + 4) return null;
    maskKey = buf.subarray(offset, offset + 4);
    offset += 4;
  }
  if (buf.length < offset + len) return null;

  let payload = buf.subarray(offset, offset + len);
  if (masked && maskKey) {
    const out = Buffer.allocUnsafe(len);
    for (let i = 0; i < len; i++) out[i] = payload[i] ^ maskKey[i & 3];
    payload = out;
  }
  return { fin: (b0 & 0x80) !== 0, opcode, payload, total: offset + len };
}

server.on('upgrade', (req, socket) => {
  const key = req.headers['sec-websocket-key'];
  if (!key || !String(req.headers.upgrade || '').toLowerCase().includes('websocket')) {
    socket.write('HTTP/1.1 400 Bad Request\r\n\r\n');
    return socket.destroy();
  }

  const accept = crypto.createHash('sha1').update(key + WS_GUID).digest('base64');
  socket.write(
    'HTTP/1.1 101 Switching Protocols\r\n' +
      'Upgrade: websocket\r\n' +
      'Connection: Upgrade\r\n' +
      `Sec-WebSocket-Accept: ${accept}\r\n\r\n`
  );

  const peer = `${socket.remoteAddress}:${socket.remotePort}`;

  function sendFrame(payload, opcode = 0x1) {
    const data = Buffer.isBuffer(payload) ? payload : Buffer.from(String(payload), 'utf8');
    const len = data.length;
    let header;
    if (len < 126) {
      header = Buffer.from([0x80 | opcode, len]);
    } else if (len < 65536) {
      header = Buffer.alloc(4);
      header[0] = 0x80 | opcode;
      header[1] = 126;
      header.writeUInt16BE(len, 2);
    } else {
      header = Buffer.alloc(10);
      header[0] = 0x80 | opcode;
      header[1] = 127;
      header.writeBigUInt64BE(BigInt(len), 2);
    }
    socket.write(Buffer.concat([header, data]));
  }

  function sendWsJson(obj) {
    try {
      sendFrame(JSON.stringify(obj), 0x1);
    } catch {
      /* 连接已断，忽略 */
    }
  }

  // —— 注册为“在线手机”（fail-closed 判断就靠这个集合）——
  // 同一台手机重连时先踢掉旧连接，避免"幽灵在线"把 phones.size 撑到 2~3
  const peerIp = String(socket.remoteAddress || '');
  for (const old of Array.from(phones)) {
    if (old.ip === peerIp) {
      phones.delete(old);
      try {
        old.socket?.destroy();
      } catch {
        /* 已断 */
      }
      log(`[手机] 踢掉旧连接 ${old.id}（同 IP 重连）`);
    }
  }
  const phone = { id: peer, ip: peerIp, socket, send: sendWsJson };
  phones.add(phone);
  log(`[手机] 已连接 ${peer}（在线 ${phones.size} 部）`);

  function handlePhoneMessage(text) {
    let msg;
    try {
      msg = JSON.parse(text);
    } catch {
      return sendWsJson({ type: 'error', message: '无效 JSON' });
    }

    switch (msg.action) {
      case 'run':
        if (!msg.prompt || !String(msg.prompt).trim()) {
          return sendWsJson({ type: 'error', message: 'prompt 不能为空' });
        }
        return startRun(String(msg.prompt).trim(), sendWsJson);

      case 'stop':
        return stopRun(sendWsJson);

      case 'approval': {
        const id = msg.id;
        const entry = pending.get(id);
        if (!entry) {
          return sendWsJson({ type: 'error', message: `没有待处理审批: ${id}` });
        }
        const decision = String(msg.decision || '').toLowerCase();
        const outcome =
          ['allowed-once', 'allowed', 'approve', 'approved', 'ok', 'yes', '允许', '同意'].includes(decision)
            ? 'allowed-once'
            : 'rejected';
        clearTimeout(entry.timer);
        pending.delete(id);
        answerPlugin(entry.socket, outcome);
        log(`[审批] 手机决定 id=${id} → ${outcome}`);
        phoneBroadcast({
          type: 'approval-resolved',
          data: { id, outcome, decidedBy: peer },
        });
        return sendWsJson({ type: 'approval-resolved', data: { id, outcome } });
      }

      case 'ping':
        return sendWsJson({ type: 'pong', t: Date.now() });

      default:
        return sendWsJson({ type: 'error', message: `未知 action: ${msg.action}` });
    }
  }

  let buffer = Buffer.alloc(0);
  socket.on('data', (chunk) => {
    buffer = Buffer.concat([buffer, chunk]);
    let frame;
    while ((frame = parseFrame(buffer))) {
      buffer = buffer.subarray(frame.total);
      switch (frame.opcode) {
        case 0x1:
          handlePhoneMessage(frame.payload.toString('utf8'));
          break;
        case 0x8:
          sendFrame(frame.payload, 0x8);
          socket.end();
          return;
        case 0x9:
          sendFrame(frame.payload, 0xa);
          break;
        default:
          break;
      }
    }
  });

  socket.on('close', () => {
    phones.delete(phone);
    log(`[手机] 已断开 ${peer}（在线 ${phones.size} 部）`);
  });
  socket.on('error', (err) => log(`[手机] WS 错误: ${err.message}`));
});

// ============================================================
// 六、启动
// ============================================================
server.listen(PORT, '0.0.0.0', () => {
  log(`DSH 中转服务已启动: http://0.0.0.0:${PORT}  (WebSocket 同端口，接受外部请求)`);
});
approvalServer.listen(APPROVAL_PORT, '127.0.0.1', () => {
  log(`审批通道已监听: tcp://127.0.0.1:${APPROVAL_PORT}  (dsh-approval-bridge 连这里)`);
});
log(`工作区: ${ROOT}`);
log(`API Key: ${apiKey ? '已从 ' + (process.env.DEEPSEEK_API_KEY ? '启动环境' : '.env') + ' 读取' : '⚠ 未设置'}`);
log(`端点: ${baseUrl}`);
