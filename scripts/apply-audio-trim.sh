#!/usr/bin/env bash
# apply-audio-trim.sh —— 把 mpv-android buildscripts 裁剪成「音频专用」。
#
# 运行位置：<buildscripts>/ （即 mpv-android/buildscripts）
# 前提：已跑过 download.sh（源码树已就位）
#
# 做什么：
#   1. 从 mpv 的依赖链里摘掉 libplacebo（视频渲染，最大依赖之一）
#   2. 在 ffmpeg 的 configure 参数里显式 enable 音频解码器/滤镜，
#      并 disable 视频编解码与不常用组件
#   3. 保留 ape / dsd / flac / alac / pcm_s24le（高解析关键）
#   4. 保留 afir / aiir / equalizer（EQ 与 IR 卷积）
#
# ⚠️ 这是「最小侵入式改写」——直接改 buildscripts 的脚本文件本身。
#    上游脚本结构可能变化，脚本会在改写前做存在性断言，失败即退出
#    （fail-closed，宁可红也不要产出一个悄悄少了格式的库）。

set -euo pipefail

log()  { printf '\033[1;34m[trim]\033[0m %s\n' "$*"; }
warn() { printf '\033[1;33m[trim]\033[0m %s\n' "$*"; }
die()  { printf '\033[1;31m[trim] FATAL:\033[0m %s\n' "$*" >&2; exit 1; }

[[ -f buildall.sh ]]       || die "找不到 buildall.sh —— 请在 buildscripts 目录下运行"
[[ -f include/depinfo.sh ]] || die "找不到 include/depinfo.sh"

# ─────────────────────────────────────────────────────────────────
# 1. 摘掉 libplacebo
#    dep_mpv=(ffmpeg libass lua libplacebo)  →  dep_mpv=(ffmpeg libass lua)
#    同时把 libplacebo 的构建脚本设为 no-op，防止被别的依赖间接拉起。
# ─────────────────────────────────────────────────────────────────
log "步骤 1/4：从 mpv 依赖链摘除 libplacebo"

DEPINFO="include/depinfo.sh"
cp "$DEPINFO" "$DEPINFO.orig"

if grep -q '^dep_mpv=' "$DEPINFO"; then
  # 删除 dep_mpv 行里出现的 libplacebo（单词边界，避免误伤类似名）
  sed -i.tmp -E 's/^(dep_mpv=\([^)]*)\)/\1)/; s/\blibplacebo\b//g; s/  +/ /g' "$DEPINFO"
  rm -f "$DEPINFO.tmp"
  log "  dep_mpv 现在是：$(grep '^dep_mpv=' "$DEPINFO")"
else
  warn "  未找到 dep_mpv= 行 —— 上游结构可能已变，跳过（不致命）"
fi

# 把 libplacebo 的构建脚本改成 no-op（双保险：防间接依赖）
PLACEBO_SCRIPT="scripts/libplacebo.sh"
if [[ -f "$PLACEBO_SCRIPT" ]]; then
  cp "$PLACEBO_SCRIPT" "$PLACEBO_SCRIPT.orig"
  cat > "$PLACEBO_SCRIPT" <<'EOF'
#!/usr/bin/env bash
# [audio-trim] libplacebo 已被裁剪掉（音频 App 不需要视频渲染）。
echo "[audio-trim] skipping libplacebo (trimmed for audio-only build)"
exit 0
EOF
  chmod +x "$PLACEBO_SCRIPT"
  log "  $PLACEBO_SCRIPT 已置为 no-op"
fi

# 同时删掉 dep_libplacebo 声明（若存在）
sed -i -E 's/^dep_libplacebo=.*/dep_libplacebo=()/' "$DEPINFO" || true

# ─────────────────────────────────────────────────────────────────
# 2. 改写 ffmpeg configure 参数
#    buildscripts/scripts/ffmpeg.sh 里有 FFMPEG_CONFIGURE 之类的变量
# ─────────────────────────────────────────────────────────────────
log "步骤 2/4：改写 ffmpeg configure 参数（音频专用）"

FFMPEG_SCRIPT="scripts/ffmpeg.sh"
[[ -f "$FFMPEG_SCRIPT" ]] || die "找不到 $FFMPEG_SCRIPT"
cp "$FFMPEG_SCRIPT" "$FFMPEG_SCRIPT.orig"

