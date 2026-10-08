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
#     （已知宽松点，记录在 dev-notes 而非此处修正。)
#
# ═══════════════════════════════════════════════════════════════════
# ★ 2026-10-08 自炸修复（首轮 CI 实证）
#
# 首版有两处致命写法，导致守卫**自己静默退出**（无任何 FATAL 输出，
# CI 只看到 `Process completed with exit code 1`）：
#
#   ① `[[ -n "$f" ]] && nm_defined "$f"`
#      —— 在 `A && B` 中 B 是「the command following the final &&」，
#         按 POSIX，**B 失败会触发 set -e**。而 sysroot 里混有非 ELF 文件
#         （linker script / 空文件）时 llvm-nm 返回非 0 → 脚本当场死掉。
#         → 改为 `if ...; then ...; fi`（if 的条件部分失败不触发 set -e）。
#
#   ② nm 抽取函数返回管道的退出码，`set -o pipefail` 下会传播失败。
#         → 函数内追加 `|| true`，使其**永不返回非 0**；把「解析失败」
#           显式计数并打印，用**可见的警告**代替**静默的死亡**。
#
# 教训与 collect-libs.sh 那三处 `[[ ]] && cmd` 同源：
# **`A && B` 里的 B 在 `set -e` 下是危险的**，别只盯着行首是不是 `[[`。
# 另加 `set -E` + ERR trap：以后任何内部命令失败都会打出文件名与行号，
# 不再出现「无输出的 exit 1」。
# ═══════════════════════════════════════════════════════════════════

set -euo pipefail
set -E
export LC_ALL=C LANG=C

trap 'rc=$?; printf "\033[1;31m[symclos] 内部错误：命令以 %s 退出（%s 第 %s 行附近）\n\033[0m" \
      "$rc" "${BASH_SOURCE[0]}" "$LINENO" >&2' ERR

STAGE_DIR="${1:-}"
NDK_ROOT="${2:-}"
TRIPLE="${3:-aarch64-linux-android}"

die() { printf '\033[1;31m[symclos] FATAL:\033[0m %s\n' "$*" >&2; exit 1; }
log() { printf '\033[1;34m[symclos]\033[0m %s\n' "$*"; }
ok()  { printf '\033[1;32m[symclos]\033[0m %s\n' "$*"; }
warn(){ printf '\033[1;33m[symclos]\033[0m %s\n' "$*" >&2; }

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
# ★ 结尾 `|| true`：清理失败（只读挂载、沙箱策略等）不得触发上面的 ERR trap，
#   否则退出路径上会多打一条与真实故障无关的「内部错误」。
trap 'rm -rf "$TMP" 2>/dev/null || true' EXIT
: > "$TMP/nmfail.list"      # nm 解析失败名单（由 nm_dump 追加）

# ── ELF 判定
#   sysroot 里并非每个 `*.so` 都是 ELF：可能是 linker script（文本）或空文件，
#   对它们跑 llvm-nm 会报错。**必须显式识别并跳过，且要打印出来** ——
#   首版就是栽在这里（报错传播 → set -e 静默杀脚本）。
is_elf() {
  [[ -f "$1" ]] || return 1
  local magic
  magic="$(od -An -N4 -tx1 "$1" 2>/dev/null | tr -d ' \n')" || return 1
  [[ "$magic" == "7f454c46" ]]      # \x7f E L F
}

# ── 符号抽取
#    ⚠️ llvm-nm 的输出布局：
#       已定义：`00000000000d6654 W _ZNSt...`   → $1=地址 $2=类型 $NF=名字
#       未定义：`                 U mpv_create`  → $1=类型 $NF=名字（地址列是空白）
#    所以类型列**不是固定下标**。一律用 `$(NF-1)` 取类型、`$NF` 取名字，
#    两种布局都成立。（初版误写成 `$2=="U"`，对未定义符号会得到空集 —— 这个
#    「守卫自己永远为真」的坑必须避免。）
#    符号名去掉 `@VERSION` 后缀（如 `__cxa_atexit@LIBC`）。
#
#    ★ 退出码处理（两次踩坑后的最终形态）：
#      · 初版让管道退出码外泄 → pipefail → set -e 静默杀脚本；
#      · 加 `|| true` 后又变成「静默丢符号」—— 若某个真 ELF 库 nm 失败，
#        它的导出符号全丢，可能**误报缺口**或**假绿**，而日志里毫无痕迹。
#      · 现在：nm 的真实退出码被捕获，失败文件记入 nmfail.list，最后**显式报警**。
#        「不炸」与「不静默」必须同时满足。
nm_dump() {                       # $1=文件  $2=-号开关；输出符号行，失败记名单
  local out rc=0
  out="$("$NM" -D "$2" "$1" 2>/dev/null)" || rc=$?
  if (( rc != 0 )); then
    printf '  %s（exit %s）\n' "$1" "$rc" >> "$TMP/nmfail.list"
    return 0
  fi
  [[ -n "$out" ]] && printf '%s\n' "$out"
  return 0
}
# ⚠️ awk 必须带 `NF>=2` 守卫：管道里若混入空行，`$(NF-1)` 会取到 field -1 而
#    `fatal: attempt to access field -1`（实测踩到，退出码 2）。`&&` 短路可挡住。
#
# ⚠️⚠️ **两侧都必须去 `@VERSION` 后缀**，否则差集必然误报：
#    llvm-nm（同 GNU nm）会读 `.gnu.version_r/.gnu.version_d`，把符号显示成
#    `__cxa_atexit@LIBC`；而 ELF dynsym 里的原始名字并不带 `@`（用自写 ELF 解析器
#    交叉验证：本包 878 个 UND 符号，原始名字 0 个带 `@`）。
#    → 一边去、一边不去 = 把「同一符号」判成两个 → 满屏假缺口。
#    （实测 libmpv 的 UND 里确实出现 @LIBC / @LIBAV* 形态。）
nm_defined() { nm_dump "$1" --defined-only   | awk 'NF>=2 {n=$NF; sub(/@.*$/,"",n); print n}'; }
nm_undef()   { nm_dump "$1" --undefined-only | awk 'NF>=2 && $(NF-1)=="U" {n=$NF; sub(/@.*$/,"",n); print n}'; }

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

