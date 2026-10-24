#!/data/data/com.termux/files/usr/bin/bash
# =============================================================================
# pi-pocket 手机原生构建（Termux / aarch64）—— 让 agent 在**手机上**自己构建 + 装机
#
#   用法：
#     termux/mobile-build.sh --setup                装/修工具链（幂等：缺什么补什么）
#     termux/mobile-build.sh --check                只体检，逐项 ok/缺失；缺必选项退出码 1
#     termux/mobile-build.sh                        构建（默认 :app:assembleDebug）
#     termux/mobile-build.sh --install              构建 + Shizuku 装机（没有 adb 也能装）
#     termux/mobile-build.sh --install :app:testDebugUnitTest :app:lintDebug
#     termux/mobile-build.sh --keystore <file>      用外部 debug.keystore（换机/首次必备，见下）
#     termux/mobile-build.sh --no-mirror            setup 时不写 ~/.gradle/init.gradle 镜像
#
#   环境变量：PP_SDK（默认 $HOME/android-sdk） PP_BT（默认 36.0.0） PP_PLATFORM（默认 android-37.0）
#
#   为什么长这样（2026-10-20 一手实测，phone）：
#   · Google 的 SDK 构建工具只发 linux-x86_64；AGP 会 exec build-tools 里的原生件 ⇒ aarch64
#     上必须换成 Termux / 静态 aarch64 版本。
#   · AGP 9.4.1 的「build-tools 完整性」检查要求这 6 个文件**都**在：
#       aapt, aapt2, aidl, dexdump, zipalign, split-select
#     缺一个就报 “Installed Build Tools revision X is corrupted”，而且一次只报一个（要逐个补）。
#   · Termux 包覆盖：aapt / aapt2 / aidl（`pkg install aapt aapt2 aidl`；**zipalign 在 aapt
#     包里**一起给）。dexdump / split-select Termux 没有 ⇒ 取 lzhiyong/android-sdk-tools 的
#     静态 aarch64 包（sha256 钉在下面）。
#   · 网络（phone 实测）：repo1.maven.org ≈150 KB/s（不可用）；华为云 maven ≈30 MB/s、
#     dl.google.com ≈23 MB/s、Gradle 分发腾讯镜像 ≈18 MB/s。所以依赖走镜像、Google 源留官方。
#   · 耗时：首次全量 1m15s（依赖已缓存），增量 10s，`clean` 重建 13s。
#   · debug 签名：装机必须与已装 app **同一个 debug.keystore**，否则 `pm install -r` 报
#     INSTALL_FAILED_UPDATE_INCOMPATIBLE。PC 装的 app ⇒ 用 PC 的 ~/.android/debug.keystore
#     （`--check` 会拿已装 app 的签名指纹跟本机 keystore 对比）。
#   · 手机上 `./gradlew` 没有可执行位（Windows 侧提交丢了 +x）⇒ 一律 `bash gradlew`。
# =============================================================================
set -u

PP_SDK="${PP_SDK:-$HOME/android-sdk}"
PP_BT="${PP_BT:-36.0.0}"
PP_PLATFORM="${PP_PLATFORM:-android-37.0}"
CMDLINE_TOOLS_BUILD="${CMDLINE_TOOLS_BUILD:-15859902}"
CMDLINE_TOOLS_SHA256="4e4c464f145a7512b57d088ac6c278c03c9eea610886b35a5e0804e74eedf583"
AARCH64_TOOLS_URL="https://github.com/lzhiyong/android-sdk-tools/releases/download/35.0.2/android-sdk-tools-static-aarch64.zip"
AARCH64_TOOLS_SHA256="db1cea2c4454d5f9c5a802646b2d1cf560b4ee7badbe23e51ab8e1881bb50fc2"
GRADLE_DIST_MIRROR="https://mirrors.cloud.tencent.com/gradle"
GRADLE_DIST_OFFICIAL="https://services.gradle.org/distributions"
GRADLE_DIST_SHA256="dce76f55f8e251a3a1f130eb120f30b3d271de2b76c9b0729d316b5a1b6dc01f"
# 构建工作区（工具链 + 日志）：代码根四区里的 shared/（跨 lane 共享的依赖/工具，2026-10-22 从 ~/projects/pp-native-build 迁来）
REPO_ROOT="$(cd "$(dirname "$0")/.." && pwd)"
WORK="${PP_BUILD_WORK:-$REPO_ROOT/../shared/pp-native-build}"

