#!/data/data/com.termux/files/usr/bin/bash
# 拉起/重启 pi-pocket 桥（幂等）。
#
# 两种落地：
#   * 有 runsv 服务（termux-services 装了、runsvdir 在跑）⇒ 只发 sv 命令，让 runsv 独占所有权；
#       - 桥健康且非 FORCE：什么都不做；
#       - 桥不健康：sv up（runsv 会自己重试拉起）；
#       - FORCE=1：sv restart（安装/更新后必须真换进程）。
#   * 没有服务（runsvdir 没跑 / termux-services 未装）⇒ 后台起 pi-pocket-start：
#       它自己按健康响应里的精确 PID 停旧桥（不再用 pkill 命令行子串匹配），FORCE=1 无条件重启。
#
# 调用方：app 的「强制重启桥」（RUN_COMMAND）、桥的 POST /v1/restart、
#         watchdog（job-scheduler 8899，无人值守兜底）、~/.termux/boot/start-pocket、手工 SSH。
set -u

PREFIX="${PREFIX:-/data/data/com.termux/files/usr}"
export PREFIX
export HOME="${HOME:-/data/data/com.termux/files/home}"
export SVDIR="${SVDIR:-$PREFIX/var/service}"
LOG="$HOME/.pi/agent/pi-pocket-start.log"
ENV_FILE="$HOME/.pi/agent/pi-pocket.env"
SVC=pi-pocket
PORT="${PI_POCKET_PORT:-8789}"
FORCE="${FORCE:-0}"
mkdir -p "$(dirname "$LOG")"

# 健康探针：桥就是 node 跑的，用 node fetch 拿状态码（token 缺失时 401 也算"进程在"，但不是健康）
health_code() {
  PI_POCKET_PROBE_TOKEN="$(sed -n 's/^PI_POCKET_TOKEN=//p' "$ENV_FILE" 2>/dev/null)" \
  PI_POCKET_PROBE_PORT="$PORT" \
  node -e 'const t=process.env.PI_POCKET_PROBE_TOKEN||"";fetch("http://127.0.0.1:"+process.env.PI_POCKET_PROBE_PORT+"/v1/health",{headers:t?{authorization:"Bearer "+t}:{},signal:AbortSignal.timeout(2000)}).then(r=>process.stdout.write(String(r.status))).catch(()=>process.stdout.write("000"))' 2>/dev/null
}

# runsv 真的在管这个服务吗？service-daemon 不幂等，所以先看 runsvdir 是否存在
# （pgrep -x runsvdir 在 Termux 实测不可靠，用 ps 惯用式，与 sshd 那套一致）。
service_ready() {
  [ -x "$SVDIR/$SVC/run" ] || return 1
  command -v sv >/dev/null 2>&1 || return 1
  ps -eo args 2>/dev/null | grep -q '[r]unsvdir /'
}

if service_ready; then
  # `sv-disable` 是用户/运维**长期停用**（写 `down` 标记）：只有 FORCE=1（安装、更新、显式重启）
  # 才能把它拉回来，否则 watchdog / app 的普通拉起会在 15 分钟内把停用的桥偷偷复活。
  # 注：`sv down` 只是临时停，不写标记，watchdog 会按“保活”职责把它拉回（要长期停用用 sv-disable）。
  if [ "$FORCE" != "1" ] && [ -e "$SVDIR/$SVC/down" ]; then
    echo "$(date -u +%FT%TZ) 服务被 sv-disable 停用（$SVDIR/$SVC/down），尊重停用状态；恢复用 sv-enable $SVC" >>"$LOG"
    exit 0
  fi
  if [ "$FORCE" = "1" ]; then
    # FORCE=1 = 显式要求跑起来：先清 `down` 标记（sv-enable），否则 sv 把进程拉起来了、runsv 仍处于
    # “want down”（`sv status` 会写 `normally down`）—— 下一次崩就不再自愈，runsvdir 重启也不会起。
    if [ -e "$SVDIR/$SVC/down" ]; then
      sv-enable "$SVC" >>"$LOG" 2>&1 || rm -f "$SVDIR/$SVC/down"
    fi
    echo "$(date -u +%FT%TZ) FORCE=1 → sv restart $SVC" >>"$LOG"
    sv restart "$SVC" >>"$LOG" 2>&1 || sv up "$SVC" >>"$LOG" 2>&1 || true
  elif [ "$(health_code)" = "200" ]; then
    echo "$(date -u +%FT%TZ) bridge already healthy（service $SVC）" >>"$LOG"
    exit 0
  else
    echo "$(date -u +%FT%TZ) bridge unhealthy → sv up $SVC" >>"$LOG"
    sv up "$SVC" >>"$LOG" 2>&1 || true
  fi
  # 等健康：runsv 拉起 + 桥 bind 一般 1–3s
  i=0
  while [ "$i" -lt 20 ]; do
    if [ "$(health_code)" = "200" ]; then
      echo "$(date -u +%FT%TZ) service healthy after ${i}s" >>"$LOG"
      exit 0
    fi
    i=$((i + 1))
    sleep 1
  done
  echo "$(date -u +%FT%TZ) 服务 20s 内未变健康；看 $LOG 与 sv status $SVC" >>"$LOG"
  exit 1
fi

# ── 没有 runsv 服务：直起（无人监督，等 runsvdir 起来后由服务收归）──────────────
command -v termux-wake-lock >/dev/null 2>&1 && termux-wake-lock >/dev/null 2>&1 || true
cd "$HOME" || exit 1
if [ "$FORCE" = "1" ]; then
  FORCE=1 setsid nohup "$PREFIX/bin/pi-pocket-start" >>"$LOG" 2>&1 </dev/null &
else
  setsid nohup "$PREFIX/bin/pi-pocket-start" >>"$LOG" 2>&1 </dev/null &
fi
echo "$(date -u +%FT%TZ) 无服务（runsvdir 未跑）：直接起 pi-pocket-start（FORCE=$FORCE）" >>"$LOG"
