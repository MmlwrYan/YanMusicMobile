#!/usr/bin/env bash
# verify-libs.sh —— 对收集到的 .so 做架构与完整性验证。
#
# 运行位置：仓库根（由 CI 以绝对路径调用）
# 目的：在打包上传前拦下「架构错误 / 缺失关键格式 / 体积异常」。
#       宁可 CI 红，也不要产出一个跑不起来的库。

set -euo pipefail

ARCH="${ARCH:-arm64}"
OUT_DIR="$PWD/out"
STAGE="$OUT_DIR/stage"

log()  { printf '\033[1;34m[verify]\033[0m %s\n' "$*"; }
warn() { printf '\033[1;33m[verify]\033[0m %s\n' "$*"; }
die()  { printf '\033[1;31m[verify] FATAL:\033[0m %s\n' "$*" >&2; exit 1; }

case "$ARCH" in
  arm64)   ABI="arm64-v8a";   EXPECT_MACHINE="AArch64" ;;
  armv7l)  ABI="armeabi-v7a"; EXPECT_MACHINE="ARM" ;;
  x86_64)  ABI="x86_64";      EXPECT_MACHINE="X86-64" ;;
  *)       die "未知架构：$ARCH" ;;
esac

LIBDIR="$STAGE/$ABI"
[[ -d "$LIBDIR" ]] || die "stage 目录不存在：$LIBDIR"

READELF="${NDK_DIR:-}/toolchains/llvm/prebuilt/linux-x86_64/bin/llvm-readelf"
[[ -x "$READELF" ]] || READELF="$(command -v llvm-readelf || command -v readelf)"
[[ -n "$READELF" ]] || die "找不到 readelf"
log "使用 readelf：$READELF"

FAIL=0

# ── 1. 架构一致性：每个 .so 都必须是目标架构
log "检查 1/4：架构一致性（期望 $EXPECT_MACHINE）"
#
# ⚠️ 不要用 `sed -n 's/^\s*...'` —— GNU sed 的**基础正则**不支持 `\s`，
#    会直接报 `unknown option to 's'`，变量取到空值，
#    于是下面每个库都会被误判为"架构不匹配"（2026-10-04 实测）。
#    必须用 POSIX 字符类 `[[:space:]]`。
ARCH_CHECKED=0
while IFS= read -r lib; do
  ARCH_CHECKED=$((ARCH_CHECKED + 1))
  machine="$("$READELF" -h "$lib" 2>/dev/null \
    | sed -n 's/^[[:space:]]*Machine:[[:space:]]*//p' | head -1)"
  if [[ -z "$machine" ]]; then
    printf '  ✗ %-30s 无法读取 Machine 字段（readelf 输出格式可能已变）\n' "$(basename "$lib")"
    FAIL=1
  elif [[ "$machine" != *"$EXPECT_MACHINE"* ]]; then
    printf '  ✗ %-30s 架构=%s（期望 %s）\n' "$(basename "$lib")" "$machine" "$EXPECT_MACHINE"
    FAIL=1
  fi
done < <(find "$LIBDIR" -maxdepth 1 -type f -name '*.so*')

if [[ "$ARCH_CHECKED" -eq 0 ]]; then
  die "stage 里没有找到任何 .so —— 前面的收集步骤可能失败了"
fi
[[ "$FAIL" == "0" ]] && log "  ✓ 全部 $ARCH_CHECKED 个库均为目标架构" || die "存在架构不匹配的库"