MODE="build"
DO_INSTALL=0
NO_MIRROR=0
KEYSTORE_SRC=""
TASKS=()
while [ $# -gt 0 ]; do
  case "$1" in
    --setup) MODE="setup" ;;
    --check) MODE="check" ;;
    --install) DO_INSTALL=1 ;;
    --no-mirror) NO_MIRROR=1 ;;
    --keystore) shift; KEYSTORE_SRC="${1:-}" ;;
    -h|--help) sed -n '2,30p' "$0"; exit 0 ;;
    -*) echo "未知参数: $1（--help 看用法）" >&2; exit 2 ;;
    *) TASKS+=("$1") ;;
  esac
  shift
done
[ "${#TASKS[@]}" -eq 0 ] && TASKS=(":app:assembleDebug")
# 允许 `mobile-build.sh assembleDebug` 这种简写
for i in "${!TASKS[@]}"; do case "${TASKS[$i]}" in :*) ;; *) TASKS[$i]=":app:${TASKS[$i]}" ;; esac; done

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
BT="$PP_SDK/build-tools/$PP_BT"
FAILED=0

say()  { printf '%s\n' "$*"; }
ok()   { printf '  ok    %s\n' "$*"; }
miss() { printf '  缺失  %s\n' "$*"; FAILED=1; }
warn() { printf '  注意  %s\n' "$*"; }
have() { [ -x "$1" ]; }

need_termux() {
  case "${PREFIX:-}" in *com.termux*) ;; *) echo "这不是 Termux（PREFIX=$PREFIX）—— 本脚本只用于手机侧。" >&2; exit 2 ;; esac
}

setup_env() { # sdkmanager / gradlew 都要求 JAVA_HOME（cmdline-tools 的启动器不认 PATH 里的 java）
  local jh; jh="$(jdk_home)" || return 1
  export JAVA_HOME="$jh"
  export PATH="$jh/bin:$PATH"
  export ANDROID_HOME="$PP_SDK" ANDROID_SDK_ROOT="$PP_SDK"
  return 0
}

jdk_home() {
  for d in "$PREFIX/lib/jvm/java-21-openjdk" "$PREFIX/lib/jvm/java-17-openjdk"; do
    [ -x "$d/bin/java" ] && { echo "$d"; return 0; }
  done
  return 1
}

sha256_of() { sha256sum "$1" | cut -d' ' -f1; }

fetch() { # fetch <url> <dest> [sha256]
  local url="$1" dest="$2" want="${3:-}"
  if [ -s "$dest" ] && { [ -z "$want" ] || [ "$(sha256_of "$dest")" = "$want" ]; }; then
    return 0
  fi
  say "  下载 $url"
  curl -fL --retry 3 --retry-delay 2 -o "$dest.part" "$url" || { rm -f "$dest.part"; return 1; }
  mv -f "$dest.part" "$dest"
  if [ -n "$want" ]; then
    local got; got="$(sha256_of "$dest")"
    [ "$got" = "$want" ] || { echo "  sha256 不符：期望 $want 实得 $got" >&2; return 1; }
  fi
}

# ---------------------------------------------------------------- 1. Termux 包
step_pkgs() {
  say "[1/6] Termux 包（JDK + Android 构建工具）"
  local missing=()
  have "$PREFIX/bin/java"       || missing+=(openjdk-21)
  have "$PREFIX/bin/aapt2"      || missing+=(aapt2)
  have "$PREFIX/bin/aidl"       || missing+=(aidl)
  have "$PREFIX/bin/zipalign"   || missing+=(aapt)     # zipalign 由 aapt 包提供
  have "$PREFIX/bin/aapt"       || missing+=(aapt)
  have "$PREFIX/bin/unzip"      || missing+=(unzip)
  have "$PREFIX/bin/git"        || missing+=(git)
  if [ "${#missing[@]}" -gt 0 ]; then
    say "  pkg install -y ${missing[*]}"
    pkg install -y "${missing[@]}" >/dev/null 2>&1 || { miss "pkg install 失败"; return 1; }
  fi
  have "$PREFIX/bin/java" && ok "java: $(java -version 2>&1 | head -1)" || miss "java"
  have "$PREFIX/bin/aapt2" && ok "aapt2: $($PREFIX/bin/aapt2 version 2>&1 | head -1)" || miss "aapt2"
  have "$PREFIX/bin/zipalign" && ok "zipalign: $PREFIX/bin/zipalign" || miss "zipalign"
}