# ── 3) 收集「提供者」来源文件清单，并记录被跳过的非 ELF 文件
#    ★ 用 `if` 而非 `[[ ]] && cmd`：条件部分失败不得触发 set -e。
: > "$TMP/skip.list"
collect_list() {                      # $1=list 文件  $2=输出文件
  local f n=0
  while IFS= read -r f; do
    if [[ -z "$f" ]]; then continue; fi
    if ! is_elf "$f"; then
      printf '  %s\n' "$f" >> "$TMP/skip.list"
      n=$((n+1))
      continue
    fi
    printf '%s\n' "$f" >> "$2"
  done < "$1"
  return 0
}

find "$SYSDIR" -maxdepth 1 -name '*.so' -type f | sort > "$TMP/sys.all"
: > "$TMP/sys.list"
collect_list "$TMP/sys.all" "$TMP/sys.list"

# ★ 排除被 stage 覆盖的同名库（运行期本地库优先）
: > "$TMP/sys.eff"
while IFS= read -r f; do
  if [[ -z "$f" ]]; then continue; fi
  if grep -qxF "$(basename "$f")" "$TMP/stage.basenames"; then continue; fi
  printf '%s\n' "$f" >> "$TMP/sys.eff"
done < "$TMP/sys.list"
SYS_ALL_N="$(wc -l < "$TMP/sys.list")"
SYS_N="$(wc -l < "$TMP/sys.eff")"
SYS_OVR_N=$(( SYS_ALL_N - SYS_N ))
SKIP_N="$(wc -l < "$TMP/skip.list")"
[[ "$SYS_N" -gt 0 ]] || die "sysroot 系统库集合为空（$SYSDIR）"

if [[ "$SKIP_N" -gt 0 ]]; then
  warn "跳过 $SKIP_N 个非 ELF 文件（linker script／空文件，llvm-nm 无法解析）："
  cat "$TMP/skip.list" >&2
  warn "若下方报出缺口且缺口符号本应由这些文件提供，请人工核对。"
fi
log "sysroot 候选 $SYS_ALL_N 个 → 去重（排除 stage 同名 $SYS_OVR_N 个）后 $SYS_N 个"

# ── 4) 提供者集合
{
  while IFS= read -r f; do
    if [[ -z "$f" ]]; then continue; fi
    nm_defined "$f"
  done < "$TMP/stage.list"
  while IFS= read -r f; do
    if [[ -z "$f" ]]; then continue; fi
    nm_defined "$f"
  done < "$TMP/sys.eff"
} | sed '/^$/d' | sort -u > "$TMP/providers.txt"
PROV_N="$(wc -l < "$TMP/providers.txt")"
[[ "$PROV_N" -gt 0 ]] || die "提供者符号集合为空 —— 解析异常，拒绝出绿"

# ── 5) 需求集合（带来源，便于定位）
: > "$TMP/needs.txt"
while IFS= read -r f; do
  if [[ -z "$f" ]]; then continue; fi
  b="$(basename "$f")"
  nm_undef "$f" | sed "s|\$|\t$b|" >> "$TMP/needs.txt"
done < "$TMP/stage.list"
cut -f1 "$TMP/needs.txt" | sed '/^$/d' | sort -u > "$TMP/needs_uniq.txt"
NEED_N="$(wc -l < "$TMP/needs_uniq.txt")"
[[ "$NEED_N" -gt 0 ]] || die "stage 内解析到 0 个未定义符号 —— 解析异常，拒绝出绿"

log "stage .so = $STAGE_N 个；提供者符号 = $PROV_N 个；强未定义符号 = $NEED_N 个"

# ── 6) diff
comm -23 "$TMP/needs_uniq.txt" "$TMP/providers.txt" > "$TMP/missing.raw"
sed '/^$/d' "$TMP/missing.raw" > "$TMP/missing.txt"
MISS_N="$(wc -l < "$TMP/missing.txt")"

# ── 6.5) 解析失败必须可见（否则「静默丢符号」会被当成「符号真的不存在」）
NMFAIL_N="$(wc -l < "$TMP/nmfail.list")"
if [[ "$NMFAIL_N" -gt 0 ]]; then
  warn "有 $NMFAIL_N 个文件 llvm-nm 解析失败（其符号未计入提供者）："
  cat "$TMP/nmfail.list" >&2
  if grep -qF "  $STAGE_DIR/" "$TMP/nmfail.list"; then
    die "stage 内存在无法解析的库 —— 产物不可信，拒绝出绿"
  fi
  warn "均在 sysroot 侧；若下方报出缺口，缺口可能由这些文件解析失败所致。"
fi

if [[ "$MISS_N" -gt 0 ]]; then
  {
    echo ""
    printf '\033[1;31m[symclos] 未找到提供者的符号（%s 个）—— 装到设备会 cannot locate symbol\033[0m\n' "$MISS_N"
    echo ""
    while IFS= read -r sym; do
      if [[ -z "$sym" ]]; then continue; fi
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
