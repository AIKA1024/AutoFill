# 验证码自动填充（LSPosed 模块）

收到短信后自动提取验证码 → 复制 → 填入当前输入框，支持自定义正则、关键词、Toast/通知、拦截验证码短信。

面向 **LSPosed v2.0.1**，使用现代的 **libxposed API**（`io.github.libxposed.api`，**API 101**），不使用旧的 `de.robv.android.xposed` 包。

> 版本说明：LSPosed 2.x 已**移除对 API 100 的支持**，只支持 API 101。因此 `module.prop` 里必须写 `minApiVersion=101`，写成 100 会导致模块不被加载。

---

## 功能

| 设置项 | 说明 |
| --- | --- |
| 启用模块（总开关） | 关闭后所有行为停止 |
| 自动复制验证码 | 识别到验证码后写入剪贴板 |
| 复制整条短信 | 关闭时只复制验证码数字 |
| 自动填入文本框 | 填入当前前台 App 的输入框 |
| 显示 Toast | 弹出「验证码 xxxxxx（发送号码）」 |
| 显示通知 | 在通知栏展示验证码与短信全文 |
| 拦截验证码短信 | 在分发前丢弃该短信，不会进短信库、不弹通知 |
| 正则表达式 | 默认 `(?<![0-9])[0-9]{4,8}(?![0-9])`；含捕获组时取第 1 组 |
| 关键词 | 逗号分隔，命中才认定为验证码短信；留空表示不过滤 |
| 排除的应用包名 | 逗号分隔，这些应用不复制、不填入 |

设置页底部带「规则测试」：粘贴一条短信正文即可验证关键词 + 正则能否提取出验证码。

## 实现原理

1. **短信解析（com.android.phone 进程）**
   Hook `com.android.internal.telephony.InboundSmsHandler#dispatchIntent`（`hookAllMethods` 式遍历所有重载，规避不同 ROM 的签名差异）。
   从 Intent 的 `pdus` 里解析 `SmsMessage`，得到正文与发送号码。
   命中规则后：
   - 拦截开启 → 不调用 `chain.proceed()`，短信在分发前被丢弃；
   - 通过有序广播 `com.autofill.sms.CODE_RECEIVED` 把验证码发给所有被 Hook 的进程。

2. **应用侧（每个 App 进程）**
   - Hook `Application#onCreate` 拿到 Context，并动态注册验证码广播（Android 13+ 使用 `RECEIVER_EXPORTED`）；
   - Hook `Activity#onResume/onPause` 判断本进程是否前台；
   - 前台进程收到广播后执行复制 / Toast / 通知 / 填入，并把有序广播结果码置为 `HANDLED`；
   - 无人处理（桌面 / 息屏）时，由电话进程兜底复制 + 通知；
   - 前台 App 没通知权限时，改由电话进程代发通知；
   - 短信先到、输入框后获得焦点的情况：Hook `TextView#onFocusChanged`，在 2 分钟内（hint 命中关键词 / 纯数字输入框 / 30 秒内的新验证码）自动补填。

3. **配置下发**
   使用 LSPosed 的 **Remote Preferences**：模块 App 通过 `XposedService` 写入，被 Hook 的进程通过 `XposedInterface#getRemotePreferences` 读取。未连接框架时会退化到本地 `SharedPreferences` 兜底（此时 Hook 进程读不到，设置页会给出提示）。

## 构建

环境要求：JDK 17+（推荐 JDK 21）、Android SDK（platform 36 / build-tools 36.0.0）。

```bash
# Linux / macOS
export JAVA_HOME=/path/to/jdk-21
./gradlew assembleDebug

# Windows
set JAVA_HOME=C:\Path\To\jdk-21
gradlew.bat assembleDebug
```

或直接用 Android Studio 打开本目录（Gradle JDK 设为 17+）。产物：`app/build/outputs/apk/debug/app-debug.apk`。

首次构建前需在项目根目录创建 `local.properties`，指向你的 Android SDK：

```properties
sdk.dir=/path/to/Android/sdk
```

依赖版本（见 `gradle/libs.versions.toml`）：AGP 8.13.2 / Gradle 8.13 / `io.github.libxposed:api:101.0.1` + `io.github.libxposed:service:101.0.0`。

> 注意：`api` / `service` 的 102 系列要求 `compileSdk 37`，需 Android SDK platform 37；本项目锁定 **101 系列**，对应 LSPosed 2.0.1 官方支持的 API 等级。

> 本项目是纯 Java 模块，没有 native 代码，NDK 不参与编译。

## 安装与启用

1. 安装 APK；
2. LSPosed 管理器 → 模块 → 勾选「验证码自动填充」；
3. 作用域：勾选 **系统框架（android）** 与 **Phone / 电话（com.android.phone）**。前者负责把模块注入到所有 App 进程（自动填入），后者负责短信解析与拦截；
4. 重启（或软重启）；
5. 打开一次本应用，确认顶部显示「框架已连接」，按需调整设置。

## 已知限制

- 短信拦截点在 `InboundSmsHandler#dispatchIntent`，极少数深度定制 ROM 若改名该方法，拦截与自动识别会失效（可查看 LSPosed 日志 `AutoFillSms`，会打印 hook 到的重载数量）。
- Android 13+ 通知由前台 App 自身发出，若该 App 未授予通知权限会自动改由电话进程代发（通知来源会显示为「电话」相关应用）。
- 自动填入是启发式的：优先填当前有焦点的输入框；无焦点时，仅当界面上只有一个可见输入框才填入。对 Flutter / 自绘输入控件可能无效。

## 日志排查

`adb logcat | grep AutoFillSms` 或 LSPosed 管理器 → 日志，关键输出：

- `onModuleLoaded | process=... | framework=... | api=...`
- `InboundSmsHandler hooked: N overload(s) in com.android.phone`
- `code receiver registered in <包名>`
- `code detected: 884219 from 1069xxxx`
- `SMS blocked (verification code intercepted)`
