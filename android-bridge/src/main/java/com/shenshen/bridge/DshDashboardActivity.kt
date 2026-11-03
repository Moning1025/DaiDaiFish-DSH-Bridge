package com.shenshen.bridge

import android.app.Activity
import android.app.AlertDialog
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.RenderEffect
import android.graphics.Shader
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.animation.AccelerateDecelerateInterpolator
import android.animation.ValueAnimator
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast

/**
 * DSH 掌上控制台 v3 · 深海鲸鱼娘主题（纯代码绘制，无 XML、无 androidx）。
 *
 * 结构：
 *   FrameLayout
 *     ├─ ImageView   assets/bg.jpg（沉浸背景，API 31+ 轻微模糊）
 *     ├─ View        #B3000000 黑色遮罩（保证文字绝对清晰）
 *     └─ ScrollView  半透明卡片内容
 *
 * 卡片：连接状态（呼吸光环）/ 工作区选择器 / 待审批 / 下发任务 / 执行轨迹（等宽字体）
 */
class DshDashboardActivity : Activity(), BridgeState.Refreshable {

    private lateinit var glow: View
    private lateinit var dot: View
    private lateinit var statusText: TextView
    private lateinit var approvalHeader: TextView
    private lateinit var approvalBox: LinearLayout
    private lateinit var traceHeader: TextView
    private lateinit var traceBox: LinearLayout
    private lateinit var taskStateText: TextView
    private lateinit var cwdText: TextView
    private lateinit var wsPathText: TextView
    private lateinit var wsSpinner: Spinner
    private lateinit var promptInput: EditText

