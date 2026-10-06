#!/usr/bin/env bash
# collect-libs.sh —— 收集 libmpv.so 及其全部 DT_NEEDED 依赖。
#
# 运行位置：仓库根（由 CI 以绝对路径调用）
# 前提：buildall.sh 已成功完成
#
# 为什么必须收集依赖：
#   libmpv.so 依赖 libavcodec.so / libavformat.so / libavutil.so /
#   libswresample.so / libass.so / ... 这些在 mpv-android 的 prefix/
#   目录里。只把 libmpv.so 拷进 jniLibs 会导致运行时
#   `dlopen failed: library "libavcodec.so" not found`。
#
# 输出：out/libmpv-android-<arch>-<variant>.tar.gz + out/MANIFEST.txt

set -euo pipefail

ARCH="${ARCH:-arm64}"
AUDIO_ONLY="${AUDIO_ONLY:-1}"
WORKDIR="${WORKDIR:-$PWD/android-build}"

BUILDSCRIPTS="$WORKDIR/mpv-android/buildscripts"
OUT_DIR="$PWD/out"
STAGE="$OUT_DIR/stage"

log()  { printf '\033[1;34m[collect]\033[0m %s\n' "$*"; }
die()  { printf '\033[1;31m[collect] FATAL:\033[0m %s\n' "$*" >&2; exit 1; }

# ── 架构 → 目录名映射（与 buildscripts 的 prefix_name 一致）
case "$ARCH" in
  arm64)   ABI="arm64-v8a"   TRIPLE="aarch64-linux-android" ;;
  armv7l)  ABI="armeabi-v7a" TRIPLE="arm-linux-androideabi" ;;
  x86_64)  ABI="x86_64"      TRIPLE="x86_64-linux-android" ;;
  *)       die "未知架构：$ARCH" ;;
esac
log "目标：ARCH=$ARCH ABI=$ABI TRIPLE=$TRIPLE"

# prefix 目录可能是 prefix/<prefix_name> 或 prefix/ 直接
PREFIX_CANDIDATES=(
  "$BUILDSCRIPTS/prefix/$ARCH"
  "$BUILDSCRIPTS/prefix/${ABI}"
  "$BUILDSCRIPTS/prefix"
)
PREFIX=""
for p in "${PREFIX_CANDIDATES[@]}"; do
  if [[ -f "$p/lib/libmpv.so" ]]; then PREFIX="$p"; break; fi
done
[[ -n "$PREFIX" ]] || {
  echo "--- 搜索候选 ---"
  for p in "${PREFIX_CANDIDATES[@]}"; do echo "$p : $(ls "$p/lib" 2>/dev/null | head -5)"; done
  die "找不到 libmpv.so（已查：${PREFIX_CANDIDATES[*]}）"
}
log "找到 prefix：$PREFIX"

LIBDIR="$PREFIX/lib"
[[ -d "$LIBDIR" ]] || die "$LIBDIR 不是目录"

rm -rf "$STAGE"
mkdir -p "$STAGE/$ABI"

# ── 递归解析 DT_NEEDED，收集依赖
#    用 llvm-readelf（NDK 自带）而非 readelf，保证与目标架构一致
READELF="${NDK_DIR:-}/toolchains/llvm/prebuilt/linux-x86_64/bin/llvm-readelf"
[[ -x "$READELF" ]] || READELF="$(command -v llvm-readelf || command -v readelf)"
[[ -n "$READELF" ]] || die "找不到 readelf/llvm-readelf"
log "使用 readelf：$READELF"

declare -A SEEN
QUEUE=("$LIBDIR/libmpv.so")

