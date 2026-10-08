#!/usr/bin/env bash
# selftest-symbol-guard.sh —— check-symbol-closure.sh 的**鉴别力自检**（变异测试）
#
# 用法：
#   selftest-symbol-guard.sh <stage 下的 ABI 目录> <NDK 根目录> [TRIPLE]
#
# ═══════════════════════════════════════════════════════════════════
# 为什么需要它
#
# 守卫本身也可能是错的、或者被写成「恒绿」。项目纪律要求：
#   每条守卫都要做**鉴别力验证** —— 喂回「修复前/坏掉的输入」必须变红。
# 只看到绿不等于守卫有效，只说明它没报警。
#
# 本脚本在 CI 内构造一个**确定会坏**的输入，断言守卫确实会红：
#
#   变异手法：把 stage 里的 `libc++_shared.so` 换成同名的**系统库 stub**
#             （libc.so 的副本，来自 NDK sysroot）。
#   · 库级闭包仍然「完整」——文件名在、DT_NEEDED 满足 →
#     **只有符号级守卫**能发现「这个库不提供 std::__ndk1::* 」。
#   · 该变异是确定性的：libc.so 永远不会导出 libc++ 的符号，
#     不像「用旧版 NDK 的 libc++」那样会随上游升级而失效。
#
# 断言：
#   [1] 原始 stage      → 守卫必须**绿**（退出码 0）
#   [2] 变异后的 stage  → 守卫必须**红**（退出码非 0），且报告中出现缺失符号
#   任一不满足 → 自检失败（说明守卫失效，比产物坏掉更严重）。
# ═══════════════════════════════════════════════════════════════════

set -euo pipefail
export LC_ALL=C LANG=C

STAGE_DIR="${1:-}"
NDK_ROOT="${2:-}"
TRIPLE="${3:-aarch64-linux-android}"
GUARD="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/check-symbol-closure.sh"

die() { printf '\033[1;31m[selftest] FATAL:\033[0m %s\n' "$*" >&2; exit 1; }
log() { printf '\033[1;34m[selftest]\033[0m %s\n' "$*"; }
ok()  { printf '\033[1;32m[selftest]\033[0m %s\n' "$*"; }

[[ -d "$STAGE_DIR" ]] || die "stage 目录不存在：$STAGE_DIR"
[[ -d "$NDK_ROOT" ]] || die "NDK 目录不存在：$NDK_ROOT"
[[ -f "$GUARD" ]] || die "找不到守卫脚本：$GUARD"

TMP="$(mktemp -d)"
trap 'rm -rf "$TMP" 2>/dev/null || true' EXIT       # 清理失败不得影响退出码

# ── 找一个系统库 stub 作为「李鬼」素材：优先 libc.so（且必须真有导出符号）
STUB=""
for host in linux-x86_64 windows-x86_64 darwin-x86_64; do
  base="$NDK_ROOT/toolchains/llvm/prebuilt/$host/sysroot/usr/lib/$TRIPLE"
  [[ -d "$base" ]] || continue
  # 取最高数字 API 目录里的 libc.so
  best=-1
  for d in "$base"/*/; do
    [[ -d "$d" ]] || continue
    b="$(basename "$d")"
    [[ "$b" =~ ^[0-9]+$ ]] || continue
    if (( b > best )); then best="$b"; STUB="$d/libc.so"; fi
  done
  # 注意：不要写 `[[ ]] && cmd` —— 条件为假时返回 1，`set -e` 会终止脚本
  if [[ -z "$STUB" && -f "$base/libc.so" ]]; then STUB="$base/libc.so"; fi
  if [[ -n "$STUB" ]]; then break; fi
done
[[ -n "$STUB" && -f "$STUB" ]] || die "找不到用于变异的系统库 stub（libc.so）"
log "变异素材：$STUB"

# ── [1] 原始 stage 必须绿
log "── [1/2] 原始 stage 应通过 ──"
if bash "$GUARD" "$STAGE_DIR" "$NDK_ROOT" "$TRIPLE" > "$TMP/pos.log" 2>&1; then
  ok "  通过（符合预期）"
else
  cat "$TMP/pos.log" >&2
  die "原始 stage 未通过守卫 —— 先修产物/守卫本身，自检中止"
fi

# ── [2] 变异 stage 必须红
log "── [2/2] 变异 stage（libc++_shared.so 换成 libc.so 副本）应被拒 ──"
cp -a "$STAGE_DIR" "$TMP/mut"
[[ -f "$TMP/mut/libc++_shared.so" ]] \
  || die "stage 内没有 libc++_shared.so —— 变异前提不成立（说明补齐逻辑失效）"
cp "$STUB" "$TMP/mut/libc++_shared.so"

set +e
bash "$GUARD" "$TMP/mut" "$NDK_ROOT" "$TRIPLE" > "$TMP/neg.log" 2>&1
neg_rc=$?
set -e

if (( neg_rc == 0 )); then
  cat "$TMP/neg.log" >&2 || true
  die "变异输入被判为**通过** —— 守卫没有鉴别力（恒绿），这比产物坏掉更严重！"
fi
log "  已拒绝（退出码 $neg_rc，符合预期）"

if ! grep -q "未找到提供者的符号" "$TMP/neg.log"; then
  tail -40 "$TMP/neg.log" >&2
  die "变异被拒，但不是因为「符号缺提供者」—— 报错原因不对，守卫逻辑可能跑偏"
fi

echo "--- 变异模式下报出的缺失符号（前 8 条）---"
grep -E '^  _' "$TMP/neg.log" | head -8 || true
echo "---"

ok "✓ 鉴别力自检通过：原始 stage 绿、变异 stage 红，且红的原因正确（符号无提供者）"
