#!/usr/bin/env bash
# setup-android-sdk.sh —— 下载 Android SDK cmdline-tools 与 NDK。
#
# 装到 D:\Android\Sdk（C 盘只剩 25G，D 盘有 108G）。
# 幂等：已存在的组件不会重复下载。

set -euo pipefail

SDK_ROOT="${SDK_ROOT:-/d/Android/Sdk}"
CMDLINE_ZIP_URL="https://dl.google.com/android/repository/commandlinetools-win-11076708_latest.zip"
NDK_VERSION="27.1.12297006"
BUILD_TOOLS="35.0.0"
PLATFORM="android-35"

log() { printf '\033[1;34m[sdk]\033[0m %s\n' "$*"; }
die() { printf '\033[1;31m[sdk] FATAL:\033[0m %s\n' "$*" >&2; exit 1; }

command -v unzip >/dev/null || die "需要 unzip"
command -v curl  >/dev/null || die "需要 curl"

mkdir -p "$SDK_ROOT/cmdline-tools"

# ── 1. cmdline-tools ──
if [[ -x "$SDK_ROOT/cmdline-tools/latest/bin/sdkmanager.bat" ]] \
   || [[ -x "$SDK_ROOT/cmdline-tools/latest/bin/sdkmanager" ]]; then
  log "cmdline-tools 已存在，跳过下载"
else
  log "下载 cmdline-tools…"
  TMP_ZIP="$(mktemp -d)/cmdline-tools.zip"
  curl -fL --retry 3 --connect-timeout 30 -o "$TMP_ZIP" "$CMDLINE_ZIP_URL" \
    || die "下载 cmdline-tools 失败"
  log "解压…"
  unzip -q "$TMP_ZIP" -d "$SDK_ROOT/cmdline-tools"
  # 官方 zip 解出来是 cmdline-tools/cmdline-tools，要重命名为 latest
  if [[ -d "$SDK_ROOT/cmdline-tools/cmdline-tools" ]]; then
    mv "$SDK_ROOT/cmdline-tools/cmdline-tools" "$SDK_ROOT/cmdline-tools/latest"
  fi
  rm -rf "$(dirname "$TMP_ZIP")"
  log "cmdline-tools 就绪"
fi

# ── 2. sdkmanager 路径（Windows 上要用 .bat）──
SDKMANAGER="$SDK_ROOT/cmdline-tools/latest/bin/sdkmanager.bat"
[[ -f "$SDKMANAGER" ]] || SDKMANAGER="$SDK_ROOT/cmdline-tools/latest/bin/sdkmanager"
[[ -f "$SDKMANAGER" ]] || die "找不到 sdkmanager：$SDKMANAGER"

export ANDROID_SDK_ROOT="$SDK_ROOT"
export ANDROID_HOME="$SDK_ROOT"

log "sdkmanager: $SDKMANAGER"

# ── 3. 接受许可 ──
log "接受 SDK 许可协议…"
yes 2>/dev/null | "$SDKMANAGER" --sdk_root="$SDK_ROOT" --licenses >/dev/null 2>&1 || true

# ── 4. 安装组件 ──
log "安装 platform-tools / platform $PLATFORM / build-tools $BUILD_TOOLS / NDK $NDK_VERSION"
"$SDKMANAGER" --sdk_root="$SDK_ROOT" \
  "platform-tools" \
  "platforms;$PLATFORM" \
  "build-tools;$BUILD_TOOLS" \
  "ndk;$NDK_VERSION" 2>&1 | grep -vE '^\s*$' | tail -20

log "完成。组件列表："
ls "$SDK_ROOT" || true
echo "--- NDK ---"
ls "$SDK_ROOT/ndk" 2>/dev/null || echo "（NDK 未安装成功）"
