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

**验证码提取优先级**（避免取到短信签名里的数字，如 `【4399】验证码：798759` 只应取 `798759`）：

1. 关键词（"验证码 / 校验码 / code / OTP" 等）**之后**的第一个匹配 —— 验证码基本都紧跟提示词；
2. 屏蔽短信签名（`【】`、`[]`、《》、『』、`<>` 内的内容，长 0~20）后的第一个匹配；
3. 兜底：整条短信的第一个匹配。

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

> **为什么不直接用 API 102？** 生态确实在往 102 迁移，但 `minApiVersion` 的语义是「模块能接受的最低框架 API」：
> 框架 API ≥ `minApiVersion` 就能加载。写 **101** 意味着模块在 **API 101 和 API 102 的框架上都能跑**；
> 写 102 则只能在 102 框架上跑。LSPosed 官方对 102 的限制（禁止调用 Legacy API）只针对
> `targetApiVersion ≥ 102` 的模块，**101 模块不受影响**。因此 101 的兼容面更大。
> 若你的框架只支持 102 且模块不加载，把 `module.prop` 的 `targetApiVersion` 改成 102，
> 并改用 `io.github.libxposed:api:102.0.0` + `service:102.0.0` 编译（需要 Android SDK platform 37）。

> 本项目是纯 Java 模块，没有 native 代码，NDK 不参与编译。

## 安装与启用

1. 安装 APK；
2. LSPosed 管理器 → 模块 → 勾选「验证码自动填充」；
3. 作用域：勾选 **要使用自动填入的 App 本身** + **电话服务（com.android.phone）**。
   - `com.android.phone` 负责短信解析、复制与拦截；
   - **填入必须在目标 App 自己的进程里执行**，所以该 App 必须单独勾选。每换一个 App 就要勾一次，勾完重启一次即长期有效。
   - 作用域列表里那个「Android 系统 / 系统框架」对应的包名就是 `android`，注入后日志显示为 `process=system`（即 system_server）。**LSPosed 2.x 的现代模块不会因为它而自动覆盖所有 App 进程**——真机验证：勾了 `android` 后只看到 `process=system` 和 `process=com.android.phone` 两个进程，第三方 App 一个都没被注入。
   - **本模块自己（com.autofill.sms）不需要、也不会出现在作用域列表里**：它自己的那部分工作（root 注入）是普通 Android 组件完成的，不靠 Xposed 注入。
   - 若确实想对所有 App 生效，可在 LSPosed 设置里开启「全局作用域」（如有该选项），或逐个勾选；
4. 重启手机（软重启不够）；
5. 打开一次本应用，确认顶部显示「框架已连接」，按需调整设置。

> 只勾 `com.android.phone` 时：**复制能用，填入不能用**——因为复制在电话进程完成，而填入需要目标 App 进程里有模块。

### 为什么不能像 Thanox 那样只勾「系统框架」

LSPosed 的作用域机制（见 daemon `ConfigManager#cacheScopes` / `getAssociatedProcesses`）是
**「包名 → 该包名所属进程」的精确映射**，代码里没有任何"全局"分支：

```java
// system_server 走单独路径，普通包按自己的进程展开
if (app.packageName.equals("system")) continue;
for (ProcessScope ps : getAssociatedProcesses(app)) cachedScope.get(ps).add(module);
```

而且数据库迁移把旧的 `android` 包名改写成了 `system`（`UPDATE scope SET app_pkg_name='system'
WHERE app_pkg_name='android'`），`system` 只对应 system_server。

Thanox 只勾「系统框架」就能管理其他应用，是因为它的后台管理/自启动/权限控制**全部是 hook
system_server 里的 AMS、PMS 等系统服务实现的**，不需要进入目标 App 进程。而**自动填入必须在
目标 App 进程里操作它的 `EditText`**——system_server 里没有目标 App 的 View，跨进程做不到。

另外，libxposed API 未提供任何操作作用域的方法（接口只有 hook / log / deoptimize /
getInvoker / getRemotePreferences / 远程文件），所以模块无法自行把自己加入某个包的作用域，
必须由用户在管理器里勾选。

### 不想逐个勾 App？用 root 注入（默认开启）

没人接管验证码时，短信进程会**同时**走两条路去触发本应用，由模块 App 用 root 执行
`input text <验证码>`——按键由 system_server 的 InputDispatcher 分发给**当前焦点窗口**，
与目标 App 是否被注入完全无关。因此：