    private var traceExpanded = false
    private var lastTraceSig: String = ""
    private var lastWsSig: String = ""
    private var spinnerSyncing = false
    private var glowAnimator: ValueAnimator? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        BridgeService.start(this)
        BridgeHooks.attachContext(this)
        setContentView(buildRoot())
        BridgeState.addRefreshable(this)
        refresh()
        BridgeClient.shared?.requestWorkspaces()
    }

    override fun onResume() {
        super.onResume()
        BridgeState.addRefreshable(this)
        refresh()
        startGlow()
    }

    override fun onPause() {
        BridgeState.removeRefreshable(this)
        stopGlow()
        super.onPause()
    }

    override fun onDestroy() {
        stopGlow()
        super.onDestroy()
    }

    // ------------------------------------------------------------ 背景

    private fun buildRoot(): View {
        val frame = FrameLayout(this).apply { setBackgroundColor(COL_BG) }

        val bg = ImageView(this).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            runCatching {
                assets.open("bg.jpg").use { stream ->
                    BitmapFactory.decodeStream(stream)?.let { setImageBitmap(it) }
                }
            }.onFailure { /* 没图就纯色底，不影响可用 */ }
        }
        if (Build.VERSION.SDK_INT >= 31) {
            runCatching {
                bg.setRenderEffect(
                    RenderEffect.createBlurEffect(22f, 22f, Shader.TileMode.CLAMP),
                )
            }
        }
        frame.addView(bg, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT,
        ))

        // 黑色遮罩 #B3000000 —— 背景亮，必须压暗才能读清字
        frame.addView(View(this).apply { setBackgroundColor(COL_SCRIM) }, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT,
        ))

        val scroll = ScrollView(this).apply { isFillViewport = true }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(22), dp(16), dp(28))
        }
        scroll.addView(root)
        frame.addView(scroll, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT,
        ))

        // ---- 标题
        root.addView(text("DSH 控制台", 24f, COL_TITLE, bold = true))
        root.addView(text("深海之下 · 鲸落之上 · 审批在你指尖", 12f, COL_DIM).apply {
            setPadding(0, dp(5), 0, dp(18))
        })

        // ---- 连接状态（呼吸光环 + 圆点）
        val statusRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val dotHolder = FrameLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(dp(26), dp(26)).apply { rightMargin = dp(10) }
        }
        glow = View(this).apply {
            background = circle(COL_ACCENT)
            alpha = 0.25f
            layoutParams = FrameLayout.LayoutParams(dp(14), dp(14)).apply {
                gravity = Gravity.CENTER
            }
        }
        dot = View(this).apply {
            background = circle(COL_DIM)
            layoutParams = FrameLayout.LayoutParams(dp(10), dp(10)).apply {
                gravity = Gravity.CENTER
            }
        }
        dotHolder.addView(glow)
        dotHolder.addView(dot)
        statusText = text("未连接", 14f, COL_TEXT).apply {
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        val reconnectBtn = smallButton("重连", COL_ACCENT) {
            val c = BridgeClient.shared
            if (c != null) c.reconnect() else {
                BridgeService.start(this)
                toast("正在启动桥接服务…")
            }
        }
        statusRow.addView(dotHolder)
        statusRow.addView(statusText)
        statusRow.addView(reconnectBtn)
        root.addView(card(statusRow))

        // ---- 工作区选择器
        root.addView(sectionLabel("工作区"))
        val wsCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(8), dp(14), dp(12))
            background = cardBackground()
        }
        val wsRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        wsSpinner = Spinner(this).apply {
            background = roundRect(COL_CARD2, dp(10), COL_STROKE)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        wsRow.addView(wsSpinner)
        wsRow.addView(smallButton("⟳", COL_ACCENT) {
            val ok = BridgeClient.shared?.requestWorkspaces() ?: false
            toast(if (ok) "正在拉取工作区…" else "未连接电脑端")
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            leftMargin = dp(8)
        })
        val newWsBtn = smallButton("+ 新建工作区", COL_ACCENT) { showNewWorkspaceDialog() }
        wsRow.addView(newWsBtn, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            leftMargin = dp(8)
        })
        wsCard.addView(wsRow)
        wsPathText = text("当前：—", 11f, COL_DIM).apply { setPadding(dp(2), dp(8), 0, 0) }
        wsCard.addView(wsPathText)
        root.addView(wsCard)

        // ---- 待审批
        approvalHeader = sectionLabel("待审批")
        root.addView(approvalHeader)
        approvalBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(approvalBox)

        // ---- 下发任务
        root.addView(sectionLabel("下发任务"))
        promptInput = EditText(this).apply {
            hint = "给电脑端的 DSH 说点什么…"
            setHintTextColor(COL_DIM)
            setTextColor(COL_TEXT)
            textSize = 14f
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            minLines = 2
            maxLines = 4
            setPadding(dp(12), dp(10), dp(12), dp(10))
            background = roundRect(COL_CARD2, dp(12), COL_STROKE)
        }
        root.addView(promptInput)

        val actionRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(10), 0, 0)
        }
        actionRow.addView(primaryButton("发 送", COL_ACCENT, isGradient = true) { sendPrompt() },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.4f))
        actionRow.addView(primaryButton("停 止", COL_DANGER) {
            val ok = BridgeClient.shared?.sendStop() ?: false
            toast(if (ok) "已发送停止指令" else "未连接或服务未就绪")
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
            leftMargin = dp(10)
        })
        root.addView(actionRow)

        taskStateText = text("空闲", 12f, COL_DIM).apply { setPadding(dp(2), dp(10), 0, 0) }
        root.addView(taskStateText)

        // ---- 执行轨迹（可折叠、等宽字体）
        traceHeader = sectionLabel("执行轨迹 (0) ▶").apply {
            isClickable = true
            setOnClickListener {
                traceExpanded = !traceExpanded
                traceBox.visibility = if (traceExpanded) View.VISIBLE else View.GONE
                renderTraceHeader()
            }
        }
        root.addView(traceHeader)
        traceBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(10), dp(10), dp(10), dp(10))
            background = roundRect(COL_TRACE, dp(12), COL_STROKE)
            visibility = View.GONE
        }
        root.addView(traceBox)

        // ---- 工作区底部提示（任务真实 cwd）
        cwdText = text("任务 cwd：—", 11f, COL_DIM).apply { setPadding(dp(2), dp(18), 0, 0) }
        root.addView(cwdText)

        return frame
    }

    // ------------------------------------------------------------ 呼吸光环

    private fun startGlow() {
        if (glowAnimator != null) return
        glowAnimator = ValueAnimator.ofFloat(0.12f, 0.5f).apply {
            duration = 1600
            repeatCount = ValueAnimator.INFINITE
            repeatMode = ValueAnimator.REVERSE
            interpolator = AccelerateDecelerateInterpolator()
            addUpdateListener { a ->
                val v = a.animatedValue as Float
                glow.alpha = v
                val s = 1f + v
                glow.scaleX = s
                glow.scaleY = s
            }
            start()
        }
    }

    private fun stopGlow() {
        glowAnimator?.cancel()
        glowAnimator = null
    }

    // ------------------------------------------------------------ 刷新

    override fun refresh() {
        runOnUiThread {
            renderStatus()
            renderWorkspaces()
            renderApproval()
            renderTaskState()
            renderTraceHeader()
            if (traceExpanded) renderTraceBody()
            cwdText.text = "任务 cwd：" + (BridgeState.lastCwd ?: "—")
        }
    }

    private fun renderStatus() {
        val (color, label) = when (BridgeState.status) {
            BridgeState.Status.CONNECTED -> COL_OK to "已连接电脑端"
            BridgeState.Status.CONNECTING -> COL_WARN to "正在连接…"
            BridgeState.Status.RECONNECTING -> COL_WARN to "重连中…"
            BridgeState.Status.DISCONNECTED -> COL_DANGER to "未连接"
        }
        dot.background = circle(color)
        glow.background = circle(color)
        val err = BridgeState.lastError
        statusText.text = if (err.isNullOrBlank()) label else "$label · $err"
        statusText.setTextColor(if (err.isNullOrBlank()) COL_TEXT else COL_WARN)
    }

    private fun renderWorkspaces() {
        val list = BridgeState.workspaces
        val sig = list?.joinToString("|") { it.id + it.path } ?: "null"
        if (sig != lastWsSig) {
            lastWsSig = sig
            spinnerSyncing = true
            val labels = (list ?: emptyList()).map { ws ->
                (if (ws.exists) "" else "⚠ ") + ws.title
            }
            val adapter = object : ArrayAdapter<String>(this, android.R.layout.simple_spinner_item, labels) {
                override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                    val tv = (super.getView(position, convertView, parent) as TextView)
                    tv.setTextColor(COL_TEXT)
                    tv.textSize = 13f
                    tv.setPadding(dp(10), dp(8), dp(10), dp(8))
                    return tv
                }

                override fun getDropDownView(position: Int, convertView: View?, parent: ViewGroup): View {
                    val tv = (super.getDropDownView(position, convertView, parent) as TextView)
                    tv.setTextColor(COL_TEXT)
                    tv.textSize = 13f
                    tv.setBackgroundColor(COL_CARD_SOLID)
                    tv.setPadding(dp(10), dp(10), dp(10), dp(10))
                    return tv
                }
            }
            wsSpinner.adapter = adapter
            wsSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                    if (spinnerSyncing) return
                    val ws = BridgeState.workspaces?.getOrNull(position) ?: return
                    BridgeState.selectWorkspace(ws.path)
                }

                override fun onNothingSelected(parent: AdapterView<*>?) = Unit
            }
            // 回填当前选中项
            val sel = BridgeState.selectedWorkspace
            val idx = (BridgeState.workspaces ?: emptyList()).indexOfFirst { it.path == sel }
            if (idx >= 0) wsSpinner.setSelection(idx)
            spinnerSyncing = false
        }
        wsPathText.text = "当前：" + (BridgeState.selectedWorkspace ?: "未选择（用服务端默认目录）")
        if (list == null) wsPathText.text = "当前：正在拉取工作区…"
    }

    private fun renderApproval() {
        approvalBox.removeAllViews()
        val pending = BridgeState.pendingApproval
        if (pending == null) {
            approvalHeader.text = "待审批"
            approvalBox.addView(text("此刻海面平静，没有待审批请求", 12f, COL_DIM).apply {
                setPadding(dp(2), 0, 0, dp(2))
            })
            return
        }
        approvalHeader.text = "待审批 (1)"
        val cardView = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(12), dp(14), dp(12))
            background = roundRect(COL_CARD_WARN, dp(14), COL_WARN)
        }
        cardView.addView(text("${pending.toolName.ifBlank { "工具" }} 请求放行", 14f, COL_TEXT, bold = true))
        if (pending.reason.isNotBlank()) {
            cardView.addView(text(pending.reason, 12f, COL_SUB).apply {
                setPadding(0, dp(6), 0, 0)
            })
        }
        cardView.addView(text("${pending.timeoutMs / 1000} 秒无响应将自动拒绝", 11f, COL_DIM).apply {
            setPadding(0, dp(6), 0, 0)
        })

        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(10), 0, 0)
        }
        row.addView(primaryButton("允许一次", COL_OK) {
            decide(pending.id, Config.DECISION_ALLOW_ONCE)
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        row.addView(primaryButton("拒绝", COL_DANGER) {
            decide(pending.id, Config.DECISION_REJECT)
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
            leftMargin = dp(10)
        })
        cardView.addView(row)
        approvalBox.addView(cardView)
    }

    private fun renderTaskState() {
        taskStateText.text = if (BridgeState.taskRunning) "任务执行中…" else "空闲"
        taskStateText.setTextColor(if (BridgeState.taskRunning) COL_WARN else COL_DIM)
    }

    private fun renderTraceHeader() {
        val n = BridgeState.trace().size
        traceHeader.text = "执行轨迹 ($n) " + if (traceExpanded) "▼" else "▶"
    }

    private fun renderTraceBody() {
        val entries = BridgeState.trace()
        val sig = entries.joinToString("|") { it.at.toString() + it.kind }
        if (sig == lastTraceSig && traceBox.childCount == entries.size) return
        lastTraceSig = sig
        traceBox.removeAllViews()
        if (entries.isEmpty()) {
            traceBox.addView(text("暂无轨迹（发出任务后这里会滚动出现）", 12f, COL_DIM).apply {
                setTypeface(Typeface.MONOSPACE)
            })
            return
        }
        for (e in entries) {
            val row = TextView(this).apply {
                text = "▶ " + e.brief
                setTextColor(if (e.kind == "tool_call") COL_ACCENT else COL_SUB)
                textSize = 11.5f
                typeface = Typeface.MONOSPACE
                setPadding(dp(6), dp(7), dp(6), dp(7))
                isClickable = true
                setOnClickListener {
                    text = if (text.startsWith("▶")) {
                        "▼ " + e.brief + "\n" + e.detail
                    } else {
                        "▶ " + e.brief
                    }
                }
            }
            traceBox.addView(row, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { bottomMargin = dp(4) })
        }
    }

    // ------------------------------------------------------------ 动作

    private fun sendPrompt() {
        val prompt = promptInput.text?.toString()?.trim().orEmpty()
        if (prompt.isEmpty()) {
            toast("先写点任务内容")
            return
        }
        val client = BridgeClient.shared
        if (client == null) {
            BridgeService.start(this)
            toast("桥接服务未就绪，正在启动…")
            return
        }
        val ws = BridgeState.selectedWorkspace
        val ok = client.sendRun(prompt, ws)
        if (ok) {
            promptInput.setText("")
            toast(if (ws.isNullOrBlank()) "已下发（默认工作区）" else "已下发到 " + ws.substringAfterLast('\\'))
        } else {
            toast("未连接电脑端")
        }
    }

    private fun decide(id: String, decision: String) {
        val ok = BridgeClient.shared?.sendApproval(id, decision) ?: false
        if (ok) {
            ApprovalNotifier.cancelApproval(this)
            BridgeState.clearPendingApproval()
        } else {
            toast("回包失败：连接已断")
        }
    }

    private fun showNewWorkspaceDialog() {
        val client = BridgeClient.shared
        if (client == null) {
            toast("未连接电脑端")
            return
        }
        val input = EditText(this).apply {
            hint = "D:\\DeepSeekHarness\\my-project"
            setHintTextColor(COL_DIM)
            setTextColor(COL_TEXT)
            textSize = 14f
            setSingleLine(true)
            setPadding(dp(12), dp(10), dp(12), dp(10))
        }
        AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_NoActionBar)
            .setTitle("新建工作区")
            .setMessage("输入电脑端的绝对路径，会在电脑上创建目录并写入 DSH 工作区注册表。")
            .setView(input)
            .setPositiveButton("创建") { _, _ ->
                val p = input.text?.toString()?.trim().orEmpty()
                if (p.isEmpty()) {
                    toast("路径不能为空")
                } else {
                    val ok = client.createWorkspace(p)
                    toast(if (ok) "已提交创建请求…" else "发送失败：未连接")
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    // ------------------------------------------------------------ 小工具

    private fun sectionLabel(value: String): TextView {
        return text(value, 13f, COL_ACCENT, bold = true).apply {
            setPadding(dp(2), dp(18), 0, dp(8))
        }
    }

    private fun text(value: String, size: Float, color: Int, bold: Boolean = false): TextView {
        return TextView(this).apply {
            text = value
            textSize = size
            setTextColor(color)
            if (bold) setTypeface(typeface, Typeface.BOLD)
        }
    }

    private fun card(child: View): View {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(12), dp(14), dp(12))
            background = cardBackground()
        }
        box.addView(child)
        return box
    }

    /** 半透明圆角卡片：#4D1E293B，透出背后的深海蓝 */
    private fun cardBackground(): GradientDrawable = roundRect(COL_CARD, dp(14), COL_STROKE)

    private fun smallButton(label: String, color: Int, onClick: () -> Unit): Button {
        return Button(this).apply {
            text = label
            textSize = 12f
            setTextColor(color)
            isAllCaps = false
            background = roundRect(COL_CARD2, dp(18), color)
            setPadding(dp(14), dp(4), dp(14), dp(4))
            minimumWidth = 0
            minWidth = 0
            minimumHeight = 0
            minHeight = 0
            setOnClickListener { onClick() }
        }
    }

    private fun primaryButton(label: String, color: Int, isGradient: Boolean = false, onClick: () -> Unit): Button {
        val bg: GradientDrawable = if (isGradient) {
            GradientDrawable(
                GradientDrawable.Orientation.LEFT_RIGHT,
                intArrayOf(0xFF38BDF8.toInt(), 0xFF4C8DFF.toInt()),
            ).apply {
                cornerRadius = dp(14).toFloat()
            }
        } else {
            roundRect(color, dp(14), null).apply {
                setStroke(dp(1), 0x66FFFFFF)
            }
        }
        return Button(this).apply {
            text = label
            textSize = 14f
            setTextColor(Color.WHITE)
            isAllCaps = false
            background = bg
            setPadding(0, dp(11), 0, dp(11))
            minimumHeight = 0
            minHeight = 0
            stateListAnimator = null
            setOnClickListener { onClick() }
        }
    }

    private fun circle(color: Int): GradientDrawable = GradientDrawable().apply {
        shape = GradientDrawable.OVAL
        setColor(color)
    }

    private fun roundRect(fill: Int, radius: Int, stroke: Int?): GradientDrawable {
        return GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = radius.toFloat()
            setColor(fill)
            if (stroke != null) setStroke(dp(1), stroke)
        }
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private fun toast(msg: String) {
        runCatching { Toast.makeText(this, msg, Toast.LENGTH_SHORT).show() }
    }

    private companion object {
        val COL_BG = 0xFF060B14.toInt()
        val COL_SCRIM = 0xB3000000.toInt()      // 70% 黑遮罩
        val COL_CARD = 0x4D1E293B.toInt()       // 30% 深蓝卡片
        val COL_CARD_SOLID = 0xF21E293B.toInt()
        val COL_CARD2 = 0x661E293B.toInt()
        val COL_TRACE = 0xB30B1220.toInt()
        val COL_CARD_WARN = 0xCC2A2418.toInt()
        val COL_STROKE = 0x33FFFFFF.toInt()
        val COL_TITLE = 0xFFFFFFFF.toInt()
        val COL_TEXT = 0xFFFFFFFF.toInt()
        val COL_SUB = 0xFFE2E8F0.toInt()
        val COL_DIM = 0xFF94A3B8.toInt()
        val COL_ACCENT = 0xFF38BDF8.toInt()
        val COL_OK = 0xFF34D399.toInt()
        val COL_WARN = 0xFFFBBF24.toInt()
        val COL_DANGER = 0xFFFF4D6D.toInt()
    }
}
