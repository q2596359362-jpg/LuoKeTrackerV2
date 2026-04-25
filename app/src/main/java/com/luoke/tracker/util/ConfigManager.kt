package com.luoke.tracker.util

import android.content.Context
import android.content.SharedPreferences

object ConfigManager {
    private const val PREFS_NAME = "luoke_tracker_prefs"
    private const val KEY_MINIMAP_X = "minimap_x"
    private const val KEY_MINIMAP_Y = "minimap_y"
    private const val KEY_MINIMAP_W = "minimap_w"
    private const val KEY_MINIMAP_H = "minimap_h"
    private const val KEY_MAP_PATH = "map_path"
    private const val KEY_CONFIDENCE = "confidence_threshold"
    private const val KEY_AUTO_DETECT = "auto_detect"
    private const val KEY_FLOATING_EXPANDED = "floating_expanded"

    private fun prefs(context: Context): SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun getMinimapRect(context: Context): IntArray {
        val p = prefs(context)
        return intArrayOf(
            p.getInt(KEY_MINIMAP_X, 0),
            p.getInt(KEY_MINIMAP_Y, 0),
            p.getInt(KEY_MINIMAP_W, 200),
            p.getInt(KEY_MINIMAP_H, 200)
        )
    }

    fun setMinimapRect(context: Context, x: Int, y: Int, w: Int, h: Int) {
        prefs(context).edit().apply {
            putInt(KEY_MINIMAP_X, x)
            putInt(KEY_MINIMAP_Y, y)
            putInt(KEY_MINIMAP_W, w)
            putInt(KEY_MINIMAP_H, h)
            apply()
        }
    }

    fun getMapFilePath(context: Context): String =
        prefs(context).getString(KEY_MAP_PATH, "") ?: ""

    fun setMapFilePath(context: Context, path: String) {
        prefs(context).edit().putString(KEY_MAP_PATH, path).apply()
    }

    fun getConfidenceThreshold(context: Context): Float =
        prefs(context).getFloat(KEY_CONFIDENCE, 0.3f)

    fun setConfidenceThreshold(context: Context, threshold: Float) {
        prefs(context).edit().putFloat(KEY_CONFIDENCE, threshold).apply()
    }

    fun isAutoDetectEnabled(context: Context): Boolean =
        prefs(context).getBoolean(KEY_AUTO_DETECT, false)

    fun setAutoDetectEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_AUTO_DETECT, enabled).apply()
    }

    fun isFloatingExpanded(context: Context): Boolean =
        prefs(context).getBoolean(KEY_FLOATING_EXPANDED, true)

    fun setFloatingExpanded(context: Context, expanded: Boolean) {
        prefs(context).edit().putBoolean(KEY_FLOATING_EXPANDED, expanded).apply()
    }
}