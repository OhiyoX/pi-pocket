# 测试与门禁

本项目**不靠手点验收**：能在 JVM 上跑的纯逻辑一律写成单元测试；UI/设备相关才用模拟器 + adb 探针。

## 一条命令跑全部门禁

```bash
./gradlew :app:testDebugUnitTest :app:lintDebug :app:assembleDebug --console=plain
node --check termux/pi-pocket-bridge.mjs
node --test script/bridge-runtime.test.mjs
node --test script/pi-pocket-start.test.mjs      # 启动器（Termux 上 14 项，Windows 上 7 项因 /proc 跳过）
node --test script/pi-pocket-service.test.mjs    # 服务化：run 脚本 + start-bridge 分流（sv / 直起）
node script/check-htmlvisual-js.mjs
```

> **在手机上（Termux）跑时**必须补一个 aapt2 覆盖，否则 `processDebugResources` 会挂在
> `aapt2 … Daemon startup failed`（AGP 从 maven 拉的 aapt2 是 x86_64 ELF，跑不了；本机装的是
> Termux 的 aarch64 aapt2）：
>
> ```bash
> ./gradlew … -Pandroid.aapt2FromMavenOverride=/data/data/com.termux/files/home/android-sdk/build-tools/36.0.0/aapt2
> ```

CI（`.gitea/workflows/ci.yml`）跑的就是这一组。

## 门禁口径

| 门禁 | 阈值 | 说明 |
| --- | --- | --- |
| 单元测试 | **全绿**（数量以当次 JUnit XML 汇总为准） | `app/src/test/**`，纯 JVM，不需要设备 |
| Lint | **0 error**（`abortOnError = true`） | warning 记录但不阻断；`PropertyEscape` 因只针对本机 `local.properties` 而关闭 |
| assembleDebug | 必须成功 | 产物 `app/build/outputs/apk/debug/app-debug.apk` |
| 桥脚本 | `node --check` 通过 | `termux/pi-pocket-bridge.mjs` |

## 测试覆盖了什么

| 文件 | 覆盖点 |
| --- | --- |
| `TableWidthsTest` | 表格列宽（方案 D + 结构性保险丝）：真实 2/3/4 列表、用户截图那张 `时间\|路径`、单列/五列/空单元格/超长 URL、装得下按比例撑满、不横滚时不许溢出、`maxMinFair` |
| `SessionScopeTest` | 会话态归属判据 `ownerMatchesSession`（排队条/工作状态/压缩卡/状态行都靠它）：桥级状态谁看都显示、会话态必须等于当前会话、当前会话未知时一律不显示 |
| `TableParseReproTest` | 表格解析回归（`\|\|` 被当成列分隔那个 bug） |
| `ProviderFormTest` | provider 表单（models.json 写入、字段校验） |
| `TermuxBootstrapTest` | 从零部署脚本的关键分支（幂等/早退/体检项） |
| `SlashArgsTest` | `/model fl`、`/mode`、`/skill inter`、`/resume` 的参数候选；命令排序（前缀→名字→说明）；全角 `／` 与全角空格归一化；未知命令不乱猜 |
| `ParsingTest` | Markdown 分块（标题/列表/引用/代码/分隔线/段落合并）、**行内解析不拆 emoji 代理对**（回归 bug）、`**粗**`/`` `code` ``/链接、未闭合标记原样保留；`briefOf` 清换行+截断；`resultSummary`；toolCall↔toolResult 配对 |
| `MermaidThemeTest` | mermaid 宿主页：浅色走 `base`+themeVariables / 深色走 `dark`（2026-10-22 浅色黑底块回归）；量高用 `getBoundingClientRect` 而**不是 viewBox**、分母用容器 `clientWidth`、`fonts.ready` + 两次补量（长图被截断的回归）；**不许给 svg 加 width:100%**（图被拉大回归）；围栏转义不许提前闭合宿主页 |
| `LogicTest` | 主机地址归一化（补 scheme/端口）、主题三态解析、SSH 目标 key 与默认启动命令、附件大小换算 |

## 设备侧验证（不能单元测试的部分）

