#!/usr/bin/env bash
# pi-pocket 开发回路（口径借 Codex 自带 app 开发技能 build-*-apps：**单一入口脚本** + 每轮渲染验证）
#
#   ./script/dev-emulator.sh            # 门禁 → 装机 → 拉起 → 打开会话 → 截图
#   ./script/dev-emulator.sh --no-build # 跳过门禁，只装机 + 截图（快速看改动）
#
# 只用自建模拟器（默认 emulator-5556 / AVD pipocket-qa）。**不要**碰用户的 5554 或真机。
# 截图固定落在 .dev/latest.png（.gitignore 里），随后必须 view_image 对比设计规格，
# 与规格不一致就继续改 —— 这是"渲染改动必须过视觉对比"的那条硬规矩。
set -euo pipefail
export MSYS_NO_PATHCONV=1

DEV="${DEV:-emulator-5556}"
MOCK_PORT="${MOCK_PORT:-8799}"
BASE_URL="http://10.0.2.2:${MOCK_PORT}"
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
OUT="$ROOT/.dev"
mkdir -p "$OUT"

say() { printf '\033[1;36m==>\033[0m %s\n' "$*"; }

# 1) mock 桥（会话列表才有内容；已经在跑就复用）
if ! curl -sf --max-time 3 --noproxy '*' -o /dev/null "http://127.0.0.1:${MOCK_PORT}/v1/health" 2>/dev/null; then
  say "启动 mock 桥 :${MOCK_PORT}"
  ( cd "$ROOT" && nohup node tools/mock-bridge.mjs --port "$MOCK_PORT" >"$OUT/mock-bridge.log" 2>&1 & )
  sleep 1.5
fi

# 2) 门禁（单元测试 + lint + 打包）
if [ "${1:-}" != "--no-build" ]; then
  say "门禁：test / lint / assembleDebug"
  ( cd "$ROOT" && ./gradlew :app:testDebugUnitTest :app:lintDebug :app:assembleDebug --console=plain \
      | grep -E "^e:|error:|FAILED|BUILD" | tail -5 )
fi

APK="app/build/outputs/apk/debug/app-debug.apk"
cd "$ROOT"
[ -f "$APK" ] || { echo "缺少 APK：$APK" >&2; exit 1; }

# 3) 装机（-r 覆盖安装，保留数据与配置）
say "安装到 $DEV"
adb -s "$DEV" install -r "$APK" | tail -1

# 4) 冷启动（改了 baseUrl 必须 force-stop，否则旧配置还在内存里）
say "拉起 app（baseUrl=$BASE_URL）"
adb -s "$DEV" shell am force-stop id.harness.pipocket
adb -s "$DEV" shell am start -n id.harness.pipocket/.MainActivity >/dev/null
sleep 6

# 5) 打开第一个会话（走 debug 探针，等价于点开列表项）
say "打开第一个会话"
adb -s "$DEV" shell am broadcast -f 0x00000020 -n id.harness.pipocket/.DebugProbeReceiver \
  -a id.harness.pipocket.DEBUG_PROBE --es action openfirst >/dev/null 2>&1 || true
sleep 5

# 6) 截图（下一动作：view_image 对比 specs）
adb -s "$DEV" exec-out screencap -p > "$OUT/latest.png"
say "截图 → $OUT/latest.png"
tail -3 "$OUT/mock-bridge.log" 2>/dev/null || true
