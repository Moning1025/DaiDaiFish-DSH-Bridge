#!/usr/bin/env node
/**
 * DSH 掌上控制台 — Node.js 中转服务（Mock 版）
 * ------------------------------------------------------------
 * 定位：独立进程，监听 8080 端口。
 *   - 手机（呆呆鱼桌宠 App 桥接端）通过 WebSocket 或 HTTP 连到本服务；
 *   - 本服务目前返回「模拟数据」；
 *   - 以后可在这里接本地命令 / DSH 数据源，替换掉 Mock。
 *
 * 运行：
 *   cd bridge
 *   node server.js          # 或 npm start
 *
 * 自测（另开一个终端）：
 *   curl http://127.0.0.1:8080/health
 *   curl http://127.0.0.1:8080/api/progress
 *   curl http://127.0.0.1:8080/api/approvals
 *   curl -X POST http://127.0.0.1:8080/api/approve -H "Content-Type: application/json" -d "{\"id\":\"apr-1\",\"decision\":\"approve\"}"
 *
 * WebSocket 协议（JSON 文本帧）：
 *   手机 -> 服务:  { "action": "getProgress" | "getApprovals" | "approve", ... }
 *   服务 -> 手机:  { "type": "progress" | "approvals" | "approveResult" | "error", "data": ... }
 */

'use strict';

const http = require('http');
const crypto = require('crypto');

const PORT = process.env.PORT || 8080;
// WebSocket 握手固定魔数（RFC 6455）
const WS_GUID = '258EAFA5-E914-47DA-95CA-C5AB0DC85B11';

// ============================================================
// 一、Mock 数据源（以后替换为真实 DSH 数据）
// ============================================================
const state = {
  tasks: [
    {
      id: 'dsh-1001',
      name: '会话任务：逆向呆呆鱼桌宠',
      phase: '执行中',
      progress: 65,
      status: 'running', // running | waiting_approval | done
      eta: '约 3 分钟',
    },
    {
      id: 'dsh-1002',
      name: '安装 Android build-tools（约 55MB）',
      phase: '等待审批',
      progress: 0,
      status: 'waiting_approval',
      eta: '—',
    },
    {
      id: 'dsh-1003',
      name: '会话任务：本地 Git 提交',
      phase: '已完成',
      progress: 100,
      status: 'done',
      eta: '—',
    },
  ],
  approvals: [
    {
      id: 'apr-1',
      title: '允许安装 Android build-tools（约 55MB）',
      detail: 'Phase 3 回编签名需要 apksigner / zipalign',
      created: '22:10',
    },
    {
      id: 'apr-2',
      title: '允许 git push 到 GitHub',
      detail: '本地已有提交待推送',
      created: '22:05',
    },
  ],
};

// ---------- 三个 Mock 方法 ----------
function mockGetProgress() {
  return {
    tasks: state.tasks,
    updatedAt: new Date().toISOString(),
  };
}

function mockGetApprovals() {
  return {
    pending: state.approvals,
    updatedAt: new Date().toISOString(),
  };
}

function mockApprove(id, decision) {
  const idx = state.approvals.findIndex((a) => a.id === id);
  if (idx === -1) {
    return { ok: false, id, decision, reason: 'not_found' };
  }
  const [removed] = state.approvals.splice(idx, 1);
  // 同步推进任务状态（演示用）
  const task = state.tasks.find((t) => t.status === 'waiting_approval');
  if (task && decision === 'approve') {
    task.status = 'running';
    task.phase = '执行中';
  }
  return {
    ok: true,
    id,
    decision,
    title: removed.title,
    remaining: state.approvals.length,
  };
}

