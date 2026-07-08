package com.x3cycles

import android.content.Context
import android.os.Build

/**
 * Tiny persistent store — no settings menu. Remembers the high score, the best
 * level reached, and whether to render side-by-side for the glasses.
 */
class SettingsStore(context: Context) {
    private val p = context.getSharedPreferences("x3cycles", Context.MODE_PRIVATE)

    private val deviceText = listOf(
        Build.MODEL, Build.MANUFACTURER, Build.BRAND, Build.DEVICE, Build.PRODUCT
    ).joinToString(" ").lowercase()

    // FABLE_X3_STARTER_GUIDE gotcha #24: the X3 Pro reports Build.MODEL=ARGF20;
    // detect RayNeo hardware by manufacturer/brand/product instead.
    val isRayNeoX3 =
        "rayneo" in deviceText || "leiniao" in deviceText || "ffalcon" in deviceText ||
            ("x3" in deviceText && ("tcl" in deviceText || "falcon" in deviceText))

    val sbs get() = isRayNeoX3 // side-by-side for the glasses, single view elsewhere

    var highScore: Int
        get() = p.getInt("hi", 0)
        set(v) { if (v > highScore) p.edit().putInt("hi", v).apply() }

    var bestLevel: Int
        get() = p.getInt("bestLevel", 1)
        set(v) { if (v > bestLevel) p.edit().putInt("bestLevel", v).apply() }

    var games: Int
        get() = p.getInt("games", 0)
        set(v) { p.edit().putInt("games", v).apply() }
}