用 debug 构建里的 `DebugProbeReceiver`（release 不含）+ `adb`：

```bash
adb shell am broadcast -f 0x00000020 -n id.harness.pipocket/.DebugProbeReceiver \
  -a id.harness.pipocket.DEBUG_PROBE --es action scan|health|prompt|shell|args|theme|machines
adb logcat -d -s PiPocketDebug:V
```

结果统一打 `RESULT ...` 前缀，便于机器判读。**注意**：
- `adb install -r` 后先 `am force-stop`，否则跑的是旧 dex；
- Git Bash 下带 `/` 的参数要 `MSYS_NO_PATHCONV=1`，否则会被转成 `C:/Program Files/Git/...`；
- 模拟器中文输入法会干扰 `adb shell input text`（会把 `/` 打成全角 `／`、擅自加空格），所以**输入类逻辑走单元测试**，不靠 adb 打字。

## 视觉验收（改 UI 必用）

- **只用自建 AVD `pipocket-qa`（5556） + `tools/mock-bridge.mjs`**；不要用用户的手机 / 他的模拟器 `5554` 做视觉迭代。
- 假桥给固定转录（markdown/表格/图片/工具块），避开“等模型 + 往真环境落测试会话”；重看一眼 ≈ `assembleDebug` + `adb install` + 一次广播。
- 命令序列见 `docs/HANDOFF.md` §3.4（坑：换 baseUrl 后必须重启 app；`openfirst` 要等会话列表载入，否则报“没有会话”）。
- 截图存 `docs/screenshots/`（编号递增，**不要覆盖已有编号**）并在回复里给路径。

### 在 win 上跑 AVD（手机侧遥控）

从手机 SSH 到 win 驱动 `pipocket-qa`（2026-10-22 实跑一遍，坑都记在这）：

```bash
# 0) 模拟器必须跑在「不会随 ssh 断开的会话」里：start /b、Start-Process 都会被会话回收
#    ⇒ 用一条常驻 ssh 会话挂着它（headless 也够截图）
ssh -T win 'cmd /c "C:\Users\OhiyoX\AppData\Local\Android\Sdk\emulator\emulator.exe" -avd pipocket-qa -port 5556 -no-window -no-audio -no-boot-anim -gpu swiftshader_indirect'

# 1) 同步代码 + 送 APK（手机编好直接 scp，别在 win 上再编一遍）
cd ~/projects/pi-pocket/repo && git pull --ff-only && scp app/build/outputs/apk/debug/app-debug.apk \
  win:'C:/Users/OhiyoX/projects/pi-pocket/app/build/outputs/apk/debug/app-debug.apk'

# 2) 装机 → 配 baseUrl → **等 4s 再 force-stop**（不等的话 prefs 还没落盘，地址不生效！）
adb -s emulator-5556 install -r app\build\outputs\apk\debug\app-debug.apk
adb -s emulator-5556 shell am broadcast -f 0x00000020 -n id.harness.pipocket/.DebugProbeReceiver \
  -a id.harness.pipocket.DEBUG_PROBE --es action setconfig --es url http://10.0.2.2:8799 --ez cleartoken true
adb -s emulator-5556 shell am force-stop id.harness.pipocket && adb -s emulator-5556 shell am start -n id.harness.pipocket/.MainActivity
sleep 12 && adb -s emulator-5556 shell am broadcast … --es action openfirst && sleep 9
adb -s emulator-5556 shell input swipe 540 700 540 2100 400      # 往上翻（看更早的消息）
adb -s emulator-5556 exec-out screencap -p > shot.png
```

- 假桥要自己起（`dev-emulator.sh` 不负责 setconfig）：`node tools/mock-bridge.mjs --port 8799`。
- `timeout /t N >nul` 在 ssh 会话里会报 `Input redirection is not supported` ⇒ 睡眠放在**客户端**。
- `logcat -d -s PiPocketMermaid:I` 是 mermaid 尺寸/字号的自证通道（`natural=… viewportCss=… dpr=…`
  和原生 `box=…dp density=…`）：**dpr 必须等于 density、viewportCss 必须等于 box 的 dp 宽**，
  不等就说明 CSS px ≠ dp，字号/高度都会算错。
