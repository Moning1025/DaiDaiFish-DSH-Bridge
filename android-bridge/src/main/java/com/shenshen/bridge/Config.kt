package com.shenshen.bridge

/**
 * 编译期默认配置。
 *
 * 脱敏原则：这里只放"连哪里"，不放任何密钥。
 * 密钥（DEEPSEEK_API_KEY 等）只存在于电脑端 bridge/.env，手机端完全不接触。
 *
 * 换电脑 / 换 Tailscale IP 时只改这一处，重新 build 即可。
 */
object Config {

    /** 电脑端 bridge/server.json 的 WebSocket 端口（Tailscale 内网 IP）。 */
    const val DEFAULT_SERVER_URL = "ws://100.93.161.33:8080"

    /** 审批超时（毫秒），与电脑端 server.js 的 APPROVAL_TIMEOUT_MS 对齐。 */
    const val APPROVAL_TIMEOUT_MS = 15_000L
}
