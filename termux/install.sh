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