# 我们额外要的开关；逐条 append 到 configure 调用之前。
# 值用 awk 注入：找到以 `--prefix=` 开头的那一行所在数组/命令，插到它前面。
TRIM_FLAGS_FILE="$(mktemp)"
cat > "$TRIM_FLAGS_FILE" <<'EOF'

# ═══════════════════════════════════════════════════════════════
# [audio-trim] 以下为 YanMusic 音频专用裁剪，由 apply-audio-trim.sh 注入
# ═══════════════════════════════════════════════════════════════
# ⚠️ 顺序不能改：ffmpeg configure 对冲突选项是「**后者覆盖前者**」，
#    所以必须「先 --disable-<大类>，再按白名单 --enable-<组件>」。
#    反过来写的话，白名单会被大类开关一起关掉（2026-10-07 修正）。
#
# ⚠️ `--disable-video-decoders` 已删除 —— ffmpeg 没有这个选项，
#    传进去 configure 会因未知选项直接退出（2026-10-07 修正）。
#    关闭视频解码靠 `--disable-decoders` + 音频白名单。
#
# ⚠️ 不再 `--disable-zlib` —— Matroska 的压缩头需要它，
#    mka/mkv 是主力格式，禁掉会导致读不了。
audio_trim_flags=(
  # ═══ 第一步：关掉组件大类 ═══
  --disable-decoders
  --disable-demuxers
  --disable-parsers
  --disable-protocols
  --disable-filters
  --disable-bsfs

  # ═══ 第二步：关掉编码 / 封装 / 工具（播放器不需要）═══
  --disable-encoders
  --disable-muxers
  --disable-programs
  --disable-doc

  # ═══ 第三步：关掉设备与硬件加速（Android 音频用不到）═══
  --disable-devices
  --disable-hwaccels
  --disable-vaapi
  --disable-vdpau
  --disable-cuda
  --disable-cuvid
  --disable-nvenc
  --disable-dxva2
  --disable-videotoolbox
  --disable-mediacodec
  --disable-libdav1d

  # ═══ 第四步：关掉桌面平台与调试符号 ═══
  --disable-sdl2
  --disable-xlib
  --disable-libxcb
  --disable-debug
  --disable-symver

  # ═══ 第五步：按白名单打开音频能力（必须在 disable 之后）═══
  # ── 解码器（含高解析与无损）──
  --enable-decoder=aac,aac_latm,mp3,flac,alac,ape,wavpack,tta,tak
  --enable-decoder=pcm_s16le,pcm_s24le,pcm_s32le,pcm_f32le,pcm_s16be,pcm_s24be,pcm_s32be
  --enable-decoder=dsd_lsbf,dsd_msbf,dsd_lsbf_planar,dsd_msbf_planar
  --enable-decoder=vorbis,opus,ac3,eac3,dca,truehd,mlp
  --enable-decoder=adpcm_ima_wav,adpcm_ms,wmav1,wmav2,wmalossless
  # ── 解复用器（含 CUE / 整轨格式）──
  --enable-demuxer=mov,matroska,flac,mp3,ogg,wav,aiff,ape,dsf,dff,tak,wv,tta
  --enable-demuxer=aac,ac3,eac3,dts,truehd,asf,cue,concat
  # ── 解析器 ──
  --enable-parser=aac,aac_latm,mpegaudio,flac,ape,dsd,vorbis,opus,dca,ac3
  # ── 协议（本地 + 流式）──
  --enable-protocol=file,http,https,tcp,tls,hls,data,cache,pipe,concat
  # ── 比特流滤镜（AAC over HLS 需要）──
  --enable-bsf=aac_adtstoasc
  # ── 音频滤镜（EQ / IR 卷积 / 重采样）──
  --enable-filter=equalizer,superequalizer,aeval,afir,aiir
  --enable-filter=aresample,aformat,anull,volume,alimiter,acompressor,agate
  --enable-filter=atempo,asetrate,asetpts,atrim,abuffer,abuffersink,anullsink
  --enable-filter=channelmap,pan,join,asplit,amix,amerge,highpass,lowpass
  --enable-filter=acrossfade,adelay
  # ── 基础设施 ──
  --enable-swresample
  --enable-avfilter
  --enable-network
)
audio_trim_flags_joined="${audio_trim_flags[*]}"
EOF

