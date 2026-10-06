package com.yanmusic.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Kotlin 侧接口逻辑的单元测试 —— **不需要 native 库**。
 *
 * 这就是「在 Windows 上先把 Kotlin 侧写好」的验证方式：
 * 纯逻辑（JSON 解析、判据、契约常量）可以在没有 `.so` 的情况下测通，
 * 只有真正调用 native 的路径才需要等 CI 产物。
 */
class AudioParamsTest {

    @Test
    fun parses_typical_hires_flac_report() {
        val json = """{"samplerate":96000,"channels":2,"format":"s32","codec":"flac"}"""
        val p = AudioParams.fromJson(json)
        assertEquals(96000, p?.samplerate)
        assertEquals(2, p?.channels)
        assertEquals("s32", p?.format)
        assertEquals("flac", p?.codec)
    }

    @Test
    fun parses_cd_quality() {
        val json = """{"samplerate":44100,"channels":2,"format":"s16","codec":"flac"}"""
        val p = AudioParams.fromJson(json)
        assertEquals(44100, p?.samplerate)
        assertFalse("44.1kHz 不应判定为高解析", p!!.isHiRes)
    }

    @Test
    fun hires_boundary_is_88200() {
        val boundary = AudioParams.fromJson("""{"samplerate":88200,"channels":2,"format":"s24","codec":"flac"}""")
        assertTrue("88.2kHz 应判定为高解析（边界值）", boundary!!.isHiRes)

        val below = AudioParams.fromJson("""{"samplerate":48000,"channels":2,"format":"s24","codec":"flac"}""")
        assertFalse("48kHz 不应判定为高解析", below!!.isHiRes)
    }

    @Test
    fun detects_dsd_and_ape_as_lossless() {
        for (codec in listOf("flac", "alac", "ape", "wavpack", "tta", "tak", "wav")) {
            val p = AudioParams.fromJson("""{"samplerate":96000,"channels":2,"format":"s32","codec":"$codec"}""")
            assertTrue("$codec 应判定为无损", p!!.isLossless)
        }
    }

    @Test
    fun detects_downsampling() {
        val p = AudioParams.fromJson("""{"samplerate":48000,"channels":2,"format":"s16","codec":"flac"}""")!!
        assertTrue("播放 96k 却读到 48k，应报降采样", p.seemsDownsampled(expected = 96000))
        assertFalse("播放 48k 读到 48k，不应报降采样", p.seemsDownsampled(expected = 48000))
    }

    @Test
    fun malformed_json_returns_null_instead_of_throwing() {
        assertNull(AudioParams.fromJson(""))
        assertNull(AudioParams.fromJson("not json at all"))
        assertNull(AudioParams.fromJson("{}"))
        assertNull(AudioParams.fromJson("""{"channels":2}""")) // 缺 samplerate
    }

    @Test
    fun tolerates_whitespace_in_json() {
        val json = """{ "samplerate" : 96000 , "channels" : 2 , "format" : "s32" , "codec" : "flac" }"""
        val p = AudioParams.fromJson(json)
        assertEquals(96000, p?.samplerate)
        assertEquals("flac", p?.codec)
    }

    @Test
    fun tolerates_extra_unknown_fields() {
        val json = """{"samplerate":96000,"channels":2,"format":"s32","codec":"flac","extra":"ignored"}"""
        val p = AudioParams.fromJson(json)
        assertEquals(96000, p?.samplerate)
    }

    @Test
    fun handles_negative_samplerate_gracefully() {
        // 理论上不该出现，但不能崩
        val p = AudioParams.fromJson("""{"samplerate":-1,"channels":0,"format":"","codec":""}""")
        assertEquals(-1, p?.samplerate)
        assertFalse(p!!.isHiRes)
    }
}

/**
 * 事件类型编码契约测试。
 *
 * **这些数值必须与 Rust 侧 `yan-engine-core/src/bridge.rs` 的 `EventType`
 * 完全一致。** Rust 侧有对称的测试 `event_type_encoding_is_stable`。
 * 两边都改才生效 —— 只改一边会被 CI 拦下。
 */
class EventTypeContractTest {

    @Test
    fun event_type_values_match_rust() {
        assertEquals(1, MpvEventType.TIME_UPDATE)
        assertEquals(2, MpvEventType.DURATION_CHANGE)
        assertEquals(3, MpvEventType.STATE_CHANGE)
        assertEquals(4, MpvEventType.PLAYBACK_END)
        assertEquals(5, MpvEventType.FILE_LOADED)
        assertEquals(6, MpvEventType.IDLE)
        assertEquals(7, MpvEventType.ERROR)
        assertEquals(8, MpvEventType.LOG_MESSAGE)
    }

    @Test
    fun event_types_are_unique() {
        val all = listOf(
            MpvEventType.TIME_UPDATE,
            MpvEventType.DURATION_CHANGE,
            MpvEventType.STATE_CHANGE,
            MpvEventType.PLAYBACK_END,
            MpvEventType.FILE_LOADED,
            MpvEventType.IDLE,
            MpvEventType.ERROR,
            MpvEventType.LOG_MESSAGE,
        )
        assertEquals("事件编号不得重复", all.size, all.toSet().size)
    }
}

/**
 * 引擎未初始化时的行为测试。
 *
 * 不需要 native 库 —— 未初始化时所有 native 调用都应被 Kotlin 层
 * 短路，返回安全默认值，而不是抛 UnsatisfiedLinkError。
 */
class MpvEngineUninitializedTest {

    @Test
    fun getters_return_safe_defaults_before_init() {
        assertFalse(MpvEngine.isInitialized)
        assertEquals(0L, MpvEngine.getTimePosMs())
        assertEquals(0L, MpvEngine.getDurationMs())
        assertFalse(MpvEngine.isPaused())
        assertTrue("未初始化应视为空闲", MpvEngine.isIdle())
        assertEquals(0.0, MpvEngine.getVolume(), 1e-9)
        assertEquals(0f, MpvEngine.getProgress(), 1e-9f)
        assertNull(MpvEngine.getAudioParams())
        assertNull(MpvEngine.getProperty("anything"))
        assertNull(MpvEngine.engineVersion())
    }

    @Test
    fun control_methods_fail_gracefully_before_init() {
        // 这些应返回 false 并设置 lastError，而不是抛异常
        assertFalse(MpvEngine.load("file:///nope.flac"))
        assertFalse(MpvEngine.togglePause())
        assertFalse(MpvEngine.stop())
        assertFalse(MpvEngine.setVolume(50.0))
        assertFalse(MpvEngine.setPaused(true))
        assertFalse(MpvEngine.seekTo(10.0))
        assertFalse(MpvEngine.setAudioFilters("equalizer=f=100:t=q:w=1:g=3"))
        assertEquals("引擎未初始化 —— 请先调用 create()", MpvEngine.lastError)
    }

    @Test
    fun probe_returns_null_when_library_missing() {
        // 未加载库时探测应返回 null（或负值被转换）
        val result = try {
            MpvEngine.probeLibmpvApiVersion("definitely-not-a-real-lib.so")
        } catch (t: UnsatisfiedLinkError) {
            null // 库完全没加载时抛错也可接受
        }
        assertNull("不存在的库应探测失败", result)
    }
}
