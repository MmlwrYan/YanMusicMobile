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

# 把解析结果回写给下游 CI 步骤（避免在 workflow 里再抄一份 架构→ABI/TRIPLE 映射，
# 那种「两处名单」正是本项目反复踩过的坑）。
if [[ -n "${GITHUB_ENV:-}" ]]; then
  {
    echo "STAGE_ABI=$ABI"
    echo "STAGE_TRIPLE=$TRIPLE"
    echo "STAGE_DIR=$OUT_DIR/stage/$ABI"
  } >> "$GITHUB_ENV"
fi

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

# ═════════════════════════════════════════════════════════════════
# Android 系统库清单（**单一真相**）
#
# 这些 .so 由 Android OS 提供，系统自带，**不得打进 APK**：
#   ① 打包进去没意义（设备上会用系统的那份）
#   ② 反而可能因版本不匹配引发冲突
#
# ⚠️ 本清单同时被下面**两处**消费，必须保持一份：
#      - 「递归收集 DT_NEEDED」阶段的跳过判定
#      - 「依赖闭包完整性校验」阶段的豁免判定
#    2026-10-06 的教训：这两处原本**各写了一份名单且不一致**，
#    我只修了前者，后者仍把 libmediandk.so 判为缺失 → CI 继续红。
#    两套名单必须合并为一，否则修一处漏一处。
#
# 清单来源：NDK r27
#   sysroot/usr/lib/aarch64-linux-android/30/ 下的全部系统 .so
#   （2026-10-06 实测列出，共 24 个；不是凭印象手写）
# 新增 NDK 版本时，请对照该目录重新核对。
#
# ⚠️⚠️ 2026-10-07 真机实证的修正：**`libc++_shared.so` 不属于系统库，已移除**。
#   ① Android 系统里的 C++ 运行库叫 `libc++.so`，**不叫** `libc++_shared.so`；
#      部分厂商（实测 vivo）还会把它改名隔离成 `libc++_shared_n1..n4.so`。
#      → 真机 `/system/lib64/` **根本不存在** `libc++_shared.so` 这个名字。
#   ② 它由 NDK 提供、必须随包分发。原白名单把它当系统库排除 → tar 里没有它 →
#      真机 `dlopen failed: library "libc++_shared.so" not found`。
#   ③ 它的补齐见下方「显式补齐 libc++_shared.so」段（从构建用的 NDK 取）。
#   —— 教训：白名单是「OS 一定提供」的断言，写错一条就等于把缺陷固化。
# ═════════════════════════════════════════════════════════════════
# 格式 A：供 `case` 匹配（以 | 分隔的完整库名）
SYS_LIBS_CASE='libc.so|libm.so|libdl.so|libz.so|libstdc++.so|libc++.so|liblog.so|libandroid.so|libjnigraphics.so|libnativewindow.so|libsync.so|libmediandk.so|libOpenSLES.so|libOpenMAXAL.so|libaaudio.so|libamidi.so|libcamera2ndk.so|libEGL.so|libGLESv1_CM.so|libGLESv2.so|libGLESv3.so|libvulkan.so|libbinder_ndk.so|libneuralnetworks.so'

# 格式 B：供 `[[ =~ ]]` 匹配（正则；在 A 的基础上补充链接器与平台私有库）
SYS_LIBS_REGEX='^(libc|libm|libdl|liblog|libandroid|libz|libstdc\+\+|libc\+\+|libOpenSLES|libOpenMAXAL|libaaudio|libamidi|libcamera2ndk|libmediandk|libEGL|libGLESv1_CM|libGLESv2|libGLESv3|libvulkan|libjnigraphics|libnativewindow|libsync|libbinder_ndk|libneuralnetworks|libcutils|libutils|libbase|libhardware|libhidlbase|libbinder|libui|libgui|ld-android|librt|libpthread)\.so$'