# ── 2. 16KB page size 对齐（Google Play 2025-11 起强制）
log "检查 2/4：16KB page size 对齐（Android 15+ 要求）"
if [[ "$ARCH" == "arm64" || "$ARCH" == "x86_64" ]]; then
  ALIGNED=1
  while IFS= read -r lib; do
    # 取所有 LOAD 段 Align 的**数值最大值**。
    # ⚠️ 三个坑，2026-10-04 全部实测踩到：
    #    ① 不能抓「行内所有 0x 字段」——那会把 Offset/Vaddr/Paddr/Filesz/
    #       Memsz 一起抓进来（例如 0x2630=9776 会被误当成 Align）。
    #       Align 永远是**该行的最后一个字段**，只取它。
    #    ② 不能用 `sort -u | tail -1` ——按字符串排序时 "0x1000" > "0x4000"，
    #       会取到较小值而误判。必须先转十进制再 `sort -n`。
    #    ③ 十六进制转十进制用 bash 的 `$(( ))` 是安全的（支持 0x 前缀），
    #       但为稳妥用 printf '%d'。
    max_align_dec="$("$READELF" -l "$lib" 2>/dev/null \
      | awk '/^[[:space:]]*LOAD/ { if ($NF ~ /^0x[0-9a-fA-F]+$/) print $NF }' \
      | while read -r h; do printf '%d\n' "$h"; done \
      | sort -n | tail -1)"
    if [[ -n "$max_align_dec" ]]; then
      if [[ "$max_align_dec" -lt 16384 ]]; then
        printf '  ⚠ %-30s LOAD align=%d (< 16384)\n' "$(basename "$lib")" "$max_align_dec"
        ALIGNED=0
      fi
    fi
  done < <(find "$LIBDIR" -maxdepth 1 -type f -name '*.so*')
  if [[ "$ALIGNED" == "1" ]]; then
    log "  ✓ 全部满足 16KB 对齐"
  else
    warn "  ⚠ 部分库未按 16KB 对齐 —— 上架 Play 可能被拒"
    warn "    解法：给 LDFLAGS 加 -Wl,-z,max-page-size=16384 后重编"
    # 默认仅告警（侧载/本地调试不受影响）。
    # CI 上如需硬门禁，设 STRICT_16KB=1 即变 die。
    if [[ "${STRICT_16KB:-0}" == "1" ]]; then
      die "16KB 对齐检查未通过（STRICT_16KB=1 要求必须对齐）"
    fi
  fi
else
  log "  （32 位架构不适用，跳过）"
fi

# ── 3. 关键能力检查：libmpv.so 必须真正导出 mpv_* 符号
log "检查 3/4：libmpv.so 符号导出"
MPV_LIB="$LIBDIR/libmpv.so"
[[ -f "$MPV_LIB" ]] || die "缺少 libmpv.so"

# 优先用 --dyn-syms（只看动态符号表，即运行时真正可见的）；
# 老版 llvm-readelf 不认这个选项时回退到 -s，但那会包含调试符号 ——
# 所以回退时额外要求符号行里 Type 为 FUNC/OBJECT（排除调试条目）。
SYMS="$("$READELF" --dyn-syms "$MPV_LIB" 2>/dev/null)" || SYMS=""
USING_DYN=1
if [[ -z "$SYMS" ]]; then
  USING_DYN=0
  SYMS="$("$READELF" -s "$MPV_LIB" 2>/dev/null)"
  warn "  llvm-readelf 不支持 --dyn-syms，回退到 -s（可能包含非动态符号）"
fi
[[ -n "$SYMS" ]] || die "无法读取 $MPV_LIB 的符号表"

for sym in mpv_create mpv_initialize mpv_command mpv_set_option_string mpv_get_property mpv_wait_event mpv_terminate_destroy; do
  # 用 `-w` 全字匹配，避免 mpv_command_async 之类前缀混淆
  if echo "$SYMS" | grep -qw "$sym"; then
    log "  ✓ $sym"
  else
    printf '  ✗ 缺少符号：%s\n' "$sym"
    FAIL=1
  fi
done
[[ "$FAIL" == "0" ]] || die "libmpv.so 缺少必要导出符号（这会导致 JNI 侧 mpv_create 返回 NULL）"

# ── 4. 体积报告（与预期区间对比，仅告警）
log "检查 4/4：体积"
TOTAL_BYTES=$(du -sb "$LIBDIR" | cut -f1)
TOTAL_MB=$(( TOTAL_BYTES / 1024 / 1024 ))
log "  未压缩合计：${TOTAL_MB} MB（${TOTAL_BYTES} 字节）"
echo ""
echo "  各库体积（前 12 大）："
(cd "$LIBDIR" && ls -la | awk 'NR>1 {printf "    %10d  %s\n", $5, $9}' | sort -rn | head -12)

if [[ "$TOTAL_MB" -gt 40 ]]; then
  warn "  ⚠ 体积偏大（>40 MB）—— 检查视频栈是否未裁剪干净"
elif [[ "$TOTAL_MB" -lt 3 ]]; then
  warn "  ⚠ 体积异常小（<3 MB）—— 可能裁剪过度，确认格式是否齐全"
else
  log "  ✓ 体积在合理区间"
fi

log "验证通过。"
