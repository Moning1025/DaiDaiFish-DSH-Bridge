#!/usr/bin/env node
/**
 * DaiDaiFish-DSH-Bridge — DSH 掌上控制台 · 中转服务（v3.2 / P1）
 * ============================================================
 * 一个进程，三条链路：
 *
 *   ① 手机  ⇄  本服务(WebSocket :8080)   发 run / 收 trace、状态 / 点审批
 *   ② 本服务 ⇄  dsh headless 子进程       spawn + 逐行 JSON 事件 → 归一化
 *   ③ dsh 插件 ⇄ 本服务(TCP :8787)        approval/request 的应答通道
 *
 * v3.2 新增（P1）：
 *   - 多任务：tasks Map，并发上限 MAX_TASKS（默认 2），超了回 BUSY
 *   - 会话：新任务省略 --session-id，从 session 事件抓 sessionId；续会话传 sessionId
 *   - 生命周期：normalizeEvent() 把 8 种原始事件压成 trace / task-state
 *   - 轨迹：每任务保留最近 TRACE_MAX 条，切任务可回放（task-trace）
 *   - 看门狗：IDLE_TIMEOUT_MS 无事件 → 判失败并杀掉
 *
 * 审批链路（打通验证过的闭环）：
 *   dsh 沙箱拒绝 → 模型提权 → 插件 dsh-approval-bridge
 *   → TCP :8787 发 {type:'approval-request', id, toolName, reason}
 *   → 本服务转发手机 {type:'approval-request', data:{id, taskId, toolName, reason, timeoutMs}}
 *   → 手机回 {action:'approval', id, decision:'allowed-once'|'rejected'}
 *   → 本服务回插件 {type:'approval-answer', outcome}
 *
 * 运行：
 *   cd bridge && node server.js
 *   （DEEPSEEK_API_KEY 从项目根 .env 读取，不硬编码）
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
const MAX_TASKS = Number(process.env.MAX_TASKS || 2); // 并发任务上限（老大拍板：2）
const TRACE_MAX = Number(process.env.TRACE_MAX || 200); // 每任务保留轨迹条数
const IDLE_TIMEOUT_MS = Number(process.env.IDLE_TIMEOUT_MS || 10 * 60 * 1000); // 10 分钟无事件 → 判失败
const UPLOAD_MAX_BYTES = Number(process.env.UPLOAD_MAX_BYTES || 50 * 1024 * 1024); // 单文件 50MB
const UPLOAD_TASK_MAX_BYTES = Number(process.env.UPLOAD_TASK_MAX_BYTES || 200 * 1024 * 1024); // 单任务累计 200MB
const WORKSPACE_FILE =
  process.env.DSH_WORKSPACE_FILE ||
  'D:\\DeepSeekHarness\\dsh_data\\storages\\workspace.json'; // DSH 工作区注册表

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
// 〇·五、工作区注册表（读写 DSH 的 workspace.json，给手机端选 cwd）
// ============================================================
function readRegistry() {
  try {
    if (!fs.existsSync(WORKSPACE_FILE)) return null;
    return JSON.parse(fs.readFileSync(WORKSPACE_FILE, 'utf8'));
  } catch (err) {
    log(`[工作区] 读取注册表失败: ${err.message}`);
    return null;
  }
}

function writeRegistry(reg) {
  const tmp = `${WORKSPACE_FILE}.${process.pid}.tmp`;
  fs.writeFileSync(tmp, JSON.stringify(reg, null, 2), 'utf8');
  fs.renameSync(tmp, WORKSPACE_FILE);
}

function listWorkspaces() {
  const reg = readRegistry();
  const table = (reg && reg.tables && reg.tables.workspaces) || {};
  const ids = (reg && reg.global && reg.global.workspaceIds) || Object.keys(table);
  const out = [];
  for (const id of ids) {
    const ws = table[id];
    if (!ws || !ws.path) continue;
    out.push({
      id,
      title: ws.title || path.basename(ws.path) || ws.path,
      path: ws.path,
      updatedAt: ws.updatedAt || ws.createdAt || '',
      exists: fs.existsSync(ws.path),
    });
  }
  out.sort((a, b) => String(b.updatedAt).localeCompare(String(a.updatedAt)));
  return out;
}

function createWorkspace(rawPath) {
  const input = String(rawPath || '')
    .trim()
    .replace(/^"(.*)"$/, '$1');
  if (!input) throw new Error('工作区路径不能为空');
  if (!path.isAbsolute(input)) throw new Error('必须是绝对路径，例如 D:\\DeepSeekHarness\\my-project');
  const dir = path.resolve(input);

  const reg = readRegistry();
  if (!reg || !reg.tables || !reg.tables.workspaces) {
    throw new Error(`注册表不可用：${WORKSPACE_FILE}`);
  }
  reg.global = reg.global || {};
  reg.global.workspaceIds = reg.global.workspaceIds || [];

  for (const [id, ws] of Object.entries(reg.tables.workspaces)) {
    if (ws && ws.path && path.resolve(ws.path).toLowerCase() === dir.toLowerCase()) {
      if (!fs.existsSync(dir)) fs.mkdirSync(dir, { recursive: true });
      return { id, title: ws.title || path.basename(dir), path: ws.path, existed: true };
    }
  }

  if (!fs.existsSync(dir)) fs.mkdirSync(dir, { recursive: true });
  if (!fs.statSync(dir).isDirectory()) throw new Error('目标路径不是文件夹');

  const id = crypto.randomUUID();
  const now = new Date().toISOString();
  reg.tables.workspaces[id] = {
    path: dir,
    title: path.basename(dir) || dir,
    sessionIds: [],
    createdAt: now,
    updatedAt: now,
  };
  reg.global.workspaceIds.push(id);
  writeRegistry(reg);
  log(`[工作区] 已创建 ${id} → ${dir}`);
  return { id, title: path.basename(dir) || dir, path: dir, existed: false };
}

function resolveCwd(workspace) {
  const want = String(workspace || '').trim();
  if (!want) return ROOT;
  try {
    const dir = path.resolve(want);
    if (fs.existsSync(dir) && fs.statSync(dir).isDirectory()) return dir;
  } catch {
    /* 路径非法 */
  }
  return null;
}

