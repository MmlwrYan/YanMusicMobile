#!/usr/bin/env bash
# check-symbol-closure.sh —— 符号级依赖闭包校验（CI 可跑，无需真机）
#
# 用法：
#   check-symbol-closure.sh <stage 下的 ABI 目录> <NDK 根目录> [TRIPLE]
# 例：
#   check-symbol-closure.sh out/stage/arm64-v8a "$MPV_NDK_DIR" aarch64-linux-android
#
# ═══════════════════════════════════════════════════════════════════
# 为什么需要它（2026-10-07 真机实证的教训）
#
# collect-libs.sh 原有的「依赖闭包完整性」断言只做到**库级**：
#   「stage 里每个 .so 的 DT_NEEDED，要么在 stage 里，要么属系统库」。
# 这条**不足以**拦住真实故障，因为：
#   libmpv.so 需要 libc++_shared.so，而白名单误把它当系统库 →
#   库级断言认为「已满足」并打印「✓ 依赖闭包完整」，产物却少了整个 C++ 运行库。
#   真机实测：`dlopen failed: library "libc++_shared.so" not found`。
#
# 本脚本把断言下沉到**符号级**：对 stage 内每个 .so 的每个**强未定义(U)**符号，
# 必须在「提供者集合」里找得到，否则装到设备上必然
#   `dlopen failed: cannot locate symbol "..."`。
#
# 提供者集合 =
#     ① stage 内所有 .so 的导出符号
#     ② NDK sysroot 内**未被 stage 覆盖**的系统库 stub 的导出符号
#
#   ★ ② 的「未被 stage 覆盖」是关键：运行期同名的本地库优先于系统库，
#     所以只要 stage 里有 libc++_shared.so，就不能再拿 sysroot 里那份
#     来充当提供者 —— 否则「带了错误版本的 libc++」会被静默放行。
#     这正是本守卫要抓的场景（r27/r28 的 libc++ 缺 __from_chars_floating_point）。
#
# 注：sysroot 侧只取**最高 API 目录**的 stub（避免逐 API 全扫导致的：
#     ① 耗时暴涨 ② 用低 API 判定而误报「符号不存在」）。
#     代价是略偏宽松 —— 但它只影响「系统库提供什么」，
#     而本次事故的全部关键在于「本地库是否带对」，这一侧是严格判定的。
# ═══════════════════════════════════════════════════════════════════

set -euo pipefail
export LC_ALL=C LANG=C

STAGE_DIR="${1:-}"
NDK_ROOT="${2:-}"
TRIPLE="${3:-aarch64-linux-android}"

die() { printf '\033[1;31m[symclos] FATAL:\033[0m %s\n' "$*" >&2; exit 1; }
log() { printf '\033[1;34m[symclos]\033[0m %s\n' "$*"; }
ok()  { printf '\033[1;32m[symclos]\033[0m %s\n' "$*"; }

[[ -n "$STAGE_DIR" ]] || die "用法：check-symbol-closure.sh <stage/ABI 目录> <NDK 根目录> [TRIPLE]"
[[ -d "$STAGE_DIR" ]] || die "stage 目录不存在：$STAGE_DIR"
[[ -n "$NDK_ROOT" ]] || die "缺少 NDK 根目录参数"
[[ -d "$NDK_ROOT" ]] || die "NDK 目录不存在：$NDK_ROOT"

# ── 定位 llvm-nm（NDK 自带；三种 host 都试，便于本机复跑）
BIN=""
for host in linux-x86_64 windows-x86_64 darwin-x86_64; do
  if [[ -x "$NDK_ROOT/toolchains/llvm/prebuilt/$host/bin/llvm-nm" ]]; then
    BIN="$NDK_ROOT/toolchains/llvm/prebuilt/$host/bin"
    break
  fi
done
[[ -n "$BIN" ]] || die "在 $NDK_ROOT 下找不到 llvm-nm"
NM="$BIN/llvm-nm"
log "NDK    ：$NDK_ROOT"
log "llvm-nm：$NM"
log "stage  ：$STAGE_DIR"
log "triple ：$TRIPLE"

TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT

# ── 抽取函数
#    ⚠️ llvm-nm 的输出布局：
#       已定义：`00000000000d6654 W _ZNSt...`   → $1=地址 $2=类型 $NF=名字
#       未定义：`                 U mpv_create`  → $1=类型 $NF=名字（地址列是空白）
#    所以类型列**不是固定下标**。一律用 `$(NF-1)` 取类型、`$NF` 取名字，
#    两种布局都成立。（初版误写成 `$2=="U"`，对未定义符号会得到空集 —— 这个
#    「守卫自己永远为真」的坑必须避免。）
#    符号名去掉 `@VERSION` 后缀（如 `__cxa_atexit@LIBC`）。
nm_defined() { "$NM" -D --defined-only "$1" 2>/dev/null | awk 'NF>=2 {n=$NF; sub(/@.*$/,"",n); print n}'; }
nm_undef()   { "$NM" -D --undefined-only "$1" 2>/dev/null | awk '$(NF-1)=="U" {print $NF}'; }