// ============================================================
// 二、HTTP 接口（REST，方便 curl / 手机 HTTP 端调试）
// ============================================================
function sendJson(res, statusCode, obj) {
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

function readBody(req) {
  return new Promise((resolve) => {
    let raw = '';
    req.on('data', (c) => (raw += c));
    req.on('end', () => resolve(raw));
  });
}

const server = http.createServer(async (req, res) => {
  const url = new URL(req.url, `http://${req.headers.host}`);

  if (req.method === 'OPTIONS') {
    res.writeHead(204, {
      'Access-Control-Allow-Origin': '*',
      'Access-Control-Allow-Headers': 'Content-Type',
      'Access-Control-Allow-Methods': 'GET,POST,OPTIONS',
    });
    return res.end();
  }

  if (req.method === 'GET' && url.pathname === '/health') {
    return sendJson(res, 200, { ok: true, service: 'dsh-bridge', port: PORT });
  }

  if (req.method === 'GET' && url.pathname === '/api/progress') {
    return sendJson(res, 200, mockGetProgress());
  }

  if (req.method === 'GET' && url.pathname === '/api/approvals') {
    return sendJson(res, 200, mockGetApprovals());
  }

  if (req.method === 'POST' && url.pathname === '/api/approve') {
    const raw = await readBody(req);
    let body = {};
    try {
      body = JSON.parse(raw || '{}');
    } catch {
      return sendJson(res, 400, { error: '无效 JSON' });
    }
    return sendJson(res, 200, mockApprove(body.id, body.decision));
  }

  return sendJson(res, 404, { error: 'not_found' });
});

// ============================================================
// 三、WebSocket 通信（零依赖，手写 RFC 6455 最小实现）
// ============================================================
server.on('upgrade', (req, socket) => {
  const key = req.headers['sec-websocket-key'];
  if (!key || !String(req.headers.upgrade || '').toLowerCase().includes('websocket')) {
    socket.write('HTTP/1.1 400 Bad Request\r\n\r\n');
    return socket.destroy();
  }

  // 1) 握手应答
  const accept = crypto
    .createHash('sha1')
    .update(key + WS_GUID)
    .digest('base64');
  socket.write(
    'HTTP/1.1 101 Switching Protocols\r\n' +
      'Upgrade: websocket\r\n' +
      'Connection: Upgrade\r\n' +
      `Sec-WebSocket-Accept: ${accept}\r\n\r\n`
  );

  const peer = `${socket.remoteAddress}:${socket.remotePort}`;
  log(`WS 已连接: ${peer}`);

  // 2) 解析客户端帧（客户端帧必须带 mask）
  let buffer = Buffer.alloc(0);

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
      if (big > 0x7fffffff) return null; // 超出安全范围
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
    return {
      fin: (b0 & 0x80) !== 0,
      opcode,
      payload,
      total: offset + len,
    };
  }

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

  function sendJson(obj) {
    sendFrame(JSON.stringify(obj), 0x1);
  }

  // 3) 消息路由（与三个 Mock 方法一一对应）
  function handleMessage(text) {
    let msg;
    try {
      msg = JSON.parse(text);
    } catch {
      return sendJson({ type: 'error', message: '无效 JSON' });
    }

    switch (msg.action) {
      case 'getProgress':
        return sendJson({ type: 'progress', data: mockGetProgress() });
      case 'getApprovals':
        return sendJson({ type: 'approvals', data: mockGetApprovals() });
      case 'approve':
        return sendJson({
          type: 'approveResult',
          data: mockApprove(msg.id, msg.decision),
        });
      default:
        return sendJson({ type: 'error', message: `未知 action: ${msg.action}` });
    }
  }

  socket.on('data', (chunk) => {
    buffer = Buffer.concat([buffer, chunk]);
    let frame;
    while ((frame = parseFrame(buffer))) {
      buffer = buffer.subarray(frame.total);
      switch (frame.opcode) {
        case 0x1: // text
          handleMessage(frame.payload.toString('utf8'));
          break;
        case 0x8: // close
          sendFrame(frame.payload, 0x8);
          socket.end();
          return;
        case 0x9: // ping -> pong
          sendFrame(frame.payload, 0xa);
          break;
        case 0xa: // pong（忽略）
          break;
        default:
          break;
      }
    }
  });

  socket.on('close', () => log(`WS 已断开: ${peer}`));
  socket.on('error', (err) => log(`WS 错误: ${err.message}`));
});

// ============================================================
// 四、启动
// ============================================================
function log(msg) {
  console.log(`[${new Date().toLocaleTimeString('zh-CN', { hour12: false })}] ${msg}`);
}

server.listen(PORT, () => {
  log(`DSH 中转服务已启动: http://127.0.0.1:${PORT}`);
  log(`WebSocket: ws://127.0.0.1:${PORT}`);
  log(`三个 Mock 接口: getProgress / getApprovals / approve`);
});