function workspaceTitleOf(cwd) {
  const want = path.resolve(String(cwd || '')).toLowerCase();
  for (const ws of listWorkspaces()) {
    if (path.resolve(ws.path).toLowerCase() === want) return ws.title;
  }
  return path.basename(String(cwd || '')) || String(cwd || '');
}

// ============================================================
// 一、运行态
// ============================================================
const phones = new Set(); // 在线手机：{ id, ip, socket, send(obj) }
const pending = new Map(); // 审批 id → { socket, request, timer, taskId }
const tasks = new Map(); // taskId → task
let taskSeq = 0;
let lastTaskId = null; // 最近活跃任务（手机没指定 taskId 时默认作用于它）

function phoneBroadcast(obj) {
  for (const p of phones) {
    try {
      p.send(obj);
    } catch {
      /* 单部手机发送失败不影响其他 */
    }
  }
}

function newTaskId() {
  taskSeq += 1;
  return `t${taskSeq}`;
}

function runningTasks() {
  return Array.from(tasks.values()).filter((t) => t.state === 'running');
}

function tasksSnapshot() {
  return Array.from(tasks.values())
    .sort((a, b) => b.startedAt - a.startedAt)
    .map((t) => ({
      taskId: t.taskId,
      sessionId: t.sessionId || null,
      workspace: t.workspace,
      workspaceTitle: t.workspaceTitle,
      cwd: t.cwd,
      prompt: t.prompt,
      state: t.state,
      turn: t.turn || 0,
      step: t.step || 0,
      startedAt: t.startedAt,
      finishedAt: t.finishedAt || null,
      traceCount: t.trace.length,
    }));
}

function broadcastTasks() {
  phoneBroadcast({ type: 'tasks', data: tasksSnapshot() });
}

// ============================================================
// 二、生命周期事件归一化（P1 核心）
// ============================================================
function clip(text, n) {
  const s = String(text == null ? '' : text).replace(/\s+/g, ' ').trim();
  return s.length > n ? `${s.slice(0, n)}…` : s;
}

function setTaskState(task, state, extra) {
  if (!task) return;
  const changed = task.state !== state;
  task.state = state;
  task.lastEventAt = Date.now();
  const payload = {
    taskId: task.taskId,
    state,
    turn: task.turn || 0,
    step: task.step || 0,
    ...(extra || {}),
  };
  if (state !== 'running') task.finishedAt = Date.now();
  phoneBroadcast({ type: 'task-state', data: payload });
  if (changed) {
    log(`[任务] ${task.taskId} → ${state}${extra && extra.reason ? `（${clip(extra.reason, 80)}）` : ''}`);
  }
  if (state !== 'running') broadcastTasks();
}