- **不用把目标 App 勾进作用域，作用域里只要有 `com.android.phone` 就够了**；
- **模块自身（com.autofill.sms）不需要、也不在 LSPosed 的作用域列表里**——
  `RootFillService` / `RootFillReceiver` 是本应用自己的普通 Android 组件，靠显式绑定 /
  显式广播被调用，**与 Xposed 注入没有任何关系**；
- **对 Compose / Flutter 自绘控件同样有效**（它们一样接收按键事件）；
- 不受 Android 版本影响（`input` 是系统命令，不涉及 hook 系统内部类）。

前提与限制：

1. 在 root 管理器（KernelSU / Magisk / APatch）里给本应用**授予 root 权限**；
   设置页底部会实时显示授权状态（绿色「已获得 root」/ 红色「未获得 root」），
   去授权后返回设置页会自动重新检测；
2. **装完 / 升级后必须手动打开一次本应用**（一次性，之后永久有效，重启也不会退回）。
   原因：Android 3.1 起，从未启动过（或被 force-stop）的应用处于 stopped 状态，
   **收不到任何广播**——包括显式 `setPackage` 广播、加
   `FLAG_INCLUDE_STOPPED_PACKAGES` 的广播、**甚至 `BOOT_COMPLETED`**（系统广播一律默认
   `FLAG_EXCLUDE_STOPPED_PACKAGES`）。实测用 root 身份发送也一样被拦。
   ⇒ 想跳过这一步，只能走**显式启动组件**（`bindService`），它不受 stopped 限制，
   模块里 `RootFillService` 就是干这个的——但它会被 Thanox 之类的工具拦掉，见下条；
3. 输入框需要有焦点（先点进输入框再等验证码）；
4. 验证码拼进 shell 前只允许 `[0-9A-Za-z]{3,16}`——这不是格式校验，而是安全底线，
   防止恶意短信构造内容以 root 执行任意命令；
5. 每次注入会 fork 一个 `su` 进程（约 0.5~1s），不会重试，避免验证码被填两遍。

### 装了 Thanox：放行 `bindService`（可选）

Thanox 的「**后台启动**」功能拦截的就是应用间的关联启动（bind / start），真机日志里长这样：

```
Thanox-Core: bindServiceLocked block, checkRes false com.autofill.sms/.RootFillService 1001 0
```

放行三选一（Thanox 8.x 菜单名）：

1. **看原因**：Thanox → 后台启动 → 右上角菜单 → **启动记录** → 点中间大数字进详情，
   找到 `com.autofill.sms` 那条。`BYPASS_PROCESS_RUNNING`（目标已在运行，放行）、
   `BLOCKED_STANDBY`（智能待机/乖巧模式把它休眠了）、`BLOCKED_IN_BLOCK_LIST`（你手动关过）
   等一目了然——**只要本应用进程在跑，Thanox 是直接放行的**，这也是"打开一次就够"的原因之一。
2. **加规则**（推荐，最精确）：后台启动 → 规则 → 添加
   ```
   ALLOW com.android.phone com.autofill.sms
   ```
   语法是 `ALLOW|DENY <启动者包名> <目标包名>`，支持 `ALLOW android *`、`SHELL`、`ROOT` 等写法。
3. **关掉对本应用的限制**：后台启动的 app 列表里把本应用打开（Thanox 默认全部允许），
   并关闭它的智能待机 / 乖巧模式。

不放行也不影响使用——只要打开过一次本应用，广播通道就通了；Thanox 放行只是让
`bindService` 那条"免启动"的备用通道也能用。

设置页有开关（「无人接管时用 root 注入」），并会实时显示 root 是否可用。

## 已知限制

- 短信拦截点在 `InboundSmsHandler#dispatchIntent`，极少数深度定制 ROM 若改名该方法，拦截与自动识别会失效（可查看 LSPosed 日志 `AutoFillSms`，会打印 hook 到的重载数量）。
- Android 13+ 通知由前台 App 自身发出，若该 App 未授予通知权限会自动改由电话进程代发（通知来源会显示为「电话」相关应用）。
- 自动填入是启发式的：优先填当前有焦点的输入框；无焦点时，按 hint / 资源 id / contentDescription / `maxLength` / 数字键盘等特征给输入框打分，取分最高的那个。**若所有输入框都不像验证码框，则不会填入**（避免填错位置）。对 Jetpack Compose / Flutter / 自绘输入控件无效。