# ── 1) stage 清单 + 「本地库优先」用的 basename 集合
find "$STAGE_DIR" -maxdepth 1 -name '*.so' -type f | sort > "$TMP/stage.list"
STAGE_N="$(wc -l < "$TMP/stage.list")"
[[ "$STAGE_N" -gt 0 ]] || die "stage 目录里没有 .so：$STAGE_DIR"
sed 's#.*/##' "$TMP/stage.list" | sort -u > "$TMP/stage.basenames"

# ── 2) sysroot stub 目录：取最高数字 API 目录（回退到 triple 根）
SYSDIR=""
_best=-1
for _d in "$NDK_ROOT"/toolchains/llvm/prebuilt/*/sysroot/usr/lib/"$TRIPLE"/*/; do
  [[ -d "$_d" ]] || continue
  _b="$(basename "$_d")"
  [[ "$_b" =~ ^[0-9]+$ ]] || continue
  if (( _b > _best )); then _best="$_b"; SYSDIR="$_d"; fi
done
if [[ -z "$SYSDIR" ]]; then
  for _d in "$NDK_ROOT"/toolchains/llvm/prebuilt/*/sysroot/usr/lib/"$TRIPLE"/; do
    if [[ -d "$_d" ]]; then SYSDIR="$_d"; break; fi
  done
fi
[[ -n "$SYSDIR" ]] || die "NDK sysroot 下找不到 $TRIPLE 的库目录（$NDK_ROOT）"
log "sysroot stub 目录：$SYSDIR（API=${_best}）"

find "$SYSDIR" -maxdepth 1 -name '*.so' -type f | sort > "$TMP/sys.all"
# ★ 排除被 stage 覆盖的同名库（运行期本地库优先）
: > "$TMP/sys.list"
while IFS= read -r f; do
  [[ -z "$f" ]] && continue
  grep -qxF "$(basename "$f")" "$TMP/stage.basenames" && continue
  echo "$f" >> "$TMP/sys.list"
done < "$TMP/sys.all"
SYS_N="$(wc -l < "$TMP/sys.list")"
[[ "$SYS_N" -gt 0 ]] || die "sysroot 系统库集合为空（$SYSDIR）"

# ── 3) 提供者集合
{
  while IFS= read -r f; do [[ -n "$f" ]] && nm_defined "$f"; done < "$TMP/stage.list"
  while IFS= read -r f; do [[ -n "$f" ]] && nm_defined "$f"; done < "$TMP/sys.list"
} | sed '/^$/d' | sort -u > "$TMP/providers.txt"
PROV_N="$(wc -l < "$TMP/providers.txt")"
[[ "$PROV_N" -gt 0 ]] || die "提供者符号集合为空 —— 解析异常，拒绝出绿"
log "stage .so = $STAGE_N 个；sysroot（去重后）= $SYS_N 个；提供者符号 = $PROV_N 个"

# ── 4) 需求集合（带来源，便于定位）
: > "$TMP/needs.txt"
while IFS= read -r f; do
  [[ -z "$f" ]] && continue
  b="$(basename "$f")"
  nm_undef "$f" | sed "s|\$|\t$b|" >> "$TMP/needs.txt"
done < "$TMP/stage.list"
cut -f1 "$TMP/needs.txt" | sed '/^$/d' | sort -u > "$TMP/needs_uniq.txt"
NEED_N="$(wc -l < "$TMP/needs_uniq.txt")"
[[ "$NEED_N" -gt 0 ]] || die "stage 内解析到 0 个未定义符号 —— 解析异常，拒绝出绿"
log "stage 内强未定义符号（去重后）：$NEED_N 个"

# ── 5) 差集
comm -23 "$TMP/needs_uniq.txt" "$TMP/providers.txt" > "$TMP/missing.raw"
sed '/^$/d' "$TMP/missing.raw" > "$TMP/missing.txt"
MISS_N="$(wc -l < "$TMP/missing.txt")"

if [[ "$MISS_N" -gt 0 ]]; then
  {
    echo ""
    printf '\033[1;31m[symclos] 未找到提供者的符号（%s 个）—— 装到设备会 cannot locate symbol\033[0m\n' "$MISS_N"
    echo ""
    while IFS= read -r sym; do
      [[ -z "$sym" ]] && continue
      src="$(awk -v s="$sym" -F'\t' '$1==s {print $2; exit}' "$TMP/needs.txt")"
      printf '  %-88s  ← %s\n' "$sym" "$src"
    done < "$TMP/missing.txt"
    echo ""
    echo "常见成因："
    echo "  ① 参与链接的运行库版本不对：如 libc++_shared.so 取了别版 NDK 的，"
    echo "     缺 std::__ndk1::__from_chars_floating_point 一类较新符号；"
    echo "  ② 依赖库根本没进 stage（库级断言理应先拦下）；"
    echo "  ③ 被误列进 collect-libs.sh 的系统库白名单，实际 OS 并不提供。"
  } >&2
  die "符号级依赖闭包不完整：$MISS_N 个符号无提供者，拒绝产出"
fi

ok "✓ 符号级依赖闭包完整：$NEED_N 个未定义符号全部有提供者"
ok "  （stage $STAGE_N 个 .so + sysroot stub $SYS_N 个；本地库优先于系统库）"