function pushTrace(task, entry) {
  if (!task) return;
  const item = { taskId: task.taskId, at: Date.now(), ...entry };
  task.trace.push(item);
  if (task.trace.length > TRACE_MAX) task.trace.splice(0, task.trace.length - TRACE_MAX);
  phoneBroadcast({ type: 'trace', data: item });
}

/** 把 dsh 的 8 种事件压成 trace / task-state；返回 true 表示已消费 */
function normalizeEvent(task, event) {
  const type = event && event.type;
  task.lastEventAt = Date.now();

  switch (type) {
    case 'session': {
      if (event.sessionId) {
        task.sessionId = event.sessionId;
        log(`[任务] ${task.taskId} 会话 ${event.sessionId}`);
      }
      phoneBroadcast({ type: 'session', data: { taskId: task.taskId, sessionId: task.sessionId, cwd: task.cwd } });
      return true;
    }

    case 'status': {
      const phase = event.phase || '';
      if (typeof event.turn === 'number') task.turn = event.turn;
      if (typeof event.step === 'number') task.step = event.step;
      phoneBroadcast({
        type: 'task-state',
        data: { taskId: task.taskId, state: 'running', phase, turn: task.turn || 0, step: task.step || 0 },
      });
      return true;
    }

    case 'thinking': {
      pushTrace(task, {
        kind: 'thinking',
        brief: `思考：${clip(event.text, 60)}`,
        detail: String(event.text || ''),
      });
      return true;
    }

    case 'tool_call': {
      const input = event.input || {};
      const hint =
        input.command || input.description || input.file_path || input.path || input.query || Object.keys(input)[0] || '';
      pushTrace(task, {
        kind: 'tool_call',
        brief: `${event.tool || '?'}: ${clip(hint, 70)}`,
        detail: JSON.stringify(input, null, 2).slice(0, 4000),
        callId: event.callId || null,
      });
      return true;
    }

    case 'tool_result': {
      const ok = event.status === 'completed' || event.status === 'success';
      pushTrace(task, {
        kind: 'tool_result',
        brief: `${ok ? '已完成' : '失败(' + (event.status || '?') + ')'} · ${clip(event.result, 70)}`,
        detail: String(event.result == null ? '' : event.result).slice(0, 4000),
        callId: event.callId || null,
        ok,
      });
      return true;
    }

    case 'text': {
      // 流式正文：不进轨迹（避免刷屏），单独下发，P3 前端可做打字机
      phoneBroadcast({ type: 'task-text', data: { taskId: task.taskId, text: String(event.text || '') } });
      return true;
    }

    case 'final': {
      pushTrace(task, { kind: 'final', brief: `任务完成 · ${clip(event.text, 60)}`, detail: String(event.text || '') });
      setTaskState(task, 'success', { durationMs: Date.now() - task.startedAt });
      return true;
    }

    case 'error': {
      const message = String(event.message || '未知错误');
      task.errorMessage = message;
      pushTrace(task, { kind: 'error', brief: `错误：${clip(message, 70)}`, detail: message });
      setTaskState(task, 'failed', { reason: message });
      return true;
    }

    default:
      return false; // 未知事件：交给调用方决定是否透传
  }
}

function finishTask(task, code) {
  if (!task) return;
  task.exitCode = code;
  task.lastEventAt = Date.now();
  if (task.state === 'stopping') {
    // 用户主动停的：不管退出码，一律报「已停止」
    setTaskState(task, 'stopped', { durationMs: Date.now() - task.startedAt });
  } else if (task.state === 'running') {
    // 没收到 final/error 就退出了：看退出码判定
    if (code === 0) {
      setTaskState(task, 'success', { durationMs: Date.now() - task.startedAt });
    } else {
      setTaskState(task, 'failed', {
        reason: task.errorMessage || clip(task.stderrTail, 200) || `进程退出码 ${code}`,
      });
    }
  }
  phoneBroadcast({ type: 'task-done', data: { taskId: task.taskId, code, prompt: task.prompt } });
  if (lastTaskId === task.taskId) {
    // 保留 lastTaskId，方便手机看最后一个任务的轨迹
  }
  broadcastTasks();
}

