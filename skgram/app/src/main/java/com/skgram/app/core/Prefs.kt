package com.skgram.app.core

import android.content.Context
import android.content.SharedPreferences
import com.skgram.app.BuildConfig
import com.skgram.app.ui.theme.SkTheme
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** App settings, exposed as flows so the UI recomposes when they change. */
object Prefs {
    private lateinit var sp: SharedPreferences

    private val _theme = MutableStateFlow(SkTheme.SYSTEM)
    val theme: StateFlow<SkTheme> = _theme
    private val _ghost = MutableStateFlow(false)
    /** Ghost mode: never send read receipts or an online status. */
    val ghost: StateFlow<Boolean> = _ghost
    private val _showIds = MutableStateFlow(false)
    val showIds: StateFlow<Boolean> = _showIds
    private val _doubleTapReply = MutableStateFlow(true)
    val doubleTapReply: StateFlow<Boolean> = _doubleTapReply

    fun init(context: Context) {
        sp = context.getSharedPreferences("skgram", Context.MODE_PRIVATE)
        _theme.value = runCatching { SkTheme.valueOf(sp.getString("theme", null)!!) }.getOrDefault(SkTheme.SYSTEM)
        _ghost.value = sp.getBoolean("ghost", false)
        _showIds.value = sp.getBoolean("show_ids", false)
        _doubleTapReply.value = sp.getBoolean("double_tap_reply", true)
    }

    fun setTheme(t: SkTheme) { _theme.value = t; sp.edit().putString("theme", t.name).apply() }
    fun setGhost(v: Boolean) { _ghost.value = v; sp.edit().putBoolean("ghost", v).apply() }
    fun setShowIds(v: Boolean) { _showIds.value = v; sp.edit().putBoolean("show_ids", v).apply() }
    fun setDoubleTapReply(v: Boolean) { _doubleTapReply.value = v; sp.edit().putBoolean("double_tap_reply", v).apply() }

    /** Credentials from the build take precedence; otherwise the ones the user entered. */
    val apiId: Int get() = BuildConfig.TG_API_ID.takeIf { it != 0 } ?: sp.getInt("api_id", 0)
    val apiHash: String get() = BuildConfig.TG_API_HASH.ifEmpty { sp.getString("api_hash", "") ?: "" }
    val hasApiCredentials get() = apiId != 0 && apiHash.isNotEmpty()
    val apiCredentialsBuiltIn get() = BuildConfig.TG_API_ID != 0 && BuildConfig.TG_API_HASH.isNotEmpty()

    fun setApiCredentials(id: Int, hash: String) {
        sp.edit().putInt("api_id", id).putString("api_hash", hash).apply()
    }

    fun clearApiCredentials() {
        sp.edit().remove("api_id").remove("api_hash").commit()
    }
}
