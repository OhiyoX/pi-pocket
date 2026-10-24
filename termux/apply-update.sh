#!/data/data/com.termux/files/usr/bin/bash
# pi-pocket 自更新脚本（由 app 的 GiteaUpdater 下载到 /sdcard/Download/pi-pocket-update/ 后执行）。
#
# 做的事：备份旧桥 → 覆盖 $PREFIX/bin 的桥与启动器 → 同步 skills/app-profile → 重启桥 → 打印版本。
# 幂等；失败不动正在跑的桥（先备份再覆盖，覆盖前不 kill）。
set -eu

DEST="$PREFIX/bin"
AGENT="${PI_CODING_AGENT_DIR:-$HOME/.pi/agent}"
APPDIR="$AGENT/pi-pocket-app-profile"
SRC="$(cd "$(dirname "$0")" && pwd)"
STAMP="$(date -u +%Y%m%dT%H%M%SZ)"

# 日志落到下载目录（app 的 termuxrun 不方便带 shell 重定向）
LOG="/sdcard/Download/pi-pocket-update/apply.log"
if [ -d /sdcard/Download/pi-pocket-update ]; then exec >>"$LOG" 2>&1; fi

echo "== pi-pocket 自更新 $STAMP =="
echo "src=$SRC"
echo "dest=$DEST"

mkdir -p "$DEST"
# Refuse incomplete bundles before touching a working installation.
for module in tree-from-file.mjs rpc-channel.mjs event-stream.mjs; do
  [ -s "$SRC/termux__$module" ] || { echo "缺少桥依赖: $module"; exit 1; }
done

# 1) 备份
if [ -f "$DEST/pi-pocket-bridge" ]; then
  cp "$DEST/pi-pocket-bridge" "$DEST/pi-pocket-bridge.bak-$STAMP"
  echo "备份: pi-pocket-bridge.bak-$STAMP"
fi

# 2) 覆盖桥 + 启动器（Termux 没有 /usr/bin/env：shebang 必须指到 $PREFIX/bin/env）
for module in tree-from-file.mjs rpc-channel.mjs event-stream.mjs; do
  install -m 644 "$SRC/termux__$module" "$DEST/$module"
done
mkdir -p "$DEST/tool-render/adapters"
install -m 644 "$SRC/termux__tool-render__index.mjs" "$DEST/tool-render/index.mjs"
for adapter in web-access unified-exec; do
  install -m 644 "$SRC/termux__tool-render__adapters__$adapter.mjs" "$DEST/tool-render/adapters/$adapter.mjs"
done
install -m 755 "$SRC/termux__pi-pocket-bridge.mjs" "$DEST/pi-pocket-bridge"
sed -i "1s|.*|#!$DEST/env node|" "$DEST/pi-pocket-bridge"
install -m 755 "$SRC/termux__pi-pocket-start" "$DEST/pi-pocket-start"
echo "已覆盖: pi-pocket-bridge / pi-pocket-start"

# 2b) 手机操作工具箱 droid（拍平后是 termux__tools__droid__droid）
if [ -f "$SRC/termux__tools__droid__droid" ]; then
  install -m 755 "$SRC/termux__tools__droid__droid" "$DEST/droid"
  sed -i "s/
$//" "$DEST/droid" 2>/dev/null || true
  # shebang 也得改：仓内是 `#!/usr/bin/env python3`，而 Termux 没有 /usr/bin/env。
  # 之前只改了桥的 shebang ⇒ 自更新后 droid 直接 `bad interpreter: No such file or directory`。
  if [ -x "$DEST/env" ]; then
    sed -i "1s|.*|#!$DEST/env python3|" "$DEST/droid"
  fi
  echo "已安装: droid（shebang: $(head -1 "$DEST/droid")）"
fi

# 3) skills 与 app-profile（app 会话注入用；放在 agent 目录下，桥按目录找）
mkdir -p "$APPDIR/skills/zcode-visualize" "$APPDIR/skills/droid-phone-ops"
[ -f "$SRC/termux__app-profile__append-system.md" ] && install -m 644 "$SRC/termux__app-profile__append-system.md" "$APPDIR/append-system.md"
[ -f "$SRC/termux__app-profile__extension.mjs" ] && install -m 644 "$SRC/termux__app-profile__extension.mjs" "$APPDIR/extension.mjs"
[ -f "$SRC/termux__app-profile__pocket-term.mjs" ] && install -m 644 "$SRC/termux__app-profile__pocket-term.mjs" "$APPDIR/pocket-term.mjs"
[ -f "$SRC/termux__app-profile__mcp-presets.json" ] && install -m 644 "$SRC/termux__app-profile__mcp-presets.json" "$APPDIR/mcp-presets.json"
[ -f "$SRC/termux__skills__zcode-visualize__SKILL.md" ] && install -m 644 "$SRC/termux__skills__zcode-visualize__SKILL.md" "$APPDIR/skills/zcode-visualize/SKILL.md"
[ -f "$SRC/termux__skills__droid-phone-ops__SKILL.md" ] && install -m 644 "$SRC/termux__skills__droid-phone-ops__SKILL.md" "$APPDIR/skills/droid-phone-ops/SKILL.md"
echo "已同步: $APPDIR"

# 4) 重启桥（先探活，token 不匹配或没在跑才动手；只杀桥本体，结尾锚避免误伤）
PIDFILE="$AGENT/pi-pocket-bridge.pid"
if pgrep -f 'bin/pi-pocket-bridge$' >/dev/null 2>&1; then
  echo "停旧桥…"
  pkill -f 'bin/pi-pocket-bridge$' || true
  sleep 1
fi

if command -v termux-wake-lock >/dev/null 2>&1; then termux-wake-lock || true; fi
# 用启动器起（它会落/复用 token、设 cwd）
nohup "$DEST/pi-pocket-start" >>"$AGENT/pi-pocket-start.log" 2>&1 &
echo "已重启: pi-pocket-start（日志 $AGENT/pi-pocket-start.log）"

sleep 2
echo "--- health ---"
CODE=$(node -e 'const t=process.env.PI_POCKET_TOKEN||"";const h=t?{authorization:"Bearer "+t}:{};fetch("http://127.0.0.1:8789/v1/health",{headers:h}).then(r=>r.text()).then(x=>process.stdout.write(x)).catch(()=>process.stdout.write("unreachable"))' 2>/dev/null || echo unreachable)
echo "$CODE" | head -c 400
echo
echo "--- 路由自检 ---"
for route in /v1/health /v1/file /v1/app-profile; do
  echo -n "$route → "
  node -e 'const p=process.argv[1];const t=process.env.PI_POCKET_TOKEN||"";const h=t?{authorization:"Bearer "+t}:{};fetch("http://127.0.0.1:8789"+p,{headers:h}).then(r=>process.stdout.write(String(r.status))).catch(()=>process.stdout.write("000"))' "$route" 2>/dev/null
  echo
done
echo "== 完成 $STAMP =="