# ------------------------------------------------------------- 2. debug keystore
step_keystore() {
  say "[2/6] debug 签名 keystore"
  mkdir -p "$HOME/.android"
  if [ -n "$KEYSTORE_SRC" ]; then
    [ -f "$KEYSTORE_SRC" ] || { miss "--keystore 给的路径不存在: $KEYSTORE_SRC"; return 1; }
    [ -f "$HOME/.android/debug.keystore" ] && cp -f "$HOME/.android/debug.keystore" "$HOME/.android/debug.keystore.bak-$(date +%Y%m%dT%H%M%S)"
    cp -f "$KEYSTORE_SRC" "$HOME/.android/debug.keystore"
    chmod 600 "$HOME/.android/debug.keystore"
    ok "已装入 $KEYSTORE_SRC"
  fi
  if [ -f "$HOME/.android/debug.keystore" ]; then
    ok "~/.android/debug.keystore 存在"
    if have "$HOME/rish"; then
      local inst fp
      inst="$(sh "$HOME/rish" -c "dumpsys package id.harness.pipocket" 2>/dev/null | grep -ioE 'Fingerprint:[0-9A-F]+' | head -1 | cut -d: -f2)"
      fp="$(keytool -list -v -keystore "$HOME/.android/debug.keystore" -storepass android -alias androiddebugkey 2>/dev/null | grep -i 'SHA256:' | head -1 | tr -d ' ' | tr 'a-f' 'A-F' | cut -d: -f2-)"
      if [ -n "$inst" ] && [ -n "$fp" ]; then
        if [ "$inst" = "$fp" ]; then ok "与已装 app 的签名一致（可以 pm install -r 覆盖装）"
        else warn "与已装 app 的签名**不一致** ⇒ 覆盖装会 INSTALL_FAILED_UPDATE_INCOMPATIBLE；用 --keystore 传装 app 那台的 debug.keystore"; fi
      fi
    fi
  else
    warn "没有 ~/.android/debug.keystore；首次构建会自动生成一个**新**签名（之后覆盖装旧 app 会签名冲突）"
    warn "从装过 app 的机器拷一份过来最省事：  scp <pc>:~/.android/debug.keystore ~/.android/debug.keystore"
  fi
}