- QA 模拟器里 app 的 prefs 是**旧的**（`fontScale=chatFontScale=1.35`）—— 正好可以压字号那条路；
  要干净基线就 `adb shell pm clear id.harness.pipocket`。
- 软件渲染（swiftshader）跑 1200dp+ 的 WebView 很吃力：**手势之间要留 4~6s**，连发 swipe+tap
  会 ANR（不是 app 崩，是模拟器追不上）。

## Lint 抓到的真实缺陷（已修）

- `PiLocator` 用 `ConnectivityManager.getAllNetworks/getLinkProperties` 扫网段，但 **manifest 缺 `ACCESS_NETWORK_STATE`** → 真机上会 `SecurityException`。已补权限。

## 模拟器性能配置（2026-10-22，用户嫌卡）

QA 用 `tools/avd-perf.sh` 把 AVD 抬到「能顺畅调试」的档：

| 项 | 之前 | 现在 | 为什么 |
| --- | --- | --- | --- |
| CPU | 4 核 | 6 核 | 模拟器上限就是 6；`-cores 8` 会被压回 6 |
| 内存 | 3 GB | 6 GB | 大 markdown/WebView 会话不再频繁 GC |
| GPU | `swiftshader_indirect`（纯软件） | `host`（宿主 RTX 4060） | 帧时间从 ~117ms 掉到 ~53ms |
| 屏幕 | 1080x2400 @420 | **720x1600 @280** | **dp 尺寸完全不变**（411x914dp），像素只有 44% |
| 动画 | 系统默认 | 全 0（`window/transition/animator_scale`） | 自动化/看图更快 |
| heap | 256 MB | 512 MB | |

用法：

```bash
scp tools/avd-perf.sh win:C:/Users/OhiyoX/avd-perf.sh
ssh -T win 'C:\PROGRA~1\Git\bin\bash.exe /c/Users/OhiyoX/avd-perf.sh'   # 改 config.ini
# 出高清文档截图时：HIGH_RES=1 再跑一次（改回 1080x2400@420）
```

启动（**必须挂常驻 ssh 会话**，`start /b` 会被回收）：

```bash
ssh -T win 'cmd /c "C:\Users\OhiyoX\AppData\Local\Android\Sdk\emulator\emulator.exe" \
  -avd pipocket-qa -port 5556 -no-window -no-audio -no-boot-anim -gpu host -cores 6'
```

### 三个必踩的坑

1. **`skin.name` 优先于 `hw.lcd.*`**：只改 `hw.lcd.width/height` 不生效，guest 仍按旧尺寸起
   （日志里看 `androidboot.qemu.skin=`）。脚本两个都改。
2. **adb daemon 会被 ssh 会话回收**：`adb devices` 每次都打印「daemon not running; starting now」，
   模拟器甚至因连不上 daemon 而退出。解法：把 daemon 跑在常驻会话里
   `ssh -T win '<adb> -a nodaemon server'`，之后同一个 daemon 服务所有 adb 调用。
3. **`am broadcast` 要带 `-p`**：Android 8+ 对隐式广播有限制，探针收不到 ⇒ 返回 `result=0`。
   正确写法：`am broadcast -a id.harness.pipocket.DEBUG_PROBE -p id.harness.pipocket --es action openfirst`
   常用动作：`openfirst`（开会话）、`tree`（拉树并报条数/耗时）、`copy`（复制最后回答）、
   `export`（导出并报路径/体积）、`cyclethink`（思考等级快切并报前后值）。
   （action 是 `DEBUG_PROBE`，不是 `DEBUG`）。

### 假桥（`tools/mock-bridge.mjs`）的事件信封

**必须是 `kind`**（`{kind:'pi', record, sessionFile}`），不是 `type` —— 真桥就是这么发的。
假桥以前写成 `type`，于是 app 一个事件分支都进不去（表现：队列条/重试横幅永远不出现，
但 HTTP 一切正常）。连上时也要发 `{kind:'bridge', event:'hello'}`，与真桥一致。

