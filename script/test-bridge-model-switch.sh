#!/data/data/com.termux/files/usr/bin/bash
# 模型切换（桥/pi 侧）能不能真的生效：在**独立会话**上试，别动正在用的会话。
set -u
REPO="$(cd "$(dirname "$0")/.." && pwd)"
# 一次性测试目录：进临时区，不落 home / 不落会话区（vault placement.md 硬规则 2）
DIR="${TMPDIR:-/tmp}/pptest2-model-switch"
PORT=8798
mkdir -p "$DIR"
SEED=$(ls -t "$HOME"/.pi/agent/sessions/*/*.jsonl 2>/dev/null | tail -1)
TARGET="$DIR/model-target-$RANDOM.jsonl"
python3 - "$SEED" "$TARGET" "$DIR" <<'PY'
import json, sys
src, dst, cwd = sys.argv[1], sys.argv[2], sys.argv[3]
first = True
with open(src) as f, open(dst, "w") as o:
    for line in f:
        if first:
            h = json.loads(line); h["cwd"] = cwd; o.write(json.dumps(h) + "\n"); first = False
        else: o.write(line)
PY
echo "测试会话: $TARGET"

PI_POCKET_TOKEN= PI_POCKET_PORT=$PORT PI_POCKET_CWD="$DIR" \
PI_POCKET_STATE="$DIR/state.json" PI_POCKET_IDLE_MS=0 \
  node "$REPO/termux/pi-pocket-bridge.mjs" > "$DIR/bridge.log" 2>&1 &
PID=$!
for _ in $(seq 1 40); do curl -s -m 2 "http://127.0.0.1:$PORT/v1/health" >/dev/null 2>&1 && break; sleep 0.25; done

api() { curl -s -m 60 -X "${1:-GET}" "http://127.0.0.1:$PORT/v1/$2" -H 'content-type: application/json' ${3:+-d "$3"}; }
model_of_info() { api GET info | python3 -c 'import json,sys; d=json.load(sys.stdin)["data"]; m=d["state"].get("model") or {}; print(m.get("provider"),"/",m.get("id"), "| thinking:", d["state"].get("thinkingLevel"))' 2>/dev/null; }

echo "1) open → $(api POST sessions/open "{\"file\":\"$TARGET\"}" | head -c 80)"
echo "   当前模型: $(model_of_info)"

echo "2) set_model deepseek/deepseek-flash →"
api POST model '{"provider":"deepseek","model":"deepseek-flash"}' | head -c 400; echo
echo "   之后模型: $(model_of_info)"

echo "3) set_model auto-provider/deepseek-v4.1-flash-auto（虚拟 provider）→"
api POST model '{"provider":"auto-provider","model":"deepseek-v4.1-flash-auto"}' | head -c 400; echo
echo "   之后模型: $(model_of_info)"

echo "4) cycle（{cycle:true}）→"
api POST model '{"cycle":true}' | head -c 300; echo
echo "   之后模型: $(model_of_info)"

echo "5) 只给 model 不给 provider →"
api POST model '{"model":"deepseek-flash"}' | head -c 300; echo
echo "   之后模型: $(model_of_info)"

kill $PID 2>/dev/null
echo "（桥已停；日志 $DIR/bridge.log）"
