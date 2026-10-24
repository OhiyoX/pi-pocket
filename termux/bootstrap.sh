#!/data/data/com.termux/files/usr/bin/bash
# bootstrap.sh —— 在一台**空白 Termux** 上从零装好 pi + pi-pocket 桥。
#
#   bash bootstrap.sh                 # 全自动：装依赖 → 装 pi → 拉仓库 → 装桥 → 起桥 → 自检
#   bash bootstrap.sh --check         # 只体检（不改动任何东西）
#   REPO_TOKEN=xxx bash bootstrap.sh  # 私有仓库用 token 拉取（app 会把它传进来）
#
# 设计要点：
#   1. **幂等**：每一步都先判断"已经有了吗"，重复跑不会重复装/不会清掉现有 pi 配置。
#   2. **不覆盖用户数据**：只写 $PREFIX/bin/pi-pocket-*、~/.pi/agent/{pi-pocket.env,start-bridge.sh}，
#      以及（首次）~/.pi/agent/pi-pocket-app-profile/。
#   3. **失败早退**：任何关键步骤失败立刻退出并打印「下一步怎么办」，不留半装状态。
#   4. 全部输出到 stdout（app 侧的 RUN_COMMAND 会把日志显示出来）。
set -u

REPO="${PI_POCKET_REPO:-https://github.com/OhiyoX/pi-pocket.git}"
REPO_HTTPS="${PI_POCKET_REPO_HTTPS:-https://github.com/OhiyoX/pi-pocket.git}"
# 代码根约定（vault `60-Skills/vault-workspace-maintenance/references/placement.md`）：
# 新主机是 <代码根>/<项目>/repo（四区）；phone = ~/projects/pi-pocket/repo。老主机（host-a）是 ~/projects/pi-pocket。
# 已有检出就沿用 —— 否则同一台机器会 clone 出第二份。
pick_work() {
  for d in "$HOME/projects/pi-pocket/repo" "$HOME/workspace/pi-pocket"; do
    [ -d "$d/.git" ] && { printf '%s' "$d"; return; }
  done
  printf '%s' "$HOME/projects/pi-pocket/repo"
}
WORK="${PI_POCKET_WORK:-$(pick_work)}"
PORT="${PI_POCKET_PORT:-8789}"
ENVF="$HOME/.pi/agent/pi-pocket.env"
CHECK_ONLY=0
[ "${1:-}" = "--check" ] && CHECK_ONLY=1

say() { printf '%s\n' "$*"; }
step() { printf '\n=== %s ===\n' "$*"; }
die() { printf '\n[失败] %s\n' "$*"; exit 1; }
have() { command -v "$1" >/dev/null 2>&1; }

say "pi-pocket bootstrap  $(date -u +%FT%TZ)  host=$(uname -m)  check_only=$CHECK_ONLY"

# ── 0) 体检 ────────────────────────────────────────────────────────────────
step "体检"
say "PREFIX       ${PREFIX:-<未设>}"
say "node         $(have node && node -v || echo 缺)"
say "git          $(have git && git --version | head -1 || echo 缺)"
say "npm          $(have npm && npm -v || echo 缺)"
say "pi           $(have pi && pi --version 2>/dev/null | head -1 || echo 缺)"
say "桥           $(have pi-pocket-bridge && echo 已装 || echo 缺)"
say "termux-api   $(have termux-toast && echo 已装 || echo 缺，可选)"
say "仓库目录     $WORK $([ -d "$WORK/.git" ] && echo '(已 clone)' || echo '(无)')"
if have pi-pocket-bridge && curl -s -o /dev/null -m 3 "http://127.0.0.1:$PORT/v1/health"; then
  say "桥在跑        是 (127.0.0.1:$PORT)"
else
  say "桥在跑        否"
fi
if [ "$CHECK_ONLY" = "1" ]; then
  say ""
  say "（--check：只看不改，结束）"
  exit 0
fi

