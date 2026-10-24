#!/usr/bin/env bash
# pipocket-qa 的「调试速度」配置（用户 2026-10-22：模拟器太卡）
#
# 关键点：
#  · 6 核（模拟器上限）/ 6GB / 宿主硬件 GPU（host，走 RTX 4060）
#  · 分辨率 1080x2400@420 ⇒ 720x1600@280：**dp 尺寸完全不变**（411.4 x 914.3dp），
#    但像素只有 44% ⇒ 每帧填充量减半，滚动/动画明显跟手。
#  · skin.name 必须一起改：它优先于 hw.lcd.*（否则 guest 仍按 1080x2400 起）。
#  · 要出高清文档截图：把下面 HIGH_RES=1 跑一次，或手工改回 1080x2400/420。
set -eu
CFG="$HOME/.android/avd/pipocket-qa.avd/config.ini"
[ -f "$CFG" ] || { echo "找不到 $CFG"; exit 1; }
cp "$CFG" "$CFG.bak-$(date +%Y%m%d-%H%M%S)"

if [ "${HIGH_RES:-0}" = "1" ]; then
  W=1080; H=2400; D=420
else
  W=720; H=1600; D=280
fi

sed -i -E \
  -e 's/^hw\.cpu\.ncore[[:space:]]*=.*/hw.cpu.ncore = 6/' \
  -e 's/^hw\.ramSize[[:space:]]*=.*/hw.ramSize = 6144/' \
  -e 's/^hw\.gpu\.mode[[:space:]]*=.*/hw.gpu.mode = host/' \
  -e 's/^vm\.heapSize[[:space:]]*=.*/vm.heapSize = 512/' \
  -e "s/^hw\\.lcd\\.width[[:space:]]*=.*/hw.lcd.width = $W/" \
  -e "s/^hw\\.lcd\\.height[[:space:]]*=.*/hw.lcd.height = $H/" \
  -e "s/^hw\\.lcd\\.density[[:space:]]*=.*/hw.lcd.density = $D/" \
  -e "s/^skin\\.name[[:space:]]*=.*/skin.name = ${W}x${H}/" \
  "$CFG"

echo "改完（dp 尺寸 $((W * 160 / D)) x $((H * 160 / D))）："
grep -E '^(hw\.cpu\.ncore|hw\.ramSize|hw\.gpu\.mode|vm\.heapSize|hw\.lcd\.|skin\.name)' "$CFG"
