package com.jacobusoettle.satmap.app

import android.content.Context
import android.content.SharedPreferences
import android.os.Bundle

/** iOS `UserDefaults`, with the same keys: the app's SharedPreferences, plus the launch extras read before them and
 *  never written back (iOS's argument domain, e.g. `-terrainMode colours`). On Android the extras come from
 *  `adb shell am start -n com.jacobusoettle.satmap/.MainActivity --es terrainMode colours --ez zoomScript true`.
 *
 *  Keys: terrainMode, showContours (legacy, read only), distanceUnit, heightUnit, kmlFileColours, offlineMaxZoom,
 *  camera ("lat,lon,latDelta,lonDelta"), zoomScript (launch extra only), and Android's locationPermissionAsked,
 *  motionPermissionAsked and notificationPermissionAsked (Android can't tell "never asked" from "denied"). */
object Defaults {
    lateinit var prefs: SharedPreferences
        private set

    /** The launch extras: read first, never written. */
    private var arguments: Bundle? = null

    fun init(context: Context) {
        prefs = context.getSharedPreferences("${context.packageName}_preferences", Context.MODE_PRIVATE)
    }

    fun setLaunchArguments(extras: Bundle?) {
        arguments = extras?.let { Bundle(it) }
    }

    private fun argument(key: String): Any? {
        val b = arguments ?: return null
        if (!b.containsKey(key)) return null
        @Suppress("DEPRECATION")
        return b.get(key)
    }

    /** The value, or null when unset (or set as another type). */
    fun string(key: String): String? {
        (argument(key) as? String)?.let { return it }
        return try {
            prefs.getString(key, null)
        } catch (e: ClassCastException) {
            null
        }
    }

    fun bool(key: String): Boolean? {
        when (val a = argument(key)) {
            is Boolean -> return a
            is String -> a.toBooleanStrictOrNull()?.let { return it }
        }
        if (!prefs.contains(key)) return null
        return try {
            prefs.getBoolean(key, false)
        } catch (e: ClassCastException) {
            null
        }
    }

    fun int(key: String): Int? {
        when (val a = argument(key)) {
            is Int -> return a
            is String -> a.toIntOrNull()?.let { return it }
        }
        if (!prefs.contains(key)) return null
        return try {
            prefs.getInt(key, 0)
        } catch (e: ClassCastException) {
            null
        }
    }

    fun putString(key: String, v: String) = prefs.edit().putString(key, v).apply()

    fun putBoolean(key: String, v: Boolean) = prefs.edit().putBoolean(key, v).apply()

    fun putInt(key: String, v: Int) = prefs.edit().putInt(key, v).apply()
}