# ── 1) 依赖 ────────────────────────────────────────────────────────────────
step "依赖（nodejs / git）"
if ! have node || ! have git; then
  have pkg || die "这台 Termux 没有 pkg（不是 Termux？）"
  pkg update -y >/dev/null 2>&1 || true
  pkg install -y nodejs git || die "pkg install nodejs git 失败（看上面输出）"
fi
have node || die "node 仍不可用"
have git || die "git 仍不可用"

# ── 2) pi ──────────────────────────────────────────────────────────────────
step "pi"
if have pi; then
  say "已装：$(pi --version 2>/dev/null | head -1)，跳过"
else
  say "安装 @earendil-works/pi-coding-agent …"
  npm install -g @earendil-works/pi-coding-agent || die "npm 安装 pi 失败"
  have pi || die "装完了但 pi 不在 PATH（检查 npm prefix）"
  say "已装：$(pi --version 2>/dev/null | head -1)"
fi

# ── 3) 仓库（拿 termux/ 下的桥与启动器）─────────────────────────────────────
step "pi-pocket 仓库"
if [ -d "$WORK/.git" ]; then
  ( cd "$WORK" && git pull --ff-only ) || say "（git pull 失败，用现有副本继续）"
else
  mkdir -p "$(dirname "$WORK")"
  if [ -n "${REPO_TOKEN:-}" ]; then
    git clone "https://x-access-token:$REPO_TOKEN@gitea.example.com/example/pi-pocket.git" "$WORK" \
      || die "clone 失败（token 无效？）"
  else
    git clone "$REPO" "$WORK" || git clone "$REPO_HTTPS" "$WORK" \
      || die "clone 失败：私有仓库需要 REPO_TOKEN=<gitea token>"
  fi
fi

# ── 4) 桥 + 启动器 + app-profile ────────────────────────────────────────────
step "安装桥 / 启动器"
if [ -x "$WORK/termux/install.sh" ]; then
  bash "$WORK/termux/install.sh" || die "install.sh 失败"
else
  say "仓库里没有 termux/install.sh？用仓库自带脚本回退"
fi
for f in pi-pocket-bridge pi-pocket-start; do
  [ -x "$PREFIX/bin/$f" ] || die "$PREFIX/bin/$f 没装上"
done

# ── 5) token + 起桥 ────────────────────────────────────────────────────────
step "启动桥"
mkdir -p "$(dirname "$ENVF")"
if [ ! -f "$ENVF" ] || ! grep -q '^PI_POCKET_TOKEN=' "$ENVF" 2>/dev/null; then
  TOKEN=$(head -c 16 /dev/urandom | od -An -tx1 | tr -d ' \n')
  printf 'PI_POCKET_TOKEN=%s\nPI_POCKET_PORT=%s\n' "$TOKEN" "$PORT" > "$ENVF"
  chmod 600 "$ENVF"
  say "已生成 token（写入 $ENVF，权限 600）"
else
  say "沿用已有 token"
fi
if [ -x "$HOME/.pi/agent/start-bridge.sh" ]; then
  FORCE=1 bash "$HOME/.pi/agent/start-bridge.sh" || say "（start-bridge.sh 返回非 0，继续体检）"
else
  die "缺 ~/.pi/agent/start-bridge.sh（install.sh 应该会装它）"
fi

# ── 6) 自检 ────────────────────────────────────────────────────────────────
step "自检"
T=$(sed -n 's/^PI_POCKET_TOKEN=//p' "$ENVF" | head -1)
ok=1
for r in /v1/health /v1/file /v1/app-profile /v1/config/machines; do
  code=$(curl -s -o /dev/null -w '%{http_code}' -m 6 -H "Authorization: Bearer $T" "http://127.0.0.1:$PORT$r" || echo 000)
  printf '%-24s %s\n' "$r" "$code"
  [ "$code" = "200" ] || ok=0
done
say ""
if [ "$ok" = "1" ]; then
  say "✅ 完成。app 里把机器指向 127.0.0.1:$PORT（token 见 $ENVF）"
else
  say "⚠️ 有人口没通：看 ~/.pi/agent/pi-pocket-start.log"
  exit 1
fi