## 日志排查

模块日志**同时写入 logcat 和 LSPosed 自己的日志系统**，两条路都能看：

```bash
adb logcat -s AutoFillSms:*          # logcat（Linux/macOS/Windows 通用，不需要 grep）
```

Windows PowerShell 里没有 `grep`，不要写 `adb logcat | grep AutoFillSms`，直接用上面那条 `-s` 过滤即可。
也可以看 LSPosed 管理器 → 日志页。

> **一条日志都没有 = 模块没被注入任何进程。** 按序检查：LSPosed 里模块开关是否打开 →
> 作用域是否勾了 **目标 App** 和 **Phone（com.android.phone）** → 是否**重启过手机**
> （软重启不够）。确认后打开目标 App，应该立刻看到 `package ready: <该 App 包名>`。

- `onModuleLoaded | process=... | framework=... | api=...`
- `InboundSmsHandler hooked: N overload(s) in com.android.phone`
- `package ready: <包名> | process=...` —— **判断某 App 有没有被注入的唯一依据**；看不到目标 App 的包名就说明它不在作用域里
- `code receiver registered in <包名>` —— 该 App 进程已挂上接收器，之后才可能填入
- `code received in <包名> | foreground=true | autoFill=true` —— **foreground 为 false 说明前台判定失败，填入不会执行**
- `candidate inputs: N` —— 界面上找到的可见输入框数量
- `filled via focused EditText` / `scored EditText` / `webview` / `split boxes` —— 填入成功及所用路径
- `root fill: bind requested to com.autofill.sms | accepted=true` —— 已请求绑定注入服务
- `root fill sent: <验证码>` / `root fill FAILED: <验证码>` —— root 注入成功 / 失败
- `root fill skipped (same code just injected)` —— 同一验证码 5 秒内重复触发被去重
- `no input found — 可能是 Compose / Flutter / 自绘控件，无法填入`
- `SMS blocked (verification code intercepted)`

## 自动填入为什么会失败

填入完全依赖"在当前界面上找到对的那个输入框"，下面几种情况都找不到：

| 情况 | 表现 | 能否解决 |
| --- | --- | --- |
| 界面用 Jetpack Compose / Flutter / 自绘控件 | 日志显示 `no input found`，界面上根本没有 `EditText` | 不能（本模块只认原生 View） |
| H5 登录页（WebView） | 有 `EditText` 但不在原生层 | 已支持：注入 JS 填写（会临时开启 JS，1.5s 后还原） |
| 多格 OTP（6 个单字符框） | 每格只收 1 个字符 | 已支持：逐格填入 |
| 多个输入框、且特征都不像验证码框 | `candidate inputs: N` 但没填 | 打开"关键词"设置，把该页面的输入框提示词加进去 |
| **目标 App 没勾进作用域**（最常见） | 日志里没有 `package ready: <该 App>`，也没有对应进程的 `onModuleLoaded` | 在 LSPosed 作用域里**勾选该 App 本身**，重启 |
| **全部日志都是同一个进程打的**（只有 `code detected` + 反复 `fill skipped: no resumed activity`，PID 相同） | 没有任何 App 进程被注入，广播无人接管，短信进程只能自己兜底——而它没有界面 | 把**目标 App** 加进作用域；只勾 `com.android.phone` 能复制但填不了（勾 `android` 也不够） |
| **作用域里没有本模块（com.autofill.sms）** | 正常，也不需要 | 模块自身不会被 LSPosed 列进作用域；root 注入用的是普通 Android 组件（显式 bind / 广播），与 Xposed 注入无关 |
| **装完没打开过本应用** | 收不到任何广播（含开机广播），root 注入静默失效 | 打开一次本应用：**一次性，之后永久有效**；想跳过只能靠下面那条 bind 通道 |
| **装了 Thanox / 类似后台管理工具** | 日志出现 `Thanox-Core: bindServiceLocked block` | 绑定通道被拦。放行方式：Thanox → 后台启动 → 规则 → `ALLOW com.android.phone com.autofill.sms`。**真机验证**：放行后即使把本应用 `force-stop`（stopped 状态、进程已死），bind 也能把它拉起来完成注入，"打开一次本应用"不再是硬要求 |

## 许可证

[MIT](LICENSE) © 2026 AIKA1024