// ============================================================
// 二·五、文件上传（P2：base64 文本分片，防穿越 + 限额）
// ============================================================
const uploads = new Map(); // uploadId → { fd, path, name, size, received, hash, taskId, cwd }

function sanitizeUploadName(raw) {
  const base = path.basename(String(raw || '').replace(/\\/g, '/'));
  const cleaned = base.replace(/[\\/:*?"<>|\x00-\x1f]/g, '_').replace(/^\.+/, '_');
  return cleaned.slice(0, 120) || `upload_${Date.now()}`;
}

function uploadTargetPath(cwd, name) {
  const dir = path.join(cwd, 'uploads');
  if (!fs.existsSync(dir)) fs.mkdirSync(dir, { recursive: true });
  const ext = path.extname(name);
  const stem = path.basename(name, ext);
  const stamp = new Date().toISOString().replace(/[-:T]/g, '').slice(0, 14);
  let target = path.join(dir, `${stem}_${stamp}${ext}`);
  let n = 1;
  while (fs.existsSync(target)) target = path.join(dir, `${stem}_${stamp}_${n++}${ext}`);
  return target;
}

function handleUploadBegin(msg, reply) {
  const uploadId = String(msg.uploadId || '').trim();
  if (!uploadId) return reply({ type: 'error', message: 'uploadId 不能为空' });
  if (uploads.has(uploadId)) return reply({ type: 'error', message: `uploadId 重复: ${uploadId}` });

  const size = Number(msg.size || 0);
  if (!Number.isFinite(size) || size < 0) return reply({ type: 'error', message: 'size 非法' });
  if (size > UPLOAD_MAX_BYTES) {
    return reply({ type: 'error', code: 'TOO_LARGE', message: `单文件超过 ${Math.round(UPLOAD_MAX_BYTES / 1048576)}MB` });
  }

  const task = msg.taskId ? tasks.get(String(msg.taskId)) : lastTaskId ? tasks.get(lastTaskId) : null;
  const cwd = task ? task.cwd : resolveCwd(msg.workspace) || ROOT;

  if (task) {
    task.uploadBytes = task.uploadBytes || 0;
    if (task.uploadBytes + size > UPLOAD_TASK_MAX_BYTES) {
      return reply({
        type: 'error',
        code: 'TOO_LARGE',
        message: `单任务累计超过 ${Math.round(UPLOAD_TASK_MAX_BYTES / 1048576)}MB`,
      });
    }
  }

  const name = sanitizeUploadName(msg.name);
  const target = uploadTargetPath(cwd, name);
  let fd;
  try {
    fd = fs.openSync(target, 'w');
  } catch (err) {
    return reply({ type: 'error', message: `无法创建文件: ${err.message}` });
  }

  uploads.set(uploadId, {
    fd,
    path: target,
    name,
    size,
    received: 0,
    hash: crypto.createHash('sha256'),
    taskId: task ? task.taskId : null,
    cwd,
    createdAt: Date.now(),
  });
  log(`[上传] 开始 ${uploadId} name=${name} size=${size} cwd=${cwd}`);
  return reply({ type: 'upload-accept', data: { uploadId, maxChunk: 65536, name } });
}

function handleUploadChunk(msg, reply) {
  const uploadId = String(msg.uploadId || '');
  const st = uploads.get(uploadId);
  if (!st) return reply({ type: 'error', message: `未知 uploadId: ${uploadId}` });

  let data;
  try {
    data = Buffer.from(String(msg.dataB64 || ''), 'base64');
  } catch {
    return reply({ type: 'error', message: 'dataB64 解码失败' });
  }
  st.received += data.length;
  if (st.received > UPLOAD_MAX_BYTES) {
    try {
      fs.closeSync(st.fd);
      fs.unlinkSync(st.path);
    } catch {
      /* ignore */
    }
    uploads.delete(uploadId);
    return reply({ type: 'error', code: 'TOO_LARGE', message: '超出单文件上限，已中止' });
  }

  try {
    fs.writeSync(st.fd, data);
  } catch (err) {
    return reply({ type: 'error', message: `写入失败: ${err.message}` });
  }
  st.hash.update(data);

  // 每 512KB 回一次进度，避免刷屏
  if (st.received >= st.size || st.received % (512 * 1024) < data.length) {
    phoneBroadcast({ type: 'upload-progress', data: { uploadId, received: st.received, size: st.size } });
  }
  return null;
}

function handleUploadEnd(msg, reply) {
  const uploadId = String(msg.uploadId || '');
  const st = uploads.get(uploadId);
  if (!st) return reply({ type: 'error', message: `未知 uploadId: ${uploadId}` });

  uploads.delete(uploadId);
  try {
    fs.closeSync(st.fd);
  } catch {
    /* ignore */
  }

  if (st.size > 0 && st.received !== st.size) {
    try {
      fs.unlinkSync(st.path);
    } catch {
      /* ignore */
    }
    log(`[上传] 尺寸不符 ${uploadId}: 收到 ${st.received} / 应为 ${st.size}，已删除`);
    return reply({ type: 'error', message: `尺寸不符：收到 ${st.received} / 应为 ${st.size}` });
  }

  const sha256 = st.hash.digest('hex');
  if (msg.sha256 && String(msg.sha256).toLowerCase() !== sha256) {
    try {
      fs.unlinkSync(st.path);
    } catch {
      /* ignore */
    }
    return reply({ type: 'error', message: 'sha256 校验失败，已删除文件' });
  }

  const task = st.taskId ? tasks.get(st.taskId) : null;
  if (task) task.uploadBytes = (task.uploadBytes || 0) + st.received;

  log(`[上传] 完成 ${uploadId} → ${st.path} (${st.received} 字节)`);
  return reply({
    type: 'upload-done',
    data: { uploadId, path: st.path, name: st.name, size: st.received, sha256, taskId: st.taskId },
  });
}

function handleUploadAbort(msg, reply) {
  const uploadId = String(msg.uploadId || '');
  const st = uploads.get(uploadId);
  if (!st) return reply({ type: 'error', message: `未知 uploadId: ${uploadId}` });
  uploads.delete(uploadId);
  try {
    fs.closeSync(st.fd);
    fs.unlinkSync(st.path);
  } catch {
    /* ignore */
  }
  log(`[上传] 已中止 ${uploadId}`);
  return reply({ type: 'upload-aborted', data: { uploadId } });
}

function attachmentBlock(paths) {
  if (!paths || !paths.length) return '';
  const lines = paths.map((p) => `- ${p}`).join('\n');
  return `\n\n附件（本地文件，请用工具读取）：\n${lines}`;
}

// ============================================================
// 三、审批通道（dsh 插件 ⇄ 本服务，TCP :8787）
// ============================================================
function answerPlugin(socket, outcome) {
  try {
    socket.write(JSON.stringify({ type: 'approval-answer', outcome }) + '\n');
    socket.end();
  } catch (err) {
    log(`[审批] 回写插件失败: ${err.message}`);
  }
}

/** 猜测审批属于哪个任务：优先 cwd 匹配，其次唯一运行任务，再次最近任务 */
function attributeTask(msg) {
  const running = runningTasks();
  if (msg && msg.cwd) {
    const want = path.resolve(String(msg.cwd)).toLowerCase();
    const hit = running.find((t) => path.resolve(t.cwd).toLowerCase() === want);
    if (hit) return hit;
  }
  if (running.length === 1) return running[0];
  if (lastTaskId && tasks.has(lastTaskId)) {
    const t = tasks.get(lastTaskId);
    if (t.state === 'running') return t;
  }
  return running[running.length - 1] || null;
}

function handleApprovalRequest(msg, socket) {
  const id = msg.id || msg.callId || `apr-${Date.now()}`;
  const task = attributeTask(msg);
  const request = {
    id,
    taskId: task ? task.taskId : null,
    toolName: msg.toolName || '?',
    reason: msg.reason || '',
    // 老大要的展示格式：[工作区名] - [会话简述] - [审批内容]
    workspace: task ? task.workspaceTitle : workspaceTitleOf(msg.cwd || ''),
    sessionBrief: task ? clip(task.prompt, 40) : '-',
    origin: msg.origin || 'headless',
    createdAt: Date.now(),
  };
  log(
    `[审批] 收到 id=${id} tool=${request.toolName} task=${request.taskId || '-'} ` +
      `workspace=${request.workspace}`
  );

  // —— fail-closed：手机不在线，直接拒绝，不让 headless 干等 ——
  if (phones.size === 0) {
    log('[审批] 手机不在线 → 自动 rejected（fail-closed）');
    answerPlugin(socket, 'rejected');
    return;
  }

  const entry = { socket, request, timer: null, taskId: request.taskId };
  pending.set(id, entry);
  phoneBroadcast({
    type: 'approval-request',
    data: { ...request, timeoutMs: APPROVAL_TIMEOUT_MS },
  });

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
// 四、任务执行（spawn dsh headless --json，多任务 / 会话续跑）
// ============================================================
function startTask({ prompt, workspace, sessionId, newTask, attachments }, reply) {
  if (runningTasks().length >= MAX_TASKS) {
    return reply({
      type: 'error',
      code: 'BUSY',
      message: `已有 ${MAX_TASKS} 个任务在跑，先停一个（action:'stop'）`,
    });
  }
  if (!apiKey) {
    return reply({ type: 'error', message: '缺少 DEEPSEEK_API_KEY（请写入项目根 .env）' });
  }

  const cwd = resolveCwd(workspace);
  if (!cwd) {
    return reply({ type: 'error', message: `工作区不可用（路径不存在）：${workspace}` });
  }

  // 续会话：只有已存在的 sessionId 才传 --session-id（新会话必须省略，实测会报 does not exist）
  const resumeId = sessionId ? String(sessionId).trim() : '';
  // P2：附件路径自动拼进 prompt
  const attachPaths = (Array.isArray(attachments) ? attachments : [])
    .map((id) => {
      const st = uploads.get(String(id));
      return st && fs.existsSync(st.path) ? st.path : null;
    })
    .filter(Boolean);
  const finalPrompt = String(prompt) + attachmentBlock(attachPaths);

  const task = {
    taskId: newTaskId(),
    label: '',
    sessionId: resumeId || null,
    workspace: workspace || cwd,
    workspaceTitle: workspaceTitleOf(cwd),
    cwd,
    prompt: String(prompt),
    attachments: attachPaths,
    uploadBytes: 0,
    startedAt: Date.now(),
    lastEventAt: Date.now(),
    state: 'running',
    turn: 0,
    step: 0,
    trace: [],
    stderrTail: '',
    exitCode: null,
    errorMessage: '',
  };
  task.label = `${task.taskId}·${clip(task.prompt, 16)}`;

  const childEnv = { ...process.env, DEEPSEEK_API_KEY: apiKey, DEEPSEEK_BASE_URL: baseUrl };
  // shell:true 时 Node 只是把 argv 用空格拼起来，prompt 里带空格/连字符会被 dsh 当成自己的参数
  // （例如 "ping 127.0.0.1 -n 5" 会报 unknown option '-n'）→ 自己拼命令串并给 prompt 加引号
  const quotedPrompt = '"' + finalPrompt.replace(/"/g, '\\"') + '"';
  const cmdLine = resumeId
    ? `dsh --profile headless --session-id ${resumeId} --json ${quotedPrompt}`
    : `dsh --profile headless --json ${quotedPrompt}`;
  let proc;
  try {
    proc = spawn(cmdLine, { cwd, env: childEnv, shell: true });
  } catch (err) {
    // spawn 被策略拒绝（EPERM/EACCES/ENOENT）时绝不能把整个服务进程带崩
    const msg = `启动 dsh 子进程失败：${err && err.message ? err.message : String(err)}`;
    log(`[任务] ${task.taskId} ${msg}`);
    task.state = 'failed';
    task.errorMessage = msg;
    tasks.set(task.taskId, task);
    lastTaskId = task.taskId;
    reply({ type: 'error', code: 'SPAWN_FAILED', message: msg, data: { taskId: task.taskId } });
    broadcastTasks();
    return;
  }
  task.proc = proc;
  task.pid = proc.pid;
  tasks.set(task.taskId, task);
  lastTaskId = task.taskId;

  log(`[任务] ${task.taskId} 启动 pid=${proc.pid} cwd=${cwd}${resumeId ? ` 续会话 ${resumeId}` : ''}: ${clip(prompt, 60)}`);
  reply({
    type: 'task-started',
    data: { taskId: task.taskId, prompt: task.prompt, pid: proc.pid, cwd, workspaceTitle: task.workspaceTitle, sessionId: task.sessionId },
  });
  setTaskState(task, 'running', { phase: 'task_started' });
  broadcastTasks();

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
        phoneBroadcast({ type: 'stdout', data: { taskId: task.taskId, text: line.slice(0, 4000) } });
        continue;
      }
      const consumed = normalizeEvent(task, event);
      // 兼容老客户端：仍透传原始 event（P3 前端改用 trace 后可关掉）
      phoneBroadcast({ type: 'event', data: { ...event, taskId: task.taskId, consumed } });
    }
  });

  proc.stderr.on('data', (chunk) => {
    const text = chunk.toString('utf8').trim();
    if (!text) return;
    task.stderrTail = (task.stderrTail + ' ' + text).slice(-2000);
    phoneBroadcast({ type: 'stderr', data: { taskId: task.taskId, text: text.slice(0, 4000) } });
  });

  proc.on('error', (err) => {
    log(`[任务] ${task.taskId} spawn 失败: ${err.message}`);
    task.errorMessage = `spawn 失败: ${err.message}`;
    phoneBroadcast({ type: 'error', data: { taskId: task.taskId, message: task.errorMessage } });
    setTaskState(task, 'failed', { reason: task.errorMessage });
  });

  proc.on('close', (code) => {
    log(`[任务] ${task.taskId} 结束 code=${code}`);
    finishTask(task, code);
  });

  return null;
}