# -------------------------------------------------------------------- 3. SDK
step_sdk() {
  say "[3/6] Android SDK（cmdline-tools + platform + build-tools）"
  setup_env || { miss "没有 JDK，sdkmanager 跑不起来（先 pkg install openjdk-21）"; return 1; }
  if [ ! -f "$PP_SDK/cmdline-tools/latest/bin/sdkmanager" ]; then
    mkdir -p "$PP_SDK/cmdline-tools" "$WORK"
    fetch "https://dl.google.com/android/repository/commandlinetools-linux-${CMDLINE_TOOLS_BUILD}_latest.zip" \
          "$WORK/cmdline-tools.zip" "$CMDLINE_TOOLS_SHA256" || { miss "cmdline-tools 下载失败"; return 1; }
    rm -rf "$WORK/ct-tmp"; mkdir -p "$WORK/ct-tmp"
    unzip -q -o "$WORK/cmdline-tools.zip" -d "$WORK/ct-tmp" || { miss "cmdline-tools 解包失败"; return 1; }
    rm -rf "$PP_SDK/cmdline-tools/latest"
    mv "$WORK/ct-tmp/cmdline-tools" "$PP_SDK/cmdline-tools/latest"
    rm -rf "$WORK/ct-tmp"
  fi
  # unzip 不保留可执行位 ⇒ 必须补 chmod，否则 sdkmanager 静默跑不起来
  chmod +x "$PP_SDK/cmdline-tools/latest/bin/"* 2>/dev/null || true
  # 注意：`sdkmanager --version` 的 stdout 是 “22.0\n\n”（末尾多一个空行）⇒ 不能用 tail -1
  local smv
  smv="$("$PP_SDK/cmdline-tools/latest/bin/sdkmanager" --version 2>/dev/null | head -1)"
  if [ -n "$smv" ]; then ok "sdkmanager: $smv"; else miss "sdkmanager 跑不起来（JAVA_HOME/网络）"; return 1; fi
  if [ ! -f "$PP_SDK/platforms/$PP_PLATFORM/android.jar" ] || [ ! -f "$BT/core-lambda-stubs.jar" ]; then
    say "  sdkmanager --install platforms;$PP_PLATFORM build-tools;$PP_BT"
    yes | "$PP_SDK/cmdline-tools/latest/bin/sdkmanager" --sdk_root="$PP_SDK" \
      --install "platforms;$PP_PLATFORM" "build-tools;$PP_BT" >/dev/null 2>&1 \
      || { miss "sdkmanager 安装 platform/build-tools 失败（网络或许可证）"; return 1; }
  fi
  [ -f "$PP_SDK/platforms/$PP_PLATFORM/android.jar" ] && ok "platform $PP_PLATFORM/android.jar" || miss "platforms/$PP_PLATFORM/android.jar"
  [ -f "$BT/core-lambda-stubs.jar" ] && ok "build-tools/$PP_BT/core-lambda-stubs.jar" || miss "core-lambda-stubs.jar"

  # 原生件：Google 给的是 x86_64，aarch64 上必须换掉
  mkdir -p "$BT"
  local n
  for n in aapt aapt2 aidl zipalign; do
    [ -x "$PREFIX/bin/$n" ] && ln -sf "$PREFIX/bin/$n" "$BT/$n"
  done
  if [ ! -x "$BT/dexdump" ] || [ ! -x "$BT/split-select" ]; then
    mkdir -p "$WORK"
    fetch "$AARCH64_TOOLS_URL" "$WORK/sdk-tools-aarch64.zip" "$AARCH64_TOOLS_SHA256" || { miss "aarch64 静态工具包下载失败（GitHub 很慢，~100 KB/s）"; return 1; }
    rm -rf "$WORK/sdk-tools-aarch64"; mkdir -p "$WORK/sdk-tools-aarch64"
    unzip -q -o "$WORK/sdk-tools-aarch64.zip" -d "$WORK/sdk-tools-aarch64" || { miss "静态工具包解包失败"; return 1; }
    for n in dexdump split-select; do
      cp -f "$WORK/sdk-tools-aarch64/build-tools/$n" "$BT/$n" && chmod 755 "$BT/$n"
    done
  fi
  local bad=0
  for n in aapt aapt2 aidl zipalign dexdump split-select; do
    if [ -x "$BT/$n" ]; then ok "build-tools/$PP_BT/$n"; else miss "build-tools/$PP_BT/$n（AGP 会报 build-tools corrupted）"; bad=1; fi
  done
  [ "$bad" -eq 0 ] || return 1
}

# --------------------------------------------------------- 4. Gradle 分发 + 镜像
gradle_dist_dir() { # 与 Gradle PathAssembler 同算法：base36(md5(distributionUrl))
  local url="$1" hex
  hex="$(printf '%s' "$url" | md5sum | cut -d' ' -f1)"
  have "$PREFIX/bin/python3" || { echo ""; return 0; }
  python3 -c "
import sys
n = int(sys.argv[1], 16)
digits = '0123456789abcdefghijklmnopqrstuvwxyz'
out = ''
while n:
    out = digits[n % 36] + out
    n //= 36
print(out)" "$hex"
}

step_gradle() {
  say "[4/6] Gradle 分发包（走腾讯镜像，校验官方 sha256）"
  local props="$ROOT/gradle/wrapper/gradle-wrapper.properties"
  [ -f "$props" ] || { miss "找不到 $props"; return 1; }
  local url; url="$(sed -n 's/^distributionUrl=//p' "$props" | sed 's/\\//g')"
  local zip="${url##*/}"   # gradle-9.8.1-bin.zip
  local base="${zip%.zip}"
  local dir; dir="$(gradle_dist_dir "$url")"
  if [ -z "$dir" ]; then
    warn "没有 python3，无法算出 wrapper 缓存目录名；直接跑 gradlew 会走官方源慢慢下（150 KB/s）"
    return 0
  fi
  local dist="$HOME/.gradle/wrapper/dists/$base/$dir"
  mkdir -p "$dist"
  if [ ! -f "$dist/$zip.ok" ] && [ ! -s "$dist/$zip" ]; then
    fetch "$GRADLE_DIST_MIRROR/$zip" "$dist/$zip" "$GRADLE_DIST_SHA256" || { miss "Gradle 分发包下载失败"; return 1; }
  fi
  # Gradle 自己解包到 $dist/gradle-9.8.1 并写 $zip.ok；用 .ok 当“就绪”标记最稳
  if [ -f "$dist/$zip.ok" ]; then ok "gradle $base 已就绪（$dist）"
  else ok "gradle $base 分发包已放好，首次 gradlew 会自动解包（$dist）"; fi
}

