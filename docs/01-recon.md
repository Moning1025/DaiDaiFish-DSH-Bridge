# Phase 1 — 呆呆鱼桌宠 APK 逆向侦察报告

> 目标：把「呆呆鱼桌宠」APK 逆向并改造为「DSH 掌上控制台」。
> 本报告为 Phase 1（侦察与攻击面定位）产出，基于 REA + jadx 反编译结果。

## 1. 目标文件

| 项目 | 值 |
| --- | --- |
| 源文件 | `base.apk.1.apk.1_tmp.apk.1_tmp` |
| 大小 | 52,864,759 字节（约 50.4 MB） |
| 格式校验 | PK(zip) 头合法，为有效 APK |
| classes.dex | 1,572,864 字节 |
| assets | `audio/*.wav`、`characters/shenshen/videos/**/*.webm` 等 |

## 2. Manifest 摘要

| 项目 | 值 |
| --- | --- |
| package | `com.shenshen.pet` |
| versionName / versionCode | 1.86 / 98 |
| compileSdk / minSdk / targetSdk | 35 / 29 / 35 |

### 权限

- `SYSTEM_ALERT_WINDOW`（悬浮窗）
- `POST_NOTIFICATIONS`
- `FOREGROUND_SERVICE` / `FOREGROUND_SERVICE_SPECIAL_USE`
- `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`
- `WAKE_LOCK`、`RECEIVE_BOOT_COMPLETED`
- 自定义签名权限 `com.shenshen.pet.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION`

### 组件

| 组件 | 说明 |
| --- | --- |
| `com.shenshen.pet.MainActivity` | LAUNCHER、exported、singleTop、portrait、noHistory（设置控制台） |
| `com.shenshen.pet.PetOverlayService` | exported=false、stopWithTask=false、`foregroundServiceType=specialUse`、property `desktop_pet_overlay`（悬浮窗服务） |
| `com.shenshen.pet.BootReceiver` | 接收 BOOT_COMPLETED / QUICKBOOT_POWERON |
| `androidx.startup.InitializationProvider`、`ProfileInstallReceiver` | 框架样板，可忽略 |

## 3. 类结构与混淆

- 类总数：**1430**
  - `androidx.*` 203
  - `com.google.*` 75（GMS）
  - `com.bumptech.*` 47（Glide）
  - `com.shenshen.*` 4（应用本体）
  - 其余约 1095 个为 R8 混淆根包类（`J`、`A0`、`K`、`E0`、`k1`、`l1`、`m1`、`t1`、`h1` …）
- 结论：release 包、R8 全量混淆；应用本体未混淆的入口只有 4 个类。

### 核心类

| 类 | 规模 | 职责 |
| --- | --- | --- |
| `PetOverlayService` | 113 字段 / 306 方法 | 悬浮窗“上帝类”：窗口、标签页、菜单、气泡、WebView、音效、状态机、设置热更新 |
| `MainActivity` | 90 字段 / 167 方法 | 设置控制台（滑杆/开关/气泡文案/图片导入/权限按钮） |
| `BootReceiver` | — | 开机自启入口 |

## 4. 关键机制（反编译证据）

### 4.1 悬浮窗 WebView（渲染/通信面）

`PetOverlayService.ensureOverlay()` 创建 WebView：

- `setJavaScriptEnabled(true)`、`setMediaPlaybackRequiresUserGesture(false)`
- `setAllowFileAccess(true)`、`setDomStorageEnabled(true)`
- `addJavascriptInterface(new F(this), "Android")`
- `loadUrl("file:///android_asset/pet/pet.html")`
- 窗口参数：type **2038**、flags **520**、gravity 8388659

`PetOverlayService.eval(String)` 可在其中执行任意 JS：

```java
if (this.webViewGone || (webView = this.webView) == null) return;
webView.post(new B.l(this, 3, str));
```

### 4.2 菜单系统（入口注入点）

- `showActionMenu()`：全屏遮罩 + `menuCard`(ScrollView, 宽 190dp, 背景 `menu_bg`) + `renderMainMenu()`。
- `menuItem(String, boolean bold, t1.a callback)`：新建 TextView 菜单项（15sp、`pet_ink` 色），点击回调 `callback.a()`。
- `menuAction(aVar)`：`dismissActionMenu(); aVar.a();`

### 4.3 指令通道（远程命令模板）

`PetOverlayService.onStartCommand()` 按 action 分发：

- `ACTION_RESTORE` → `removeTab()` + `ensureOverlay()`
- `ACTION_STOP` → `stopSelf()`
- `ACTION_UPDATE` → `applyUpdate()`
- `ACTION_COLLAPSE` → `collapsePet()`
- 服务侧状态量 `PetOverlayService.running`（`Companion` 静态态）

`MainActivity.pushUpdate()` 是现成的“设置台 → 服务”命令通道：

```java
if (PetOverlayService.running)
    startService(new Intent(this, PetOverlayService.class)
        .setAction(PetOverlayService.ACTION_UPDATE));
```

### 4.4 气泡显示（任务进度面）

`displayTextBubble(String)`：`paginateText()` 分页 → TextView（13sp × scale，气泡背景，白色/蓝色/默认三态）→ `addBubbleView$default(...)`；多页自动 `scheduleNextPage`。

### 4.5 设置热更新

`applyUpdate()` 纯本地：重读设置并刷新气泡开关、全屏隐藏、贴角、朝向、镜像、速度、亮度、透明度、窗口尺寸等。**不涉及网络。**

## 5. 关键结论

1. **应用本体无网络栈**：依赖里只有 GMS 与 Glide，没有 HTTP/WebSocket 客户端。
   → 桥接需自带 HTTP(S)/WebSocket 客户端，或复用 WebView JS `fetch`。
2. **四大注入面已锁定**：
   - 渲染/通信面：WebView `pet.html` + `"Android"` JS 接口 + `eval()`
   - 菜单入口：`menuItem("DSH 面板", …)`
   - 命令通道：`ACTION_*` + `startService` + `running`
   - 进度气泡：`displayTextBubble`
3. **异网联通**：推荐 Tailscale 组网（PC + 手机同账号），桥接端直连 DSH Web 端口；备选 ZeroTier / Cloudflare Tunnel / frp。

## 6. 待补工具

| 工具 | 用途 | 状态 |
| --- | --- | --- |
| apktool | 解码/回编资源与 smali | 缺 |
| Android build-tools（apksigner/zipalign/d8/aapt2） | 签名/对齐/打包 | 缺（约 55MB，需授权下载） |
| platform-tools（adb） | 真机安装验证 | 缺 |
| Tailscale | 异网组网 | 缺 |
| jadx（rea 桥） | 反编译 | ✅ `D:\REA_Tools\jadx\jadx-headless-mcp-0.7.1-all.jar` |
| JDK 21 / keytool | 签名 | ✅ |