function stopTask(taskId, reply) {
  const id = taskId || lastTaskId;
  const task = id ? tasks.get(id) : null;
  if (!task) return reply({ type: 'error', message: '没有可停止的任务' });
  if (task.state !== 'running') return reply({ type: 'error', message: `任务 ${task.taskId} 已结束（${task.state}）` });

  const pid = task.pid;
  try {
    if (process.platform === 'win32') {
      // shell:true 下 proc 只是 cmd.exe 包装，必须 taskkill /T 连孙子进程一起杀
      spawn('taskkill', ['/pid', String(pid), '/T', '/F'], { stdio: 'ignore' });
    } else {
      task.proc.kill('SIGTERM');
    }
    task.state = 'stopping';
    log(`[任务] ${task.taskId} 停止指令已发 pid=${pid}`);
  } catch (err) {
    log(`[任务] ${task.taskId} kill 失败: ${err.message}`);
  }
  phoneBroadcast({ type: 'task-state', data: { taskId: task.taskId, state: 'stopping' } });
  return reply({ type: 'task-stopping', data: { taskId: task.taskId, pid } });
}

// —— 看门狗：长时间无事件的任务判失败并回收 ——
setInterval(() => {
  const now = Date.now();
  for (const task of tasks.values()) {
    if (task.state !== 'running') continue;
    if (now - task.lastEventAt > IDLE_TIMEOUT_MS) {
      log(`[任务] ${task.taskId} ${Math.round(IDLE_TIMEOUT_MS / 1000)}s 无事件 → 判失败`);
      task.errorMessage = `任务无响应超时（${Math.round(IDLE_TIMEOUT_MS / 60000)} 分钟无任何事件）`;
      pushTrace(task, { kind: 'error', brief: '无响应超时', detail: task.errorMessage });
      try {
        if (process.platform === 'win32') {
          spawn('taskkill', ['/pid', String(task.pid), '/T', '/F'], { stdio: 'ignore' });
        } else {
          task.proc.kill('SIGKILL');
        }
      } catch {
        /* ignore */
      }
      setTaskState(task, 'failed', { reason: task.errorMessage });
    }
  }
}, 30000).unref();

