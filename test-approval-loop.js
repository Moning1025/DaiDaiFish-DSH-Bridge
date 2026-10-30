// test-approval-loop.js —— 阶段二/三最小闭环验证
// 起本地 TCP 服务器模拟 WS 通道；spawn `dsh --profile headless --json` 跑一个故意跨工作区的任务；
// 审批请求过来时打印并回 allowed-once。
const net = require('net');
const { spawn } = require('child_process');
const { EventEmitter } = require('events');
const fs = require('fs');
const path = require('path');

const emitter = new EventEmitter();
const ROOT = __dirname;

const server = net.createServer((socket) => {
  let buffer = '';
  socket.on('data', (data) => {
    buffer += data.toString();
    const lines = buffer.split('\n');
    buffer = lines.pop();
    for (const line of lines) {
      if (!line.trim()) continue;
      let msg;
      try {
        msg = JSON.parse(line);
      } catch {
        continue;
      }
      emitter.emit('message', msg, socket);
    }
  });
});

server.listen(8787, '127.0.0.1', () => {
  console.log('[test] TCP 模拟 WS 服务器已启动 127.0.0.1:8787');

  emitter.on('message', (msg, socket) => {
    if (msg.type === 'approval-request') {
      console.log('[test] 拦截到审批，发送 allowed-once');
      socket.write(JSON.stringify({ type: 'approval-answer', outcome: 'allowed-once' }) + '\n');
    }
  });

  // 读 .env 里的 key（注意：DSH app-boot 禁止 .env 设置 DEEPSEEK_BASE_URL，必须走启动环境）
  const envContent = fs.readFileSync(path.join(ROOT, '.env'), 'utf8');
  const apiKey = envContent.match(/DEEPSEEK_API_KEY=(.+)/)[1].trim();

  // 中转站端点由启动环境注入
  const baseUrl = process.env.DSH_TEST_BASE_URL || 'https://api.kouxianl.com/v1';

  // spawn headless（故意写工作区外文件，触发审批）
  const childEnv = { ...process.env, DEEPSEEK_API_KEY: apiKey, DEEPSEEK_BASE_URL: baseUrl };
  const child = spawn('dsh', ['--profile', 'headless', '--json', '用终端在 C:\\Windows\\Temp 下创建 approval-test.txt 并写入 hello'], {
    env: childEnv,
    shell: true,
    cwd: ROOT,
  });

  // 全局兜底：180 秒没结束就强杀，避免挂死
  const globalTimer = setTimeout(() => {
    console.log('[test] 全局超时 180s，强杀 headless');
    try { child.kill('SIGKILL'); } catch {}
    server.close();
  }, 180000);

  child.stdout.on('data', (data) => {
    for (const line of data.toString().split('\n')) {
      if (!line.trim()) continue;
      try {
        const event = JSON.parse(line);
        console.log('[headless]', JSON.stringify(event).slice(0, 800));
      } catch {
        console.log('[stdout]', line); // 插件 console.log 从这可见
      }
    }
  });

  child.stderr.on('data', (data) => {
    console.log('[stderr]', data.toString().trimEnd());
  });

  child.on('error', (err) => {
    console.log('[test] spawn 失败:', err.message);
    clearTimeout(globalTimer);
    server.close();
  });

  child.on('close', (code) => {
    clearTimeout(globalTimer);
    console.log('[test] headless 退出，code:', code);
    server.close();
  });
});