### 取 ANR 堆栈（**不需要 root**）
```
adb -s emulator-5556 shell dumpsys dropbox --print data_app_anr > anr.txt
```
里面是 `dropbox` 存的完整线程堆栈（每个条目以 `SystemUptimeMs:` 开头，最后一条最新）。
找 `"main" prio=5 tid=1` 那段的 `at …` 帧就是**卡在哪**。
2026-10-22 就是这么定位到"WebView 建在组合/布局里"的（`MermaidKt.MermaidView` →
`AndroidComposeView.addAndroidView` → `AndroidViewHolder.onLayout`），
以及早先那次 `LayoutNode.detach` 递归爆栈。

### WebView 创建闸门（`ui/WebViewGate.kt`）
mermaid / html-visual / 工具 HTML 都是"一块一个 WebView"，创建发生在**主线程的组合+布局**里。
`DeferWebView(key, placeholderHeight)` 负责：延迟 `DELAY_MS=120` + 串行 `INTERVAL_MS=80`。
- 占位高度必须给**上限内的稳定值**，否则图出来时会把下文顶走；
- 流式回答里的图**不渲染**（`MarkdownText(deferDiagrams = item.streaming)`），
  否则每来一个 token 就重建一次 WebView；
- 模拟器上单次创建仍会吃掉 0.5–1s 的一帧（Chromium 初始化），真机明显更快。
  闸门只保证**不叠加**，不保证单次很快 —— 别把"偶发掉帧"当成回归。

### 压缩事件（假桥）
```
curl 'http://127.0.0.1:8799/mock/emit?type=compaction_start'
curl 'http://127.0.0.1:8799/mock/emit?type=compaction_end'                # 默认 18k → 4.1k + 摘要
curl 'http://127.0.0.1:8799/mock/emit?type=compaction_end&tokensBefore=82505&estimatedTokensAfter=780&summary=xx'
```
`compaction_end` 的 `result` 形状必须和真桥一致：`{tokensBefore, estimatedTokensAfter, summary}`
（app 从 `result` 里取，不在顶层）。

### 假桥要**常驻**跑
`ssh win "… node tools\mock-bridge.mjs --port 8799"` 必须留在一个常驻会话里；
用 `start /b` 或 `ssh` 退出即结束的方式启动，会出现"健康检查 200、但 app 里的 info/messages 全 404/断连"
的假象（2026-10-22 踩过：健康探测的 200 来自命令内联那次 curl，进程其实已经没了）。

### 重连实验（怎么真正把桥弄死）
**坑**：`kill_session` 杀掉 `ssh … node mock-bridge.mjs` 只会断 ssh，Windows 上的 node 仍然
LISTENING —— 实验会得出"桥没死"的假结论（2026-10-22 踩过）。按端口杀：
```
PID=$(ssh win "netstat -ano | findstr :8799" | grep LISTENING | head -1 | awk '{print $NF}')
ssh win "taskkill /F /PID $PID"
ssh win "netstat -ano | findstr :8799"     # 空 = 真停了
```
然后重启假桥（必须常驻会话），观察：
- 对话页顶部出现 `重连中（n） · host` 状态带（`ConnectionStrip`）；
- 桥回来后**不点任何东西**，状态带消失；
- `adb logcat -s PiPocket:*` 出现 `RESULT rehydrate file=… info=true messages=N`；
- 假桥日志出现 `/v1/sessions/open` + `/v1/info` + `/v1/messages`（证明三件事都重做了）。

### AVD 的 WebView 在这台机器上不可靠
`logcat | grep -i chromium` 里有 `aw_browser_terminator: Renderer process crash detected (code -1)`
和 10s 级 `Davey! duration=…` 时，先怀疑 AVD/Trichrome，不要直接当成 app 逻辑 bug：
- 用 `applicationContext` 建 WebView 会让渲染进程崩（试过，撤回）；
- 单次 `WebView(ctx)` 在这台 AVD 上要数秒，真机是百毫秒级；
- app 侧能做的已经做了：`WebViewGate` 串行 + 流式不渲染。