# ── 自检：两份格式必须覆盖同一组库
#    防止将来只改一处（这正是 2026-10-06 踩过的坑）
_sys_check_fail=0
while IFS= read -r _lib; do
  [[ -z "$_lib" ]] && continue
  [[ "$_lib" =~ $SYS_LIBS_REGEX ]] || { echo "[collect] 内部错误：$_lib 在 case 名单但不在 regex 名单" >&2; _sys_check_fail=1; }
done < <(printf '%s\n' "$SYS_LIBS_CASE" | tr '|' '\n')
(( _sys_check_fail == 0 )) || die "系统库名单两处不一致（见上）"
unset _sys_check_fail _lib

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
      # 同样避免 `[[ ]] &&` 在 set -e 下误杀：条件为假时用 if 包起来
      if [[ -f "$real" ]]; then lib="$real"; fi
    fi
  fi
  [[ -f "$lib" ]] || { log "  ⚠ 跳过不存在的 $base"; continue; }
  cp -L "$lib" "$STAGE/$ABI/$base" 2>/dev/null || cp "$lib" "$STAGE/$ABI/$base"

  # 解析 DT_NEEDED
  while IFS= read -r needed; do
    [[ -z "$needed" ]] && continue
    # 系统库由 OS 提供，**不要打包**。
    # 名单定义在脚本顶部 SYS_LIBS_CASE（单一真相，勿在此另写一份）。
    case "$needed" in
      $SYS_LIBS_CASE) continue ;;
    esac
    [[ -n "${SEEN[$needed]:-}" ]] && continue
    candidate="$LIBDIR/$needed"
    if [[ -f "$candidate" || -L "$candidate" ]]; then
      QUEUE+=("$candidate")
    fi
  done < <("$READELF" -d "$lib" 2>/dev/null | sed -n 's/.*NEEDED.*\[\(.*\)\].*/\1/p')
done

# ═════════════════════════════════════════════════════════════════
# ★ 显式补齐 libc++_shared.so（2026-10-07 真机实证）
#
# 为什么递归收集抓不到、必须显式补：
#   libmpv.so 的 DT_NEEDED 含 `libc++_shared.so`，但它
#     ① 不在 mpv 的 prefix/lib 里（C++ 运行库由 NDK 提供，不随 mpv 编出来）；
#     ② 也不是 Android 系统库（见上方白名单处的注记）。
#   两边都不覆盖 → 它**必然**从 tar 里消失。
#
# ⚠️⚠️ 必须用「构建 libmpv 的那个 NDK」里的 libc++，**不能随便挑一个版本**：
#   实测（穷举差集）r27 / r28 的 libc++ **都不导出**
#       std::__ndk1::__from_chars_floating_point<float|double>
#   —— 该函数在 libc++ 20+ 才从 header 移出、改为外部符号导出，
#   而 libmpv 由 **r30** 构建、正是引用了它。
#   拿错版本 → 报 `cannot locate symbol ... __from_chars_floating_point`
#   （2026-10-07 用 r27 的 libc++ 实测复现）。
#
# → 版本**由 buildscripts 的 depinfo.sh 动态解析 `v_ndk`**，绝不硬编码 r30。
#   上游升级 NDK 时本段自动跟随；解析不到即 fail-closed，绝不静默降级。
# ═════════════════════════════════════════════════════════════════
DEPINFO="$BUILDSCRIPTS/include/depinfo.sh"
_ndk_candidates=()

# ① workflow 显式传入（最可靠，见 build-libmpv job 的 Collect 步骤）
#    注意：不能写成 `[[ -n ... ]] && ...` —— 条件为假时整条返回 1，
#    在 `set -e` 下会直接终止脚本（本地不带 MPV_NDK_DIR 跑就会踩到）。
if [[ -n "${MPV_NDK_DIR:-}" ]]; then
  _ndk_candidates+=("$MPV_NDK_DIR")
fi