while [[ ${#QUEUE[@]} -gt 0 ]]; do
  lib="${QUEUE[0]}"
  QUEUE=("${QUEUE[@]:1}")
  base="$(basename "$lib")"

  [[ -n "${SEEN[$base]:-}" ]] && continue
  SEEN["$base"]=1

  # 复制（保留符号链接的真实文件）
  if [[ ! -e "$lib" ]]; then
    # 可能是符号链接指向同目录版本化文件名
    if [[ -L "$LIBDIR/$base" ]]; then
      real="$(readlink -f "$LIBDIR/$base")"
      [[ -f "$real" ]] && lib="$real"
    fi
  fi
  [[ -f "$lib" ]] || { log "  ⚠ 跳过不存在的 $base"; continue; }
  cp -L "$lib" "$STAGE/$ABI/$base" 2>/dev/null || cp "$lib" "$STAGE/$ABI/$base"

  # 解析 DT_NEEDED
  while IFS= read -r needed; do
    [[ -z "$needed" ]] && continue
    # 系统库（libc/libm/libdl/liblog/libandroid/...）由 OS 提供，不要打包
    case "$needed" in
      libc.so|libm.so|libdl.so|liblog.so|libandroid.so|libz.so|libstdc++.so|libc++_shared.so|libOpenSLES.so|libEGL.so|libGLESv2.so|libjnigraphics.so) continue ;;
    esac
    [[ -n "${SEEN[$needed]:-}" ]] && continue
    candidate="$LIBDIR/$needed"
    if [[ -f "$candidate" || -L "$candidate" ]]; then
      QUEUE+=("$candidate")
    fi
  done < <("$READELF" -d "$lib" 2>/dev/null | sed -n 's/.*NEEDED.*\[\(.*\)\].*/\1/p')
done

COUNT=$(find "$STAGE/$ABI" -maxdepth 1 -type f | wc -l)
log "共收集 $COUNT 个 .so 文件"
[[ "$COUNT" -gt 0 ]] || die "收集到 0 个库"

# 断言 libmpv.so 在
[[ -f "$STAGE/$ABI/libmpv.so" ]] || die "stage 中缺少 libmpv.so"

# ─────────────────────────────────────────────────────────────────
# ★ 收集完整性断言（防「静默漏收依赖」）
#
# 为什么必须做：
#   上面的 DT_NEEDED 递归只从 $LIBDIR 里找候选。若某个依赖被
#   安放在别的目录（lib64 / 另一个 prefix），递归会**静默跳过**，
#   打包出的 tar 看似正常，装到设备上才 `dlopen failed: xxx not found`。
#   这正是「构建退出码 0 不等于能跑」的典型形态。
#
# 做法：把 stage 里的每个 .so 再扫一遍 DT_NEEDED，
#       凡是不属于「系统库白名单」的，都必须已存在于 stage 中。
# ─────────────────────────────────────────────────────────────────
SYS_WHITELIST='^(libc|libm|libdl|liblog|libandroid|libz|libstdc\+\+|libc\+\+_shared|libOpenSLES|libEGL|libGLESv2|libjnigraphics|libnativewindow|libsync|libcutils|libutils|libbase|libhardware|libhidlbase|libbinder|libui|libgui|ld-android|librt|libpthread)\.so$'

log "校验依赖闭包完整性…"
MISSING=()
while IFS= read -r so; do
  [[ -z "$so" ]] && continue
  base="$(basename "$so")"
  while IFS= read -r need; do
    [[ -z "$need" ]] && continue
    [[ "$need" =~ $SYS_WHITELIST ]] && continue
    [[ -f "$STAGE/$ABI/$need" ]] && continue
    MISSING+=("$base -> $need")
  done < <("$READELF" -d "$so" 2>/dev/null | sed -n 's/.*NEEDED.*\[\(.*\)\].*/\1/p')
done < <(find "$STAGE/$ABI" -maxdepth 1 -name '*.so' -type f)

if (( ${#MISSING[@]} > 0 )); then
  echo "--- 缺失的依赖 ---" >&2
  printf '  %s\n' "${MISSING[@]}" >&2
  die "依赖闭包不完整（共 ${#MISSING[@]} 项缺失）—— 装到设备会 dlopen 失败，拒绝产出"
fi
log "  ✓ 依赖闭包完整（stage 内所有 .so 的 DT_NEEDED 均已满足或属系统库）"

# ── 打包
VARIANT=$([[ "$AUDIO_ONLY" == "1" ]] && echo "audio" || echo "full")
TARBALL="$OUT_DIR/libmpv-android-$ARCH-$VARIANT.tar.gz"
tar -czf "$TARBALL" -C "$STAGE" "$ABI"
log "已打包：$TARBALL ($(du -h "$TARBALL" | cut -f1))"

# ── MANIFEST（记录内容与体积，便于体积回归对比）
{
  echo "# libmpv Android 产物清单"
  echo ""
  echo "- 构建时间（UTC）：$(date -u +%Y-%m-%dT%H:%M:%SZ)"
  echo "- 提交：${GITHUB_SHA:-（本地构建）}"
  echo "- 架构：$ARCH（$ABI）"
  echo "- 模式：$VARIANT（AUDIO_ONLY=$AUDIO_ONLY）"
  echo "- 文件数：$COUNT"
  echo ""
  echo "## 各库体积（字节）"
  echo ""
  echo '```'
  (cd "$STAGE/$ABI" && ls -la | awk 'NR>1 {printf "%12d  %s\n", $5, $9}' | sort -rn)
  echo '```'
  echo ""
  echo "## 合计"
  echo ""
  TOTAL=$(du -sb "$STAGE/$ABI" | cut -f1)
  echo "- 未压缩合计：$TOTAL 字节（$(numfmt --to=iec "$TOTAL" 2>/dev/null || echo "$TOTAL B")）"
  echo "- tar.gz：$(stat -c%s "$TARBALL") 字节"
  echo ""
  echo "## 依赖闭包（DT_NEEDED 递归结果）"
  echo ""
  echo '```'
  (cd "$STAGE/$ABI" && ls -1 | sort)
  echo '```'
} > "$OUT_DIR/MANIFEST.txt"

log "已写出 MANIFEST：$OUT_DIR/MANIFEST.txt"
cat "$OUT_DIR/MANIFEST.txt"
