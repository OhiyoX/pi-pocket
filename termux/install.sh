#!/usr/bin/env sh
# pi-pocket 桥的一键落地：Termux 落 $PREFIX/bin，其它 POSIX 落 ~/.local/bin。
# 幂等：重复跑只覆盖脚本本体，不动任何数据。
set -eu

ROOT=$(cd "$(dirname "$0")/.." && pwd)
SRC="$ROOT/termux/pi-pocket-bridge.mjs"

if [ -n "${PREFIX:-}" ] && [ -d "$PREFIX/bin" ]; then
  BIN="$PREFIX/bin"
else
  BIN="${PI_POCKET_BIN_DIR:-$HOME/.local/bin}"
fi
mkdir -p "$BIN"
# Install only the launcher without replacing live bridge modules.
if [ "${1:-}" = "--launcher-only" ]; then
  install -m 755 "$ROOT/termux/pi-pocket-start" "$BIN/pi-pocket-start"
  echo "installed launcher only: $BIN/pi-pocket-start"
  exit 0
fi
# All relative imports travel with the entry point (including the tree fast path).
for module in tree-from-file.mjs rpc-channel.mjs event-stream.mjs notify-payload.mjs; do
  install -m 644 "$ROOT/termux/$module" "$BIN/$module"
done
# app-profile（只在 app 会话注入：预设 MCP + pocket_notify 工具）：桥按 BRIDGE_DIR/app-profile 找
if [ -d "$ROOT/termux/app-profile" ]; then
  mkdir -p "$BIN/app-profile"
  cp "$ROOT/termux/app-profile/"* "$BIN/app-profile/" 2>/dev/null || true
fi
mkdir -p "$BIN/tool-render/adapters"
install -m 644 "$ROOT/termux/tool-render/index.mjs" "$BIN/tool-render/index.mjs"
for adapter in web-access unified-exec; do
  install -m 644 "$ROOT/termux/tool-render/adapters/$adapter.mjs" "$BIN/tool-render/adapters/$adapter.mjs"
done
cp "$SRC" "$BIN/pi-pocket-bridge"
chmod 755 "$BIN/pi-pocket-bridge"
# Termux/Android 没有 /usr/bin/env：把 shebang 改指到本机的 env（否则 exec 会报 “not found”）
if [ -x "$BIN/env" ]; then
  sed -i "1s|.*|#!$BIN/env node|" "$BIN/pi-pocket-bridge"
fi
cp "$ROOT/termux/pi-pocket-start" "$BIN/pi-pocket-start"
chmod 755 "$BIN/pi-pocket-start"

# ── 服务化（termux-services / runsv）+ home 侧兜底入口 ────────────────────────
# 桥以前靠 job-scheduler 每 15 分钟一次的 watchdog 拉（Android 最小周期），runsv 是秒级重启。
# 这里只落文件，**不自动 sv up / sv restart**：装的过程中不该掐断正在跑的会话，
# 何时（重）启动由调用方决定（termux/bootstrap.sh 会 FORCE=1 调 start-bridge.sh）。
AGENT="${PI_CODING_AGENT_DIR:-$HOME/.pi/agent}"
mkdir -p "$AGENT/bin" "$HOME/.termux/boot"
install -m 755 "$ROOT/termux/start-bridge.sh" "$AGENT/start-bridge.sh"
install -m 755 "$ROOT/termux/pi-pocket-watchdog.sh" "$AGENT/bin/pi-pocket-watchdog.sh"
install -m 700 "$ROOT/termux/boot/start-pocket" "$HOME/.termux/boot/start-pocket"
echo "installed: $AGENT/start-bridge.sh"
echo "installed: $AGENT/bin/pi-pocket-watchdog.sh   # job-scheduler 8899 兜底"
echo "installed: $HOME/.termux/boot/start-pocket   # Termux:Boot 开机自启"
SVDIR="${SVDIR:-${PREFIX:-}/var/service}"
if [ -n "${PREFIX:-}" ] && [ -d "$SVDIR" ] && command -v sv >/dev/null 2>&1 && [ -d "$ROOT/termux/service/pi-pocket" ]; then
  mkdir -p "$SVDIR/pi-pocket"
  install -m 755 "$ROOT/termux/service/pi-pocket/run" "$SVDIR/pi-pocket/run"
  if [ -e "$SVDIR/pi-pocket/down" ]; then
    echo "service:  $SVDIR/pi-pocket 已装（保留 down 标记 = 仍停用；sv-enable pi-pocket 启用）"
  else
    echo "service:  $SVDIR/pi-pocket 已装（sv status|up|restart pi-pocket）"
  fi
else
  echo "service:  跳过（没有 termux-services 或非 Termux 环境；用 start-bridge.sh 直起）"
fi

# droid（手机操作工具箱）：仓内 shebang 是 `#!/usr/bin/env python3`，而 Termux/Android **没有**
# /usr/bin/env ⇒ 装完不改就报 `bad interpreter: No such file or directory`。和桥一样改成 $PREFIX/bin/env。
if [ -f "$ROOT/termux/tools/droid/droid" ]; then
  install -m 755 "$ROOT/termux/tools/droid/droid" "$BIN/droid"
  sed -i "s/\r$//" "$BIN/droid" 2>/dev/null || true
  if [ -x "$BIN/env" ]; then
    sed -i "1s|.*|#!$BIN/env python3|" "$BIN/droid"
  fi
  echo "installed: $BIN/droid   # shebang: $(head -1 "$BIN/droid")"
fi

echo "installed: $BIN/pi-pocket-bridge"
echo "installed: $BIN/pi-pocket-start   # app(com.termux.RUN_COMMAND) 与手工都走它"
echo "run:       pi-pocket-bridge            # 127.0.0.1:8789"
echo "phone:     termux-wake-lock; nohup pi-pocket-bridge >> ~/.pi/agent/pi-pocket-bridge.log 2>&1 &"
echo "verify:    curl -s localhost:8789/v1/health"
echo "undo:      rm -f $BIN/pi-pocket-bridge"
