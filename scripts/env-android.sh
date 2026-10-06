#!/usr/bin/env bash
# YanMusic Android 开发环境（bash / Git Bash 版）
# 用法：source scripts/env-android.sh
#
# ⚠️ 必须用 `source`（而不是直接执行）：否则导出到的是子 shell，
#    当前终端拿不到这些变量。

export JAVA_HOME="C:/Program Files/Eclipse Adoptium/jdk-17.0.20.101-hotspot"
export ANDROID_SDK_ROOT="/d/Android/Sdk"
export ANDROID_HOME="/d/Android/Sdk"
export ANDROID_NDK_HOME="/d/Android/Sdk/ndk/27.1.12297006"
export ANDROID_NDK_VERSION="27.1.12297006"
export PATH="$JAVA_HOME/bin:$ANDROID_SDK_ROOT/platform-tools:$ANDROID_SDK_ROOT/cmdline-tools/latest/bin:$PATH"

# ─────────────────────────────────────────────────────────────────
# Rust 交叉编译到 Android 所需的 linker
#
# `.cargo/config.toml` 里刻意**不写死** NDK 路径（CI 是 Linux、
# 本地是 Windows，写死任一侧都会让另一侧失效），改由这里注入。
# 若缺失，Rust 会回退到系统 cc 并大概率链接失败。
#
# 变量命名规则（Cargo 规定）：
#   CARGO_TARGET_<TRIPLE 大写且 - 换 _>_LINKER
# ─────────────────────────────────────────────────────────────────
_NDK_BIN="$ANDROID_NDK_HOME/toolchains/llvm/prebuilt/windows-x86_64/bin"

# minSdk 26 → 用 *26-clang 包装器（会自动带上 --target=<triple>26）
export CARGO_TARGET_AARCH64_LINUX_ANDROID_LINKER="$_NDK_BIN/aarch64-linux-android26-clang.cmd"
export CARGO_TARGET_ARMV7_LINUX_ANDROIDEABI_LINKER="$_NDK_BIN/armv7a-linux-androideabi26-clang.cmd"
export CARGO_TARGET_X86_64_LINUX_ANDROID_LINKER="$_NDK_BIN/x86_64-linux-android26-clang.cmd"
export CARGO_TARGET_I686_LINUX_ANDROID_LINKER="$_NDK_BIN/i686-linux-android26-clang.cmd"

# NDK 的 llvm-ar 供 Cargo 的 ar 配置使用
export AR_aarch64_linux_android="$_NDK_BIN/llvm-ar.exe"
export AR_armv7_linux_androideabi="$_NDK_BIN/llvm-ar.exe"

unset _NDK_BIN

echo "YanMusic Android 环境已加载："
echo "  JAVA_HOME        = $JAVA_HOME"
echo "  ANDROID_SDK_ROOT = $ANDROID_SDK_ROOT"
echo "  ANDROID_NDK_HOME = $ANDROID_NDK_HOME"
echo "  Rust linker (arm64) = $CARGO_TARGET_AARCH64_LINUX_ANDROID_LINKER"
