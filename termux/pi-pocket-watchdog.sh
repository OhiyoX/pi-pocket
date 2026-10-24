#!/data/data/com.termux/files/usr/bin/bash
# pi-pocket 桥的**外层**兜底 watchdog —— termux-job-scheduler job 8899 每 15 分钟调一次（persisted）。
#
# 分工（2026-10-24 起）：
#   * 内层：termux-services 的 runsv 服务 `pi-pocket`（$PREFIX/var/service/pi-pocket/run）
#           —— 秒级重启，Termux 会话/应用在的时候由它负责。
#   * 外层：本脚本 —— runsvdir 自己没了（Termux 应用被系统杀掉、设备重启后 Termux 没被打开、
#           OOM 把 service-daemon 一起带走）时，把桥重新拉起来。策略全在 start-bridge.sh：
#           有服务就 sv，没服务就直起，等 runsvdir 回来再由服务收归。
#
# 稳健化：flock 并发锁 + node 探针（不依赖 curl）+ 日志轮转 + token 缺失降级。
set -u

PREFIX="${PREFIX:-/data/data/com.termux/files/usr}"
export PREFIX
export HOME="${HOME:-/data/data/com.termux/files/home}"
export SVDIR="${SVDIR:-$PREFIX/var/service}"
case ":$PATH:" in
  *":$PREFIX/bin:"*) ;;
  *) export PATH="$PREFIX/bin:$HOME/bin:$PATH" ;;
esac

LOG="$HOME/.pi/agent/pi-pocket-watchdog.log"
LOCK="$HOME/.pi/agent/pi-pocket-watchdog.lock"
PORT="${PI_POCKET_PORT:-8789}"

# 日志轮转：>500 行截半
if [ -f "$LOG" ] && [ "$(wc -l <"$LOG")" -gt 500 ]; then
  tail -250 "$LOG" >"$LOG.tmp" && mv "$LOG.tmp" "$LOG"
fi

# 并发保护：另一个 watchdog 在跑就退出（flock -n 非阻塞）
exec 9>"$LOCK"
flock -n 9 || exit 0

TOKEN=$(sed -n 's/^PI_POCKET_TOKEN=//p' "$HOME/.pi/agent/pi-pocket.env" 2>/dev/null)

# `sv-disable` = 用户/运维有意长期停用（写 `down` 标记）。watchdog 是无人值守的，不能自己把它拉回来
# （15 分钟一轮，否则一停就被偷启，`sv-disable` 形同虚设）。
# 注：`sv down` 只是临时停（不写标记），watchdog 会按保活职责把它拉回。
if [ -e "$SVDIR/pi-pocket/down" ]; then
  exit 0
fi

probe() {
  PI_POCKET_PROBE_TOKEN="$TOKEN" PI_POCKET_PROBE_PORT="$PORT" node -e '
    const t = process.env.PI_POCKET_PROBE_TOKEN || "";
    fetch("http://127.0.0.1:" + process.env.PI_POCKET_PROBE_PORT + "/v1/health", {
      headers: t ? { authorization: "Bearer " + t } : {},
      signal: AbortSignal.timeout(3000),
    }).then(r => process.stdout.write(String(r.status))).catch(() => process.stdout.write("000"));
  ' 2>/dev/null
}

healthy=0
code=down
if [ -z "$TOKEN" ]; then
  # token 缺失时 health 会 401（不是"桥挂了"）：进程在就算健康，交给人补 token
  if [ -x "$SVDIR/pi-pocket/run" ] && command -v sv >/dev/null 2>&1; then
    case "$(sv status pi-pocket 2>/dev/null)" in
      run:*) healthy=1 ;;
    esac
  elif pgrep -f 'bin/pi-pocket-bridge$' >/dev/null 2>&1; then
    healthy=1
  fi
else
  code=$(probe)
  [ "$code" = "200" ] && healthy=1
fi

if [ "$healthy" = "1" ]; then
  exit 0
fi

echo "$(date -u +%FT%TZ) bridge unhealthy (probe=${code}), restarting via start-bridge.sh" >>"$LOG"
FORCE=1 bash "$HOME/.pi/agent/start-bridge.sh" >>"$LOG" 2>&1
