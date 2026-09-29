package com.bellingham.optimizer

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

data class GameProfile(
    val name: String,
    val resolution: String,
    val refreshHz: String,
    val downscale: String,
    val fps: String,
    val animOff: Boolean,
    val cleanRam: Boolean
)

object ProfileStorage {
    private const val PREFS = "victory_hard_profiles"
    private const val KEY = "profiles"

    fun getAll(context: Context): List<GameProfile> {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val raw = prefs.getString(KEY, "[]") ?: "[]"
        val arr = JSONArray(raw)
        val list = mutableListOf<GameProfile>()
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            list.add(
                GameProfile(
                    o.getString("name"), o.getString("resolution"), o.getString("refreshHz"),
                    o.getString("downscale"), o.getString("fps"),
                    o.getBoolean("animOff"), o.getBoolean("cleanRam")
                )
            )
        }
        return list
    }

    private fun writeAll(context: Context, list: List<GameProfile>) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val arr = JSONArray()
        for (p in list) {
            val o = JSONObject()
            o.put("name", p.name); o.put("resolution", p.resolution); o.put("refreshHz", p.refreshHz)
            o.put("downscale", p.downscale); o.put("fps", p.fps)
            o.put("animOff", p.animOff); o.put("cleanRam", p.cleanRam)
            arr.put(o)
        }
        prefs.edit().putString(KEY, arr.toString()).apply()
    }

    fun save(context: Context, profile: GameProfile) {
        val current = getAll(context).filter { it.name != profile.name }.toMutableList()
        current.add(profile)
        writeAll(context, current)
    }

    fun delete(context: Context, name: String) {
        writeAll(context, getAll(context).filter { it.name != name })
    }

    fun buildApplyCommand(p: GameProfile): String {
        val parts = mutableListOf<String>()
        parts.add(
            when (p.resolution) {
                "720x1280" -> "wm size 720x1280"
                "540x960" -> "wm size 540x960"
                "480x854" -> "wm size 480x854"
                else -> "wm size reset"
            }
        )
        if (p.refreshHz == "auto") {
            parts.add("settings delete system min_refresh_rate")
            parts.add("settings delete system peak_refresh_rate")
        } else {
            parts.add("settings put system min_refresh_rate ${p.refreshHz}.0")
            parts.add("settings put system peak_refresh_rate ${p.refreshHz}.0")
        }
        if (p.animOff) {
            parts.add("settings put global window_animation_scale 0")
            parts.add("settings put global transition_animation_scale 0")
            parts.add("settings put global animator_duration_scale 0")
        } else {
            parts.add("settings put global window_animation_scale 1")
            parts.add("settings put global transition_animation_scale 1")
            parts.add("settings put global animator_duration_scale 1")
        }
        if (p.cleanRam) {
            parts.add("for p in \$(pm list packages -3 | sed 's/package://'); do am force-stop \$p; done")
        }
        return parts.joinToString("; ")
    }

    fun buildGameModeCommand(pkg: String, p: GameProfile): String {
        return "cmd game mode performance $pkg; cmd game set --mode performance --downscale ${p.downscale} --fps ${p.fps} $pkg"
    }

    const val NORMAL_COMMAND = "wm size reset; settings delete system min_refresh_rate; settings delete system peak_refresh_rate; settings put global window_animation_scale 1; settings put global transition_animation_scale 1; settings put global animator_duration_scale 1"
}