# 在 `./configure` 或其等价调用前插入我们的数组，并把数组拼进调用
if grep -qE '^[[:space:]]*(\.\./|\./)?configure' "$FFMPEG_SCRIPT"; then
  # ── 插入 flags 定义 ──
  # ⚠️ 不要用 `awk -v flagsfile=... 'while ((getline l < flagsfile))'`：
  #    Windows/MSYS 下 mktemp 产生的是 `/tmp/xxx` 形式路径，
  #    而原生 awk.exe 不认 MSYS 虚拟路径 → getline 静默失败、
  #    数组定义一段都不插入，脚本却继续往下跑（2026-10-04 实测）。
  #    改用纯 bash 逐行拼接，完全绕开路径转换问题。
  FFMPEG_NEW="$FFMPEG_SCRIPT.new"
  : > "$FFMPEG_NEW"
  inserted_flags=0
  while IFS= read -r line; do
    if [[ $inserted_flags -eq 0 && "$line" =~ ^[[:space:]]*(\.\./|\./)?configure ]]; then
      cat "$TRIM_FLAGS_FILE" >> "$FFMPEG_NEW"
      inserted_flags=1
    fi
    printf '%s\n' "$line" >> "$FFMPEG_NEW"
  done < "$FFMPEG_SCRIPT"
  mv "$FFMPEG_NEW" "$FFMPEG_SCRIPT"

  # ⚠️ 断言「数组定义真的被插进去了」——上面的 awk 写法曾在
  #    Windows 上静默不插入，光看退出码是绿的。
  if [[ $inserted_flags -eq 0 ]] || ! grep -q '^audio_trim_flags=(' "$FFMPEG_SCRIPT"; then
    die "audio_trim_flags 数组定义未插入 $FFMPEG_SCRIPT —— 裁剪参数不会生效"
  fi

  # 在 configure 调用行追加引用（若尚未引用）
  #
  # ⚠️ 这里要写入的是**字面量** `$audio_trim_flags_joined`（留给
  #    buildscripts 运行时展开），所以 sed 用**单引号**让 `$` 保持字面量。
  #    若误用双引号，当前 shell 会立即展开该变量 —— 此时它尚未定义，
  #    结果是空串，注入变成无效操作且不易察觉。
  #
  # ⚠️ 守卫条件必须**只检查 configure 行**，不能全文件 grep
  #    'audio_trim_flags_joined' —— 我们上面刚插入的数组定义里
  #    就有 `audio_trim_flags_joined="${audio_trim_flags[*]}"` 这一行，
  #    全文件 grep 会误判「已引用」而跳过注入（2026-10-04 实测踩到）。
  #
  # ⚠️ 匹配要兼容**行尾续行反斜杠**：
  #    buildscripts 里普遍写成
  #        ./configure \
  #          --prefix=...
  #    若模式写成 `[^\\]*`（要求 configure 后到行尾无反斜杠），
  #    多行续行写法会完全匹配不上 → 注入静默失败。
  if ! grep -qE '^[[:space:]]*(\.\./|\./)?configure.*audio_trim_flags_joined' "$FFMPEG_SCRIPT"; then
    # ⚠️ 必须追加到**行尾**（`\1\3 $var`），不能插在 configure 与 `\3` 之间。
    #    ffmpeg configure 是「后者覆盖前者」，若我们的 flags 在上游 args 之前，
    #    上游的 `--enable-mediacodec` / `--enable-libdav1d` 会反过来盖掉裁剪。
    #    （2026-10-07 本地实测发现并修正）
    sed -i -E 's#^([[:space:]]*(\.\./|\./)?configure)(.*)$#\1\3 $audio_trim_flags_joined#' "$FFMPEG_SCRIPT"
  fi

  # ── 生效点断言 ──
  # 只断言「文件里出现过 ape/flac 等字样」是**不够的** ——
  # 那些字样就写在我们插入的数组定义里，即便 configure 从未引用它，
  # 断言照样是绿的（v1.3.3 曾栽在同类「标识符存在 ≠ 生效」的误判上）。
  # 真正决定生效的是：configure 行必须引用该变量。
  if ! grep -qE '^[[:space:]]*(\.\./|\./)?configure.*audio_trim_flags_joined' "$FFMPEG_SCRIPT"; then
    die "ffmpeg configure 调用行未引用 \$audio_trim_flags_joined —— 裁剪参数不会生效"
  fi
  log "  ✓ 已注入 ffmpeg 音频白名单参数（数组定义 + configure 引用均已断言）"
