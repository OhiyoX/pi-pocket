#!/data/data/com.termux/files/usr/bin/bash
# 「桥重启后当前会话会不会丢」的回归验证（HANDOFF §16 的那条 bug）。
#
# 做法：拿**真的 pi** 起两个桥进程（旧/新）各一轮，判据是重启后**不调 /v1/sessions/open**、
# 直接 GET /v1/info 时 worker 绑的是哪个会话：
#   旧桥：sessionFile 变成一条凭空新建的空会话（= app 里"变成新会话"的 bug）
#   新桥：sessionFile 仍是重启前那个会话
#
# 用法：
#   script/test-bridge-session-restore.sh                 # 对比 $PREFIX/bin/pi-pocket-bridge 与仓库里的桥
#   script/test-bridge-session-restore.sh <旧桥> <新桥>    # 指定两个桥脚本
# 注意：只跑 get_state/switch_session，不产生模型调用（不花 token）；端口固定 8798，别和正式桥撞。
set -u
REPO="$(cd "$(dirname "$0")/.." && pwd)"
OLD="${1:-$PREFIX/bin/pi-pocket-bridge}"
NEW="${2:-$REPO/termux/pi-pocket-bridge.mjs}"
# 一次性测试目录进临时区（不落 home / 不落会话区）；会话目录名 = "-" + cwd 里 "/"→"-" + "-"
DIR="${TMPDIR:-/tmp}/pptest-session-restore"
SESSDIR="$HOME/.pi/agent/sessions/-$(printf '%s' "$DIR" | sed 's|/|-|g')-"
PORT=8798
mkdir -p "$DIR" "$SESSDIR"

# 造一个 cwd 指向本测试目录的会话文件（复制最近一条真会话，只改第一行的 cwd）
SEED=$(ls -t "$HOME"/.pi/agent/sessions/*/*.jsonl 2>/dev/null | head -1)
[ -n "$SEED" ] || { echo "找不到任何会话文件做种子"; exit 1; }
TARGET="$DIR/target-$RANDOM.jsonl"
python3 - "$SEED" "$TARGET" "$DIR" <<'PY'
import json, sys
src, dst, cwd = sys.argv[1], sys.argv[2], sys.argv[3]
first = True
with open(src) as f, open(dst, "w") as o:
    for line in f:
        if first:
            head = json.loads(line)
            head["cwd"] = cwd
            o.write(json.dumps(head) + "\n")
            first = False
        else:
            o.write(line)
PY

api() { curl -s -m 90 -X "${1:-GET}" "http://127.0.0.1:$PORT/v1/$2" -H 'content-type: application/json' ${3:+-d "$3"}; }
session_of_info() {
  local raw; raw=$(api GET info)
  echo "$raw" | python3 -c 'import json,sys; print(json.load(sys.stdin)["data"]["state"].get("sessionFile"))' 2>/dev/null \
    || echo "<拿不到：$(echo "$raw" | head -c 160)>"
}

start_bridge() { # $1=bridge $2=statedir
  PI_POCKET_TOKEN= PI_POCKET_PORT=$PORT PI_POCKET_CWD="$DIR" \
  PI_POCKET_STATE="$2/state.json" PI_POCKET_IDLE_MS=0 \
    node "$1" >> "$2/bridge.log" 2>&1 &
  echo $!
  for _ in $(seq 1 40); do curl -s -m 2 "http://127.0.0.1:$PORT/v1/health" >/dev/null 2>&1 && return; sleep 0.25; done
}

# 停桥并**等端口真的空出来**（不等的话第二个实例会 EADDRINUSE，表现就是后面全是空响应）
stop_bridge() { # $1=pid
  kill "$1" 2>/dev/null
  for _ in $(seq 1 40); do curl -s -m 2 "http://127.0.0.1:$PORT/v1/health" >/dev/null 2>&1 || return; sleep 0.25; done
  kill -9 "$1" 2>/dev/null
  for _ in $(seq 1 20); do curl -s -m 2 "http://127.0.0.1:$PORT/v1/health" >/dev/null 2>&1 || return; sleep 0.25; done
  echo "  !! 端口 $PORT 一直没释放"
}

run_phase() { # $1=label $2=bridge $3=statedir
  echo "──────── $1 ────────"
  mkdir -p "$3"; rm -f "$3/state.json"
  # 无扩展名副本：$PREFIX/bin 里的桥就是没扩展名的，而 node 对未知扩展名（.bak-xxx）会拒跑
  cp "$2" "$3/bridge-src"
  local pid; pid=$(start_bridge "$3/bridge-src" "$3")
  if ! curl -s -m 2 "http://127.0.0.1:$PORT/v1/health" >/dev/null 2>&1; then
    echo "  !! 桥没起来，见 $3/bridge.log"; tail -5 "$3/bridge.log"; kill "$pid" 2>/dev/null; return 1
  fi
  echo "1) open 目标会话 → $(api POST sessions/open "{\"file\":\"$TARGET\"}" | head -c 100)"
  echo "   重启前 /v1/info → $(session_of_info)"
  local snap; snap=$(ls -1 "$SESSDIR" 2>/dev/null | sort)
  stop_bridge "$pid"

  echo "2) 重启桥（模拟 app 的「强制重启桥」/ 自更新）"
  pid=$(start_bridge "$3/bridge-src" "$3")
  if ! curl -s -m 2 "http://127.0.0.1:$PORT/v1/health" >/dev/null 2>&1; then
    echo "  !! 重启后桥没起来，见 $3/bridge.log"; tail -5 "$3/bridge.log"; kill "$pid" 2>/dev/null; return 1
  fi
  echo "3) **不调 open**，直接 /v1/info → $(session_of_info)"
  echo "   目标会话 = $TARGET"
  echo "   （判据看上一行：旧桥那条会是 pi 新建的空会话；它的文件要等第一条消息落盘才出现在磁盘上）"
  stop_bridge "$pid"
  echo
}

run_phase "旧桥 $OLD" "$OLD" "$DIR/old"
run_phase "新桥 $NEW" "$NEW" "$DIR/new"
echo "结论：两段的「/v1/info → …」若不同（旧桥变成新文件、新桥仍是 target），就是这条 bug 与它的修复。"