// ============================================================
// 五、HTTP 接口
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
      version: '3.2-p2',
      port: PORT,
      approvalPort: APPROVAL_PORT,
      phones: phones.size,
      pendingApprovals: pending.size,
      tasks: tasksSnapshot(),
      running: runningTasks().length,
      maxTasks: MAX_TASKS,
      taskRunning: runningTasks().length > 0,
    });
  }

  if (req.method === 'GET' && url.pathname === '/tasks') {
    return sendHttpJson(res, 200, { ok: true, tasks: tasksSnapshot() });
  }

  if (req.method === 'GET' && url.pathname === '/workspaces') {
    return sendHttpJson(res, 200, { ok: true, workspaces: listWorkspaces() });
  }

  if (req.method === 'POST' && url.pathname === '/workspaces/create') {
    let raw = '';
    req.on('data', (c) => {
      raw += c;
      if (raw.length > 1e6) req.destroy();
    });
    req.on('end', () => {
      let input = {};
      try {
        input = JSON.parse(raw || '{}');
      } catch {
        return sendHttpJson(res, 400, { ok: false, error: '无效 JSON' });
      }
      try {
        const ws = createWorkspace(input.path);
        return sendHttpJson(res, 200, { ok: true, workspace: ws });
      } catch (err) {
        return sendHttpJson(res, 400, { ok: false, error: err.message });
      }
    });
    return null;
  }

  return sendHttpJson(res, 404, { error: 'not_found' });
});

