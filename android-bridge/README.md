# android-bridge — 呆呆鱼手机桥接模块（Kotlin）

原版 APK 不动。本模块只负责生成一个 **`classes2.dex`**，注入由你手工完成。

状态：**已编译通过**，产物 `build\dex\classes2.dex`（24,516 字节，10 个 class）。

## 一、目录结构

```
android-bridge/
├── build.ps1                           # 一键编译（kotlinc -> d8 -> classes2.dex）
├── build.gradle                        # 可选：有 Android SDK 时用 Gradle 交叉验证
├── tools/                              # 本地编译工具链（已下载好）
│   ├── kotlin/                         # kotlin-compiler-embeddable 2.0.21 + stdlib/reflect/coroutines
│   ├── android/android-34/android.jar  # API 34 平台 stub（25.1 MiB）
│   └── r8.jar                          # 内含 D8（R8 8.5.35）
└── src/main/
    ├── AndroidManifest.xml             # 权限 + 前台服务声明
    └── java/com/shenshen/bridge/
        ├── BridgeState.kt              # 单例：连接状态 + pendingApproval + 监听器
        ├── BridgeClient.kt             # OkHttp WebSocket + 指数退避重连 + sendRun/sendApproval
        ├── ApprovalNotifier.kt         # 系统通知（审批弹窗 + 服务常驻通知）
        └── BridgeService.kt            # 前台服务，持有 BridgeClient 保活
```

## 二、与电脑端协议（bridge/server.js）

| 方向 | 消息 |
| --- | --- |
| 手机→电脑 | `{"action":"run","prompt":"..."}` |
| 手机→电脑 | `{"action":"approval","id":"call_xxx","decision":"allowed-once"}` |
| 电脑→手机 | `{"type":"approval-request","data":{"id":"call_xxx","toolName":"pwsh","reason":"..."}}` |
| 电脑→手机 | `{"type":"approval-resolved"} / {"type":"event","data":{...}} / {"type":"task-done"}` |

电脑端地址：`BridgeClient.DEFAULT_SERVER_URL = "ws://100.93.161.33:8080"`（电脑 Tailscale IP）。
手机装 Tailscale 并登录同一账号即可直连。

## 三、编译环境（本机已就绪）

| 组件 | 位置 |
| --- | --- |
| JDK 21 | `D:\IDEA\Java\jdk-21\bin\java.exe` ⚠️ PATH 上的 `java` 是 Oracle javapath 转发器，会静默失败，必须用完整路径 |
| Kotlin 编译器 2.0.21 | `tools\kotlin\*`（embeddable + stdlib + reflect + script-runtime + daemon + trove4j + annotations + coroutines 1.8.0） |
| android.jar（API 34） | `tools\android\android-34\android.jar` |
| D8 | `tools\r8.jar`（`com.android.tools.r8.D8`） |
| OkHttp / Okio | `.m2\repository\...\okhttp-4.9.3.jar`、`okio-jvm-3.4.0.jar` |

> tools 目录全部来自 阿里云 Maven（Kotlin）与 dl.google.com（platform-34-ext7_r03.zip / r8），已下载完成，无需再联网。

## 四、一键编译

```powershell
cd D:\DeepSeekHarness\project3-DaiDaiFish-DSH-Bridge\DaiDaiFish-DSH-Bridge\android-bridge
powershell -NoProfile -ExecutionPolicy Bypass -File .\build.ps1
```

产物：

```
build\dex\classes.dex
build\dex\classes2.dex
```

`build.ps1` 内部三步（手工等价）：

```powershell
$J  = 'D:\IDEA\Java\jdk-21\bin\java.exe'
$T  = '.\tools'
$AJ = "$T\android\android-34\android.jar"
$OKHTTP = "$env:USERPROFILE\.m2\repository\com\squareup\okhttp3\okhttp\4.9.3\okhttp-4.9.3.jar"
$OKIO   = "$env:USERPROFILE\.m2\repository\com\squareup\okio\okio-jvm\3.4.0\okio-jvm-3.4.0.jar"
$KCP = (Get-ChildItem "$T\kotlin" -Filter *.jar | % FullName) -join ';'

# 1) Kotlin -> JVM class
& $J -cp $KCP org.jetbrains.kotlin.cli.jvm.K2JVMCompiler `
  -no-stdlib -no-reflect `
  -classpath "$AJ;$OKHTTP;$OKIO;$T\kotlin\kotlin-stdlib.jar" `
  -jvm-target 1.8 -d build\classes `
  src\main\java\com\shenshen\bridge\*.kt

# 2) class -> dex
& $J -cp "$T\r8.jar" com.android.tools.r8.D8 --release --min-api 24 `
  --lib $AJ --output build\dex (Get-ChildItem build\classes -Recurse -Filter *.class | % FullName)

# 3) 改名
Copy-Item build\dex\classes.dex build\dex\classes2.dex -Force
```

## 五、dex 内容说明

当前 dex **只含本模块 4 个类**（24.5 KB），对 `okhttp3` / `kotlin.*` 是"引用"而非"打包"。

- 若原版 APK 自带 OkHttp 与 Kotlin 运行时 → 直接注入 `classes2.dex` 即可。
- 若原版 APK 没有 → 把 `okhttp-4.9.3.jar`、`okio-jvm-3.4.0.jar`、`kotlin-stdlib.jar` 一并交给 D8 打包（dex 会涨到 ~4 MB），或改成调用 APK 内已有的网络库。

## 六、注入（手工，不自动做）

```powershell
# 1. 解包副本
unzip -o app.apk -d app_unpacked
# 2. 放入 dex
Copy-Item build\dex\classes2.dex app_unpacked\
# 3. 重打包 + 对齐 + 签名（需 apksigner / zipalign）
```

## 七、已知坑

1. `java` 必须用 JDK21 完整路径，PATH 上的 javapath 版本静默退出。
2. PowerShell 执行策略：脚本要 `-ExecutionPolicy Bypass`。
3. `Expand-Archive` 解 60 MB platform 包很慢（几分钟），已解压完毕，无需重跑。
4. `ApprovalNotifier` 只用系统 API（无 androidx），避免注入时缺库。
5. `BridgeService` 用 `foregroundServiceType="dataSync"`（Android 14 强制要求）。
6. 断线重连：1s→2s→4s→8s→16s→30s 封顶 + 0–500 ms 抖动。
