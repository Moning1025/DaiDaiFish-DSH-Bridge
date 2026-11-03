package com.shenshen.bridge

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.media.RingtoneManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Gravity
import android.view.WindowManager
import android.widget.TextView

/**
 * 与原版桌宠（com.shenshen.pet.PetOverlayService，在 classes.dex 里）打交道的中转层。
 *
 * 为什么全走反射：classes.dex 是原版 smali 编的，编译期我们拿不到它的符号，
 * 只能运行时按方法名/类名去找。任何一步失败都降级，绝不抛异常崩服务。
 *
 * 提供三件事：
 *  1. attachService —— 由 smali 插针在 PetOverlayService.onCreate 里把单例交进来
 *  2. showBubble / playClick —— 复用原版气泡与音效
 *  3. openDashboard —— 让长按菜单打开 DSH 控制台
 */
object BridgeHooks {

    private const val TAG = "BridgeHooks"
    private const val PET_SERVICE_CLASS = "com.shenshen.pet.PetOverlayService"

    private val main = Handler(Looper.getMainLooper())

    @Volatile
    private var petService: Any? = null

    @Volatile
    private var appContext: Context? = null

    // ---------------------------------------------------------------- 挂载

    /** smali 在 PetOverlayService.onCreate 里调用：把桌宠服务实例交给桥接层。 */
    @JvmStatic
    fun attachService(service: Any?) {
        petService = service
        if (service is Context) appContext = service.applicationContext
        Log.i(TAG, "pet service attached: ${service?.javaClass?.name}")
    }

    /** BridgeService 启动时兜底注入 applicationContext（桌宠没起来也能开控制台）。 */
    @JvmStatic
    fun attachContext(context: Context?) {
        if (context != null) appContext = context.applicationContext
    }

    // ---------------------------------------------------------------- 控制台入口

    /** 长按菜单里的「打开 DSH 控制台」最终调到这里（无参版本，供 smali 直接调）。 */
    @JvmStatic
    fun openDashboardFromService() {
        val ctx = appContext ?: (petService as? Context)
        if (ctx == null) {
            Log.w(TAG, "openDashboardFromService: no context")
            return
        }
        openDashboard(ctx)
    }

    @JvmStatic
    fun openDashboard(context: Context) {
        runCatching {
            val intent = Intent(context, DshDashboardActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            }
            context.startActivity(intent)
        }.onFailure { Log.w(TAG, "open dashboard failed", it) }
    }

    // ---------------------------------------------------------------- 气泡

    /** 桌宠气泡显示一行字；原版服务不在就降级成系统悬浮窗。 */
    @JvmStatic
    fun showBubble(text: String) {
        if (text.isBlank()) return
        val svc = petService
        if (svc != null) {
            val ok = runCatching {
                val m = svc.javaClass.getDeclaredMethod("displayTextBubble", String::class.java)
                m.isAccessible = true
                main.post { runCatching { m.invoke(svc, text) } }
                true
            }.getOrDefault(false)
            if (ok) return
        }
        showOverlayBubble(text)
    }

    private fun showOverlayBubble(text: String) {
        val ctx = appContext ?: return
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        main.post {
            runCatching {
                val wm = ctx.getSystemService(Context.WINDOW_SERVICE) as WindowManager
                val tv = TextView(ctx).apply {
                    this.text = text
                    setTextColor(Color.WHITE)
                    textSize = 13f
                    maxLines = 3
                    setPadding(dp(ctx, 14), dp(ctx, 10), dp(ctx, 14), dp(ctx, 10))
                    background = GradientDrawable().apply {
                        cornerRadius = dp(ctx, 14).toFloat()
                        setColor(0xE61B1F2A.toInt())
                        setStroke(dp(ctx, 1), 0x66FFFFFF)
                    }
                }
                val lp = WindowManager.LayoutParams(
                    WindowManager.LayoutParams.WRAP_CONTENT,
                    WindowManager.LayoutParams.WRAP_CONTENT,
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                        WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
                    PixelFormat.TRANSLUCENT,
                ).apply {
                    gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
                    y = dp(ctx, 120)
                }
                wm.addView(tv, lp)
                main.postDelayed({ runCatching { wm.removeView(tv) } }, 3_500)
            }.onFailure { Log.w(TAG, "overlay bubble failed", it) }
        }
    }

    // ---------------------------------------------------------------- 音效

    /** 收到审批时响一声：优先用原版 playClickOnce，失败退回系统通知音。 */
    @JvmStatic
    fun playClick() {
        val svc = petService
        if (svc != null) {
            val ok = runCatching {
                val m = svc.javaClass.getDeclaredMethod("playClickOnce")
                m.isAccessible = true
                main.post { runCatching { m.invoke(svc) } }
                true
            }.getOrDefault(false)
            if (ok) return
        }
        runCatching {
            val ctx = appContext ?: return
            val uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
            RingtoneManager.getRingtone(ctx, uri)?.play()
        }.onFailure { Log.w(TAG, "fallback sound failed", it) }
    }

    // ---------------------------------------------------------------- 杂项

    private fun dp(ctx: Context, v: Int): Int = (v * ctx.resources.displayMetrics.density).toInt()

    /** 供调试：判断桌宠服务是否已经挂上 */
    @JvmStatic
    fun hasPetService(): Boolean = petService != null

    /** 供调试：桌宠类名（避免编译期依赖） */
    @JvmStatic
    fun petServiceClass(): String = PET_SERVICE_CLASS
}
