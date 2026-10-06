#!/usr/bin/env bash
# ─────────────────────────────────────────────────────────────────
# install-and-run.sh —— 真机安装与启动（Demo 验证用）
#
# 用途：手机插 USB + 开 USB 调试后，一条命令完成：
#   ① 检查设备连接  ② 检查 libmpv.so 是否齐备
#   ③ 装 APK       ④ 启动 Demo  ⑤ 拉 logcat 看验证点
#
# 用法：
#   bash scripts/install-and-run.sh
#   bash scripts/install-and-run.sh --no-build    # 跳过重新打包
#   bash scripts/install-and-run.sh --logs-only   # 只看日志
#
# 退出码：0 成功；非 0 见下方错误码
# ─────────────────────────────────────────────────────────────────

set -uo pipefail

# ── 路径与常量 ──
# 本脚本位于 <repo>/scripts/ 下，库根本体（Gradle / Cargo）就在上跳一级。
# 注：YanMusicMobile 拆分后，android/ 已不再存在，Gradle 工程即库根。
REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
ANDROID_DIR="$REPO_ROOT"
APK="$ANDROID_DIR/app/build/outputs/apk/debug/app-debug.apk"
JNILIBS_DIR="$ANDROID_DIR/app/src/main/jniLibs/arm64-v8a"
PREBUILT_DIR="$ANDROID_DIR/prebuilt/arm64-v8a"
BUILD_JNI_DIR="$ANDROID_DIR/build-jni/arm64-v8a"

PKG="com.yanmusic.engine.demo"
ACTIVITY="$PKG/.MainActivity"

DO_BUILD=1
LOGS_ONLY=0
for arg in "$@"; do
  case "$arg" in
    --no-build)   DO_BUILD=0 ;;
    --logs-only)  LOGS_ONLY=1; DO_BUILD=0 ;;
    -h|--help)    sed -n '2,15p' "$0"; exit 0 ;;
    *) echo "未知参数：$arg（-h 看用法）" >&2; exit 2 ;;
  esac
done

log()  { printf '\033[1;34m[run]\033[0m %s\n' "$*"; }
ok()   { printf '\033[1;32m  ✓\033[0m %s\n' "$*"; }
warn() { printf '\033[1;33m  !\033[0m %s\n' "$*"; }
die()  { printf '\033[1;31m[run] FATAL:\033[0m %s\n' "$*" >&2; exit 1; }

# ── 环境：定位 adb ──
if [[ -n "${ANDROID_SDK_ROOT:-}" && -x "$ANDROID_SDK_ROOT/platform-tools/adb" ]]; then
  ADB="$ANDROID_SDK_ROOT/platform-tools/adb"
elif [[ -n "${ANDROID_HOME:-}" && -x "$ANDROID_HOME/platform-tools/adb" ]]; then
  ADB="$ANDROID_HOME/platform-tools/adb"
elif command -v adb >/dev/null 2>&1; then
  ADB="$(command -v adb)"
else
  die "找不到 adb。请先：source scripts/env-android.sh"
fi
log "adb = $ADB"

# ── ① 设备连接检查 ──
log "① 检查设备连接"
DEVICES="$("$ADB" devices | awk 'NR>1 && $2=="device" {print $1}')"
UNAUTH="$("$ADB" devices | awk 'NR>1 && $2=="unauthorized" {print $1}')"
OFFLINE="$("$ADB" devices | awk 'NR>1 && $2=="offline" {print $1}')"

if [[ -n "$UNAUTH" ]]; then
  die "设备已连接但**未授权**（$UNAUTH）。请在手机上点「允许 USB 调试」。"
fi
if [[ -n "$OFFLINE" ]]; then
  die "设备处于 offline（$OFFLINE）。试：拔插数据线 / 手机上「撤销 USB 调试授权」后重连。"
fi
if [[ -z "$DEVICES" ]]; then
  die "没有已授权的设备。检查：① 数据线是否支持数据传输（非充电线）② 手机是否开了「USB 调试」③ 手机上是否弹了授权框"
fi
DEV_COUNT="$(echo "$DEVICES" | wc -l | tr -d ' ')"
log "  已连接 $DEV_COUNT 台设备：$(echo $DEVICES | tr '\n' ' ')"
if [[ "$DEV_COUNT" -gt 1 ]]; then
  warn "多台设备，后续命令会因歧义失败。建议只留一台，或自行加 -s <serial>。"
