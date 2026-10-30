// dsh-approval-bridge —— 极简审批应答器（阶段二/三最小闭环验证版）
// 监听 approval/request，经本地 TCP 通道转发给测试脚本（模拟手机端），
// 15 秒超时兜底 rejected；收到 allowed-once 则放行。
import net from 'node:net';

export const name = 'dsh-approval-bridge';
export const inject = [];

export function apply(ctx, config) {
  console.log('[bridge] 审批插件 apply 已执行');
  ctx.on('approval/request', async (req) => {
    // sandbox 提权请求没有 id 字段，用 callId 兜底
    const reqId = req?.id ?? req?.callId ?? '(no-id)';
    console.log('[bridge] 拦截到审批请求:', reqId, '| tool:', req?.toolName, '| reason:', req?.reason);

    return new Promise((resolve) => {
      // 15 秒超时兜底（测试期设短；正式默认 600 秒）
      const timer = setTimeout(() => {
        console.log('[bridge] 审批超时，返回 rejected');
        resolve('rejected');
      }, 15000);

      // 本地 TCP 模拟通道（测试期用，正式版换 WS + 心跳）
      const client = net.connect(8787, '127.0.0.1', () => {
        client.write(JSON.stringify({ type: 'approval-request', id: reqId, toolName: req?.toolName }) + '\n');
      });

      client.on('data', (data) => {
        let msg;
        try {
          msg = JSON.parse(data.toString().trim());
        } catch {
          return; // 粘包/半包：等下一次
        }
        if (msg.type === 'approval-answer') {
          clearTimeout(timer);
          client.end();
          console.log('[bridge] 收到放行指令:', msg.outcome);
          resolve(msg.outcome); // 'allowed-once' 或 'rejected'
        }
      });

      client.on('error', (err) => {
        clearTimeout(timer);
        console.error('[bridge] TCP 连接错误:', err.message);
        resolve('rejected');
      });
    });
  });
}