else
  warn "  未在 $FFMPEG_SCRIPT 找到 configure 调用 —— 结构可能已变"
  warn "  已保存原始脚本为 $FFMPEG_SCRIPT.orig，请人工检查裁剪是否生效"
fi
rm -f "$TRIM_FLAGS_FILE"

# ─────────────────────────────────────────────────────────────────
# 3. 改写 mpv 的 Meson 参数：关闭视频相关
# ─────────────────────────────────────────────────────────────────
log "步骤 3/4：改写 mpv Meson 参数（关闭视频栈）"

MPV_SCRIPT="scripts/mpv.sh"
if [[ -f "$MPV_SCRIPT" ]]; then
  cp "$MPV_SCRIPT" "$MPV_SCRIPT.orig"

  # 常见的 Meson 开关；对已存在的不重复加，对新加的出现即生效。
  # ⚠️ 这里只关「视频渲染/输出」，不动 libass（歌词可能需要）
  #    也不动 libplacebo（已在步骤 1 从依赖链摘除）
  MPV_MESON_TRIM=(
    "-Dcplayer=false"          # 不编 mpv 可执行文件
    "-Dlibmpv=true"            # 只编 libmpv 共享库
    "-Dgl=disabled"
    "-Dvulkan=disabled"
    "-Dshaderc=disabled"
    "-Dd3d11=disabled"
    "-Dcuda=disabled"
    "-Dvaapi=disabled"
    "-Dvdpau=disabled"
    "-Dcaca=disabled"
    "-Djpeg=disabled"
    "-Dzimg=disabled"
    "-Ddvdnav=disabled"
    "-Dlibbluray=disabled"
    "-Drubberband=disabled"
    "-Duchardet=disabled"
    "-Dvapoursynth=disabled"
    "-Djavascript=disabled"
    "-Dlua=enabled"            # 保留 lua（脚本能力，体积小）
  )

  if grep -qE 'meson[[:space:]]+(setup[[:space:]]+)?' "$MPV_SCRIPT"; then
    # ── 注入方式：在 meson 命令的**末行行尾**统一追加 ──
    # 不用「逐个 flag 找位置插」，那种做法：
    #   ① 容易被 `s#\s*key=...##g` 误删其它行内容
    #   ② 依赖行尾无反斜杠（多行续行时静默失败）
    # 改成：找到 meson 那条逻辑命令的最后一行（首个不以 \ 结尾的行），
    #       在该行**行尾**追加全部 flags —— 保持在同一命令内。

    # 先剔除脚本里已有的同名开关，避免 -Dgl=disabled 出现两次而报错
    for flag in "${MPV_MESON_TRIM[@]}"; do
      key="${flag%%=*}"
      sed -i -E "s#[[:space:]]*${key}=[A-Za-z0-9_]+##g" "$MPV_SCRIPT" || true
    done

    # 定位第一个 meson 行，记录行号；若该行以 \ 结尾，则插入点后移到
    # 该逻辑命令的最后一行（第一行不以 \ 结尾的行）。
    #
    # ⚠️ 关键：Meson 的续行块里，**只有最后一行不带反斜杠**。
    #    我们要在「最后一行」的**同一行行尾**追加 flags（缩进对齐），
    #    而不是「最后一行之后新起一行」——后者会让 flags 变成一条
    #    独立命令（前一行已无续行符），运行时直接报 command not found。
    meson_line="$(grep -nE 'meson[[:space:]]+(setup[[:space:]]+)?' "$MPV_SCRIPT" | head -1 | cut -d: -f1)"
    if [[ -n "$meson_line" ]]; then
      total=$(wc -l < "$MPV_SCRIPT")
      last_line="$meson_line"
      while (( last_line < total )); do
        cur="$(sed -n "${last_line}p" "$MPV_SCRIPT")"
        [[ "$cur" == *\\ ]] || break
        last_line=$((last_line + 1))
      done
      # 在这一行**行尾**追加（保留原有内容与缩进）
      sed -i "${last_line}s#\$# ${MPV_MESON_TRIM[*]}#" "$MPV_SCRIPT"
      log "  已在 meson 命令（第 ${meson_line}-${last_line} 行）行尾追加裁剪参数"
    fi

    # ── 生效点断言 ──
    # ⚠️ 不能只 grep 全文件「-Dgl=disabled 是否存在」——
    #    它可能落在 meson 命令**之外**（例如被插成独立一行），
    #    那样 meson 根本收不到这个参数，但断言照样是绿的。
    #    正确做法：截取 meson 那条逻辑命令的整个文本块，在块内断言。
    #    （教训同 v1.3.3：标识符在文件里出现 ≠ 在生效点出现。）
    if [[ -n "$meson_line" ]]; then
      meson_block="$(sed -n "${meson_line},${last_line}p" "$MPV_SCRIPT")"
      missing=()
      for key in '-Dgl=disabled' '-Dvulkan=disabled' '-Dlibmpv=true' '-Dcplayer=false'; do
        [[ "$meson_block" == *"$key"* ]] || missing+=("$key")
      done
      # 反向断言：meson 命令块的续行结构不能被破坏 ——
      # 若末行原本是续行（本不该发生，因为我们取的就是非续行行），
      # 或块内出现「独立一行只有 -D 开头」，说明结构错了。
      if (( ${#missing[@]} > 0 )); then
        die "mpv Meson 裁剪参数未落在 meson 命令内，块内缺少：${missing[*]}"
      fi
      log "  ✓ 已注入 mpv Meson 裁剪参数（$((${#MPV_MESON_TRIM[@]})) 项；已断言它们落在 meson 命令块内）"
    else
      die "未能在 $MPV_SCRIPT 定位 meson 行 —— 注入未执行"
    fi
  else
    warn "  $MPV_SCRIPT 里未找到 meson 调用 —— 结构可能已变"
  fi
else
  warn "  找不到 $MPV_SCRIPT"
fi

# ─────────────────────────────────────────────────────────────────
# 4. 断言：关键能力确实被保留（fail-closed）
# ─────────────────────────────────────────────────────────────────
log "步骤 4/4：自检（确认关键音频能力未被误删）"

check_present() {
  local needle="$1" file="$2" label="$3"
  if grep -q -- "$needle" "$file"; then
    log "  ✓ $label"
  else
    die "裁剪后缺少关键项『$label』（$needle 未出现在 $file）—— 拒绝产出有缺陷的库"
  fi
}

check_present 'ape'          "$FFMPEG_SCRIPT" 'APE（Monkey Audio）解码'
check_present 'dsd'          "$FFMPEG_SCRIPT" 'DSD 解码'
check_present 'flac'         "$FFMPEG_SCRIPT" 'FLAC 解码'
check_present 'pcm_s24le'    "$FFMPEG_SCRIPT" '24bit PCM（高解析）'
check_present 'afir'         "$FFMPEG_SCRIPT" 'afir IR 卷积（空间音效）'
check_present 'equalizer'    "$FFMPEG_SCRIPT" 'equalizer（10 段 EQ）'

# ⚠️ 上面 6 条只证明「字样存在于文件」，不证明「参数真的传给了 configure」——
#    字样就写在注入的数组定义里，注入失败时它们照样在。
#    真正决定生效的是下面这条：configure 行必须引用那个变量。
if ! grep -qE '^[[:space:]]*(\.\./|\./)?configure.*audio_trim_flags_joined' "$FFMPEG_SCRIPT"; then
  die "ffmpeg configure 行未引用 \$audio_trim_flags_joined —— 音频白名单参数不会被应用"
fi
log "  ✓ configure 行确实引用了 audio_trim_flags_joined（生效点已确认）"

# 断言 libplacebo 确实被摘掉
if grep -qE '^dep_mpv=\([^)]*libplacebo' "$DEPINFO"; then
  die "libplacebo 仍在 mpv 依赖链中 —— 裁剪未生效"
fi
log "  ✓ libplacebo 已从依赖链移除"

log "音频专用裁剪完成。原始脚本已备份为 *.orig"
