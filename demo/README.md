# Pocket Pi — demo site / 演示站点

A static, bilingual screenshot tour of Pocket Pi. It is the source of the GitHub Pages site and it is also kept in the private upstream so the screenshots and copy stay reviewable.

Pocket Pi 的静态中英双语截图导览，既是 GitHub Pages 的源文件，也随私有上游仓维护，方便截图和文案一起评审。

## What is here / 目录

```
demo/
  index.html                 product landing page / 产品落地页
  demo.html                  nine-screen screenshot tour / 九屏截图导览
  assets/
    styles.css               design tokens and layout / 设计变量与布局
    landing.js               language, theme, copy, lightbox / 语言、主题、复制、看图
    logo.svg                 launcher mark / 启动器图标
  screenshots/               Android build captures / Android 实拍
    01-sessions-dark.png
    02-chat-markdown.png
    03-tool-cards.png
    04-tables-mermaid.png
    05-context.png
    06-slash-commands.png
    07-machines.png
    08-shell.png
    09-sessions-light.png
```

## Language / 语言

The site starts in the browser language (`zh-*` → Chinese, otherwise English) and remembers the choice in `localStorage` under `pocketpi.lang`. The switch is in the top navigation.

站点默认跟随浏览器语言（`zh-*` 显示中文，其余显示英文），选择会记在 `localStorage` 的 `pocketpi.lang`。切换按钮在顶部导航。

## Preview / 本地预览

```sh
cd demo
python3 -m http.server 8899
# open http://127.0.0.1:8899/index.html
```

No build step and no external requests. The only network request is the page itself.

无需构建、无外部请求；除页面本身外不加载任何第三方资源。

## How the screenshots were captured / 截图怎么拍的

- Device: AVD `pipocket-qa`, 720×1600, Android 15.
- App: debug build from the same source snapshot, installed with `adb install -r`.
- Bridge: [`tools/mock-bridge.mjs`](../tools/mock-bridge.mjs) on the host, reached from the emulator as `10.0.2.2:8797`.
- Control: the debug probe (`id.harness.pipocket.DEBUG_PROBE`) set the bridge URL, theme, opened the first session and ran the shell command without tapping through private data.
- Mock session names, project directories and IPs are synthetic. IPs shown are local only: `10.0.2.2` (emulator host alias) and `127.0.0.1` (loopback); no real address appears.

- 设备：AVD `pipocket-qa`，720×1600，Android 15。
- App：同一源码快照的 debug 构建，用 `adb install -r` 安装。
- 桥：[`tools/mock-bridge.mjs`](../tools/mock-bridge.mjs) 跑在宿主机，模拟器通过 `10.0.2.2:8797` 连接。
- 控制：debug 探针（`id.harness.pipocket.DEBUG_PROBE`）设置桥地址、主题、打开首个会话并执行 Shell 命令，不需要点进任何真实数据。
- 假会话名称、项目目录与 IP 均为合成；出现的 IP 仅为本地地址：`10.0.2.2`（模拟器宿主机别名）与 `127.0.0.1`（回环）；没有真实地址。

Reproduce the bridge side:

```sh
node tools/mock-bridge.mjs --port 8797
adb -s emulator-5556 shell am broadcast -p id.harness.pipocket \
  -a id.harness.pipocket.DEBUG_PROBE --es action setconfig --es url http://10.0.2.2:8797
adb -s emulator-5556 shell am broadcast -p id.harness.pipocket \
  -a id.harness.pipocket.DEBUG_PROBE --es action openfirst
```

## Privacy / 隐私

- No analytics, telemetry, fonts, images or scripts from third parties.
- The screenshots contain synthetic names, paths and conversations only.
- The private upstream, not this directory, is the source of the app and bridge code.

- 无统计、无遥测，不加载第三方字体、图片或脚本。
- 截图只包含合成的名称、路径与会话。
- app 与桥的源码以私有上游为准，本目录只放站点资产。
