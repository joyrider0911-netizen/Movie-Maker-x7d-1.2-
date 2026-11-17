package com.moviemaker.x7d.model

/**
 * Strict hardware & export caps tuned for HONOR X7d 4G
 * (Qualcomm Snapdragon 685, Adreno 610 GPU, 720x1610 HD+ display, 8GB RAM).
 */
object HardwareCaps {
    // 540p (16:9) proxy preview guarantees smooth multi-track scrubbing on Adreno 610
    const val PREVIEW_PROXY_WIDTH = 960
    const val PREVIEW_PROXY_HEIGHT = 540

    // Strict snap threshold in microseconds for beat marker alignment
    const val MAGNETIC_SNAP_THRESHOLD_US = 120_000L // 120ms

    // Public MediaStore relative export path
    const val EXPORT_RELATIVE_DIR = "Movies/MovieMakerX7d"

    // Audio waveform extraction resolution (buckets per second of audio)
    const val WAVEFORM_SAMPLE_INTERVAL_MS = 50
}

/**
 * Frame rate selector strictly locked to 24 / 25 / 30 FPS (No 60 FPS).
 */
enum class ExportFrameRate(val fps: Int, val label: String) {
    FPS_24(24, "24 FPS (Cinema)"),
    FPS_25(25, "25 FPS (PAL)"),
    FPS_30(30, "30 FPS (Standard)")
}

/**
 * Output resolution capped at 1080p max (plus 720p fast-export option).
 */
enum class ExportResolution(
    val width: Int,
    val height: Int,
    val targetBitrateBps: Int,
    val label: String
) {
    HD_720P(1280, 720, 6_000_000, "720p HD"),
    FHD_1080P(1920, 1080, 12_000_000, "1080p Full HD")
}