#!/data/data/com.termux/files/usr/bin/bash
# 手动（或由 app / SSH 调用）拉起 pi-pocket 桥：幂等，已在跑就退出。
LOG="$HOME/.pi/agent/pi-pocket-start.log"
mkdir -p "$(dirname "$LOG")"

if [ "${FORCE:-0}" != "1" ] && pgrep -f 'bin/pi-pocket-bridge$' >/dev/null 2>&1 && \
   [ "$(curl -s -o /dev/null -w '%{http_code}' -m 3 -H "Authorization: Bearer $(sed -n 's/^PI_POCKET_TOKEN=//p' "$HOME/.pi/agent/pi-pocket.env" 2>/dev/null)" http://127.0.0.1:8789/v1/health)" = "200" ]; then
  echo "bridge already healthy" >> "$LOG"
  exit 0
fi

pkill -f 'bin/pi-pocket-bridge$' 2>/dev/null || true
sleep 1
command -v termux-wake-lock >/dev/null 2>&1 && termux-wake-lock 2>/dev/null || true
cd "$HOME" || exit 1
setsid nohup "$PREFIX/bin/pi-pocket-start" >> "$LOG" 2>&1 < /dev/null &
echo "started at $(date -u +%FT%TZ)" >> "$LOG"