// ============================================================
// 六、WebSocket（零依赖，手写 RFC 6455 最小实现；手机端）
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
  sendWsJson({ type: 'hello', data: { tasks: tasksSnapshot(), maxTasks: MAX_TASKS, workspaces: listWorkspaces() } });

  function handlePhoneMessage(text) {
    let msg;
    try {
      msg = JSON.parse(text);
    } catch {
      return sendWsJson({ type: 'error', message: '无效 JSON' });
    }

    switch (msg.action) {
      case 'run': {
        if (!msg.prompt || !String(msg.prompt).trim()) {
          return sendWsJson({ type: 'error', message: 'prompt 不能为空' });
        }
        return startTask(
          {
            prompt: String(msg.prompt).trim(),
            workspace: msg.workspace,
            sessionId: msg.sessionId,
            newTask: Boolean(msg.newTask),
            attachments: msg.attachments,
          },
          sendWsJson
        );
      }

      // —— P2：文件上传（begin / chunk / end / abort）——
      case 'upload-begin':
        return handleUploadBegin(msg, sendWsJson);

      case 'upload-chunk':
        return handleUploadChunk(msg, sendWsJson);

      case 'upload-end':
        return handleUploadEnd(msg, sendWsJson);

      case 'upload-abort':
        return handleUploadAbort(msg, sendWsJson);

      case 'tasks':
        return sendWsJson({ type: 'tasks', data: tasksSnapshot() });

      case 'switch-task': {
        const task = msg.taskId ? tasks.get(msg.taskId) : null;
        if (!task) return sendWsJson({ type: 'error', message: `任务不存在: ${msg.taskId}` });
        lastTaskId = task.taskId;
        return sendWsJson({
          type: 'task-trace',
          data: {
            taskId: task.taskId,
            state: task.state,
            sessionId: task.sessionId,
            cwd: task.cwd,
            workspaceTitle: task.workspaceTitle,
            prompt: task.prompt,
            entries: task.trace,
          },
        });
      }

      case 'workspaces':
        return sendWsJson({ type: 'workspaces', data: listWorkspaces() });

      case 'create-workspace':
        try {
          const ws = createWorkspace(msg.path);
          sendWsJson({ type: 'workspace-created', data: ws });
          return sendWsJson({ type: 'workspaces', data: listWorkspaces() });
        } catch (err) {
          return sendWsJson({ type: 'error', message: err.message });
        }

      case 'stop':
        return stopTask(msg.taskId, sendWsJson);

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
        phoneBroadcast({ type: 'approval-resolved', data: { id, outcome, taskId: entry.taskId, decidedBy: peer } });
        return sendWsJson({ type: 'approval-resolved', data: { id, outcome, taskId: entry.taskId } });
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
// 七、启动
// ============================================================
// 兜底：任何漏网异常都不许把中转服务带崩（崩溃＝手机立即断连，必须人工重启）
process.on('uncaughtException', (err) => {
  log(`[致命] 未捕获异常（已拦截，服务继续运行）: ${err && err.stack ? err.stack.split('\n').slice(0, 4).join(' | ') : String(err)}`);
});
process.on('unhandledRejection', (reason) => {
  log(`[警告] 未处理的 Promise 拒绝（已拦截）: ${reason && reason.message ? reason.message : String(reason)}`);
});

server.listen(PORT, '0.0.0.0', () => {
  log(`DSH 中转服务已启动: http://0.0.0.0:${PORT}  (WebSocket 同端口，接受外部请求)`);
});
approvalServer.listen(APPROVAL_PORT, '127.0.0.1', () => {
  log(`审批通道已监听: tcp://127.0.0.1:${APPROVAL_PORT}  (dsh-approval-bridge 连这里)`);
});
log(`工作区: ${ROOT}`);
log(`API Key: ${apiKey ? '已从 ' + (process.env.DEEPSEEK_API_KEY ? '启动环境' : '.env') + ' 读取' : '⚠ 未设置'}`);
log(`端点: ${baseUrl}`);
log(`并发上限: ${MAX_TASKS} 个任务 / 轨迹 ${TRACE_MAX} 条 / 空闲超时 ${Math.round(IDLE_TIMEOUT_MS / 60000)} 分钟`);