fi

# ── ② libmpv.so 齐备性检查（真机加载的前提）──
log "② 检查 native 库齐备性"
MPV_FOUND=0
for d in "$JNILIBS_DIR" "$PREBUILT_DIR"; do
  if [[ -f "$d/libmpv.so" ]]; then
    MPV_FOUND=1
    N=$(find "$d" -name '*.so' | wc -l | tr -d ' ')
    ok "libmpv.so 在 $d（同目录共 $N 个 .so）"
    if [[ "$N" -lt 2 ]]; then
      warn "只找到 1 个 .so —— libmpv 有 DT_NEEDED 依赖（libavcodec 等），"
      warn "缺依赖会 dlopen 失败。请确认 CI 产出的 tar 包已完整解包。"
    fi
  fi
done
if [[ "$MPV_FOUND" == "0" ]]; then
  warn "未找到 libmpv.so —— App 会装上但 **播放功能不可用**（V2 会失败）。"
  warn "这是预期状态（libmpv 需 CI 构建）。若要完整体验："
  warn "  1) 触发 CI：gh workflow run build-libmpv-android.yml"
  warn "  2) 下载 artifact 解包到 <repo>/prebuilt/arm64-v8a/"
  warn "  3) 重跑本脚本"
fi
if [[ -f "$BUILD_JNI_DIR/libyan_engine_jni.so" ]]; then
  ok "libyan_engine_jni.so 已就位（$BUILD_JNI_DIR）"
else
  die "缺少 libyan_engine_jni.so —— 请先跑：
       source scripts/env-android.sh
       cd android && cargo build -p yan-engine-jni --target aarch64-linux-android --release"
fi

# ── ③ 打包 ──
if [[ "$DO_BUILD" == "1" ]]; then
  log "③ 重新打包 debug APK"
  ( cd "$ANDROID_DIR" && ./gradlew :app:assembleDebug --console=plain -q ) \
    || die "assembleDebug 失败。单独跑看日志：cd android && ./gradlew :app:assembleDebug"
  ok "打包完成"
fi

if [[ "$LOGS_ONLY" == "0" ]]; then
  [[ -f "$APK" ]] || die "APK 不存在：$APK"
  log "  APK = $APK ($(stat -c %s "$APK" 2>/dev/null || echo '?') 字节)"

  # ── ④ 安装 ──
  log "④ 安装到设备"
  if ! "$ADB" install -r -d "$APK" 2>&1 | tail -3; then
    die "安装失败。常见原因：① 设备上已有不同签名的同名包 → 先 adb uninstall $PKG  ② 存储空间不足"
  fi
  ok "安装成功"

  # ── ⑤ 启动 ──
  log "⑤ 启动 Demo"
  "$ADB" shell am force-stop "$PKG" >/dev/null 2>&1 || true
  "$ADB" shell am start -n "$ACTIVITY" 2>&1 | tail -3
  ok "已发送启动指令"
fi

# ── ⑥ 日志 ──
log "⑥ 拉取验证相关日志（10 秒）"
echo "  ── 关键验证点 ──"
echo "  V2  System.loadLibrary   → 应无 UnsatisfiedLinkError"
echo "  V4  mpv_initialize       → 应见 engine ready"
echo "  V6  samplerate           → 期望 96000（高解析未被降采样）"
echo "  V8  ffmpeg 解码错误      → 应无 error"
echo ""
"$ADB" logcat -c 2>/dev/null || true
"$ADB" shell am force-stop "$PKG" >/dev/null 2>&1 || true
"$ADB" shell am start -n "$ACTIVITY" >/dev/null 2>&1
timeout 10 "$ADB" logcat 2>/dev/null \
  | grep -iE "yanmusic|yan-engine|mpv|libmpv|UnsatisfiedLink|samplerate|aaudio|ffmpeg|AndroidRuntime" \
  | head -80 \
  || warn "10 秒内未捕获到相关日志"

echo ""
ok "完成。若上面无输出，可在手机上手动看 App 界面（Demo 自带四个验证区）。"
echo "    持续看日志：$ADB logcat | grep -iE 'yanmusic|mpv|samplerate'"