step_mirror() {
  say "[5/6] Maven 镜像（~/.gradle/init.gradle，只在本机生效、只前置不改项目源）"
  local f="$HOME/.gradle/init.gradle"
  mkdir -p "$HOME/.gradle"
  if [ "$NO_MIRROR" = 1 ]; then warn "--no-mirror：跳过"; return 0; fi
  cat > "$f" <<'EOF'
// pi-pocket 手机侧 Maven 镜像（由 termux/mobile-build.sh 写入；只前置镜像，不删项目里的源）
// 实测（phone，2026-10-20）：repo1.maven.org ≈150 KB/s；华为云 ≈30 MB/s（google+central 都代理）；
// Aliyun central ≈7 MB/s；dl.google.com ≈23 MB/s（官方源本来就快，保留在项目 settings 里）。
beforeSettings { settings ->
  def mirrors = [
    'https://repo.huaweicloud.com/repository/maven',
    'https://maven.aliyun.com/repository/central',
    'https://maven.aliyun.com/repository/gradle-plugin',
  ]
  settings.pluginManagement.repositories {
    mirrors.each { u -> maven { url = u } }
  }
  settings.dependencyResolutionManagement.repositories {
    mirrors.each { u -> maven { url = u } }
  }
}
EOF
  ok "写入 $f"
  [ -f "$f" ] && grep -q 'huaweicloud' "$f" && ok "镜像行校验通过"
}

step_props() {
  say "[6/6] 仓库本地文件"
  local lp="$ROOT/local.properties"
  if [ ! -f "$lp" ] || ! grep -q "^sdk.dir=$PP_SDK\$" "$lp"; then
    printf 'sdk.dir=%s\n' "$PP_SDK" > "$lp"
    ok "local.properties → sdk.dir=$PP_SDK"
  else
    ok "local.properties: $(cat "$lp")"
  fi
}

# -------------------------------------------------------------------- 构建
step_build() {
  local jh; jh="$(jdk_home)" || { echo "没有 JDK（先 --setup）" >&2; exit 1; }
  export JAVA_HOME="$jh"
  export ANDROID_HOME="$PP_SDK" ANDROID_SDK_ROOT="$PP_SDK"
  export PATH="$jh/bin:$PATH"
  cd "$ROOT" || exit 1
  mkdir -p "$WORK"
  local log="$WORK/build-last.log"
  say "构建：${TASKS[*]}   （ANDROID_HOME=$PP_SDK  JAVA_HOME=$jh）"
  local rc=0
  ( bash gradlew "${TASKS[@]}" --console=plain \
      "-Pandroid.aapt2FromMavenOverride=$BT/aapt2" ) >"$log" 2>&1 || rc=$?
  tail -12 "$log"
  if [ "$rc" -ne 0 ]; then
    echo "构建失败（完整日志：$log）—— 常见原因："
    echo "  * build-tools 原生件缺失 → 再跑 --check"
    echo "  * 换机后首次 ⇒ 依赖要下（镜像已在 init.gradle）；repo1.maven.org 慢是常态"
    exit "$rc"
  fi
  ok "构建成功（日志 $log）"
}