### 验证「逐字上屏」
```
# 2 字/260ms 推流（ASCII 文本，避免 URL 编码问题）
curl "http://127.0.0.1:8799/mock/stream?chars=2&delayMs=260&text=Typewriter-reveal-check-abc"
adb -s emulator-5556 logcat -s PiPocketTypewriter      # shown=… total=… / caught-up
adb -s emulator-5556 logcat -s PiPocketDebug           # 探针 --es action state：streaming/lastLen
```
判据：日志里 `shown` 应该**一段一段往上走**并在每个 delta 后 `caught-up`；
截图连拍能看到文本逐渐变长（t+1.6s 只显示前 16 个字符）。
**坑**：URL 里直接写中文会让 Node 回 400（非法请求行），必须 URL-encode。

## 「发送失败保留草稿」怎么复现（不用真把桥弄死）

mock 桥支持人为让发送失败：

```bash
curl 'http://127.0.0.1:8799/mock/fail?prompt=1'   # 打开：POST /v1/prompt 回 500（形状同真桥出错）
curl 'http://127.0.0.1:8799/mock/fail?prompt=0'   # 关掉
```

然后：输入文字 → 发送 → 出现「发送失败，内容已保留」+ 输入框上方「没发出去」条。
想验"进程被杀也不丢"：`adb shell am force-stop id.harness.pipocket` 再启动，回到同一会话，条还在。
点「恢复编辑」后文本回到输入框；关掉开关再发一次，`pi-pocket.xml` 里的 `outbox` 会被清掉。

## 设备画布上报（屏幕/字号/主题）

app 会在启动、旋转、改字号、换主题时 `POST /v1/device`；mock 桥把最后一次记下来：

```bash
curl -s 'http://127.0.0.1:8799/mock/device'     # 看 app 报了哪些字段
adb logcat -s PiPocketDebug | grep 'device 已上报'   # 同一份内容的签名
```

真桥侧会写两个文件（在 `~/.pi/agent/pi-pocket-app-profile/`）：`device.json`（原始）与
`device-context.md`（拼进 pi 提示的那段），可以用
`ps aux | grep 'append-system-prompt'` 看是不是两个参数都挂上了。

## html-visual 宿主页 JS 改完先跑语法自检

```bash
node script/check-htmlvisual-js.mjs    # 4 种 light/fullscreen 组合各解析一遍
```

这段 JS 一旦有语法/引用错误，页面会 `reportEmpty` ⇒ 整块退回代码块（只有真机才看得见）。


## CI（Gitea Actions）2026-10-24 修好记录

连续多轮 PR 的 CI 都是红的，**与代码无关**。三层原因，按发现顺序：

1. **`tools` 包已下架**：`android-actions/setup-android@v3` 的 `packages` 默认值是
   `tools platform-tools`，而 SDK 仓库里已经没有 `tools` ⇒ `sdkmanager tools` 报
   `Failed to find package 'tools'` 并 exit 1，后面所有步骤 skipped。
   → `packages: "platform-tools"`；另加一步预装 `platforms;android-37` + `build-tools;37.0.0`
   （装不上只 warning，交给 Gradle 的 `sdkDownload` 兜底）。
2. **Gradle daemon 被 OOM kill**：三个 gradle 步骤共用常驻 daemon（`-Xmx2560m`），
   act 容器里 assemble 阶段报 `Gradle build daemon disappeared unexpectedly`。
   → 每个 gradle 步骤加 `--no-daemon`；另外加一步打印 `free -m / nproc / df`，下次好判断。
3. **upload-artifact@v4 不被 Gitea 支持**：报 `GHESNotSupportedError: @actions/artifact v2.0.0+`。
   → 改 `actions/upload-artifact@v3`。

验证：run **1607**（`3e282d3`）全绿 —— checkout / JDK / SDK / 预装 / 资源 / 单测 / lint / assemble /
桥脚本 / 上传报告，10 步全部 success。

⚠️ 另一条：workflow 只监听 `push: [main]` 与 `pull_request`，所以**feature 分支单独 push 不触发 CI**，
必须先开 PR。