# ② 从 depinfo.sh 解析 v_ndk，拼出 buildscripts 自建自用的那个 NDK
if [[ -f "$DEPINFO" ]]; then
  V_NDK="$(sed -n 's/^[[:space:]]*v_ndk=\(.*\)$/\1/p' "$DEPINFO" | head -1 | tr -d ' \t\r"')"
  if [[ -n "$V_NDK" ]]; then
    log "depinfo.sh v_ndk = $V_NDK"
    _ndk_candidates+=("$BUILDSCRIPTS/sdk/android-ndk-$V_NDK")
  else
    log "  ⚠ 未能从 depinfo.sh 解析出 v_ndk"
  fi
fi

# ③ 兜底：调用方给的 NDK_DIR（可能是错版本，仅当上面都落空时用）
if [[ -n "${NDK_DIR:-}" ]]; then
  _ndk_candidates+=("$NDK_DIR")
fi

LIBCXX_SRC=""
RESOLVED_NDK=""
for _d in "${_ndk_candidates[@]}"; do
  [[ -d "$_d" ]] || continue
  for _cand in "$_d"/toolchains/llvm/prebuilt/*/sysroot/usr/lib/"$TRIPLE"/libc++_shared.so \
               "$_d"/toolchains/llvm/prebuilt/*/sysroot/usr/lib/"$TRIPLE"/*/libc++_shared.so; do
    if [[ -f "$_cand" ]]; then LIBCXX_SRC="$_cand"; RESOLVED_NDK="$_d"; break 2; fi
  done
done
unset _d _cand

if [[ -z "$LIBCXX_SRC" ]]; then
  echo "--- 已尝试的 NDK 候选 ---" >&2
  printf '  %s\n' "${_ndk_candidates[@]:-（无）}" >&2
  die "找不到 libc++_shared.so —— 需要「构建 libmpv 所用的那个 NDK」的 sysroot。
       候选来自：MPV_NDK_DIR / depinfo.sh 的 v_ndk / NDK_DIR。"
fi
log "补齐 C++ 运行库：$LIBCXX_SRC（$(stat -c%s "$LIBCXX_SRC") 字节）"
cp -L "$LIBCXX_SRC" "$STAGE/$ABI/libc++_shared.so"
unset LIBCXX_SRC DEPINFO V_NDK

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
#
# ⚠️ 白名单用顶部的 SYS_LIBS_REGEX（与上面的 SYS_LIBS_CASE 同源）。
#    2026-10-06 的教训：这里原本**另写了一份正则**，与上面那份不一致，
#    导致修了一处、另一处仍报错。
# ─────────────────────────────────────────────────────────────────
log "校验依赖闭包完整性…"
MISSING=()
while IFS= read -r so; do
  [[ -z "$so" ]] && continue
  base="$(basename "$so")"
  while IFS= read -r need; do
    [[ -z "$need" ]] && continue
    [[ "$need" =~ $SYS_LIBS_REGEX ]] && continue
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

# ─────────────────────────────────────────────────────────────────
# ★★ 符号级依赖闭包校验（库级断言的补强）
#
# 上面那条只证明「依赖库都在 stage 里」，**证明不了**「库里的符号够用」。
# 2026-10-07 真机实证反例：库级断言报 ✓，但 tar 里少了整个 C++ 运行库，
# 真机 `dlopen failed: library "libc++_shared.so" not found`。
#
# 这里再下沉一层：stage 内每个 .so 的每个**强未定义**符号都必须有提供者。
# 实现见同目录 check-symbol-closure.sh（Tier 2 守卫，可在 CI 跑、无需真机）。
# ─────────────────────────────────────────────────────────────────
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
if [[ -f "$SCRIPT_DIR/check-symbol-closure.sh" ]]; then
  bash "$SCRIPT_DIR/check-symbol-closure.sh" "$STAGE/$ABI" "$RESOLVED_NDK" "$TRIPLE" \
    || die "符号级依赖闭包校验未通过（见上）—— 拒绝产出"
else
  die "缺少 $SCRIPT_DIR/check-symbol-closure.sh —— 符号级守卫不可用，拒绝产出"
fi
unset SCRIPT_DIR RESOLVED_NDK _ndk_candidates 2>/dev/null || true

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