step_install() {
  local apk="$ROOT/app/build/outputs/apk/debug/app-debug.apk"
  [ -f "$apk" ] || { echo "没有 APK：$apk" >&2; exit 1; }
  local stage=/sdcard/Download/pi-pocket-update
  mkdir -p "$stage" || { echo "写不了 $stage（Termux 存储权限？）" >&2; exit 1; }
  cp -f "$apk" "$stage/app-debug.apk"
  say "装机：$(stat -c%s "$apk") 字节  sha256=$(sha256_of "$apk" | cut -c1-16)…"
  if [ -f "$HOME/rish" ]; then   # Shizuku：没有 adb 也能装（注意 rish 文件本身没有可执行位，用 sh 跑）
    ( cd "$HOME" && sh ./rish -c "cp $stage/app-debug.apk /data/local/tmp/pp.apk && pm install -r /data/local/tmp/pp.apk; rm -f /data/local/tmp/pp.apk" ) 2>&1 | tail -2
    ( cd "$HOME" && sh ./rish -c "am force-stop id.harness.pipocket" ) >/dev/null 2>&1
    ( cd "$HOME" && sh ./rish -c "dumpsys package id.harness.pipocket | grep -E 'versionName|lastUpdateTime'" ) 2>&1 | sed 's/^/  /'
  elif have "$PREFIX/bin/adb"; then
    adb install -r "$apk"
  else
    warn "没有 ~/rish（Shizuku），也没有 adb ⇒ APK 放在 $stage/app-debug.apk，请自行安装"
  fi
}

check_all() {
  say "== pi-pocket 手机原生构建环境体检 =="
  say "SDK=$PP_SDK  build-tools=$PP_BT  platform=$PP_PLATFORM  JDK=$(jdk_home || echo '无')"
  step_keystore
  say "[包]"
  have "$PREFIX/bin/java"     && ok "java"     || miss "java（pkg install openjdk-21）"
  have "$PREFIX/bin/aapt2"    && ok "aapt2"    || miss "aapt2（pkg install aapt2）"
  have "$PREFIX/bin/aidl"     && ok "aidl"     || miss "aidl（pkg install aidl）"
  have "$PREFIX/bin/zipalign" && ok "zipalign" || miss "zipalign（pkg install aapt）"
  say "[SDK]"
  [ -f "$PP_SDK/platforms/$PP_PLATFORM/android.jar" ] && ok "platforms/$PP_PLATFORM/android.jar" || miss "platforms/$PP_PLATFORM/android.jar"
  [ -f "$BT/core-lambda-stubs.jar" ] && ok "core-lambda-stubs.jar" || miss "core-lambda-stubs.jar"
  local n
  for n in aapt aapt2 aidl zipalign dexdump split-select; do
    [ -x "$BT/$n" ] && ok "build-tools/$PP_BT/$n" || miss "build-tools/$PP_BT/$n"
  done
  say "[Gradle]"
  local url zip dir base dist
  url="$(sed -n 's/^distributionUrl=//p' "$ROOT/gradle/wrapper/gradle-wrapper.properties" | sed 's/\\//g')"
  zip="${url##*/}"; base="${zip%.zip}"
  dir="$(gradle_dist_dir "$url")"
  dist="$HOME/.gradle/wrapper/dists/$base/$dir"
  if [ -n "$dir" ] && { [ -f "$dist/$zip.ok" ] || [ -s "$dist/$zip" ]; }; then
    ok "gradle $base 分发包就绪"
  else
    miss "gradle $base 分发包（--setup 会从腾讯镜像拉，~150MB）"
  fi
  [ -f "$HOME/.gradle/init.gradle" ] && ok "~/.gradle/init.gradle 镜像" || warn "没有 ~/.gradle/init.gradle（依赖会走 repo1 很慢；--setup 可写）"
  say ""
  if [ "$FAILED" -eq 0 ]; then say "== 体检通过：可以 bash termux/mobile-build.sh --install =="; else say "== 有缺失：先 bash termux/mobile-build.sh --setup =="; fi
  return "$FAILED"
}

need_termux
case "$MODE" in
  setup) rc=0
         step_pkgs || rc=1
         step_keystore || rc=1
         step_sdk || rc=1
         step_gradle || rc=1
         step_mirror || rc=1
         step_props || rc=1
         say ""
         if [ "$rc" -eq 0 ]; then
           say "setup 完成。下一步：bash termux/mobile-build.sh --install"
         else
           say "setup 有失败项（见上）。修好后重跑 --setup（幂等，已装的不重装）"
           exit 1
         fi ;;
  check) check_all ;;
  build) step_props; step_build; [ "$DO_INSTALL" = 1 ] && step_install ;;
esac
