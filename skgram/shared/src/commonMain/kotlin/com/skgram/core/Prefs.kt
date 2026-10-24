package com.skgram.core

import com.skgram.i18n.Lang
import com.skgram.ui.theme.SkTheme
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** App settings, exposed as flows so the UI recomposes when they change. */
object Prefs {
    private val store get() = Platform.store

    /** Telegram API credentials baked in at build time, if any. */
    var builtInApiId = 0
    var builtInApiHash = ""

    private val _theme = MutableStateFlow(SkTheme.SYSTEM)
    val theme: StateFlow<SkTheme> = _theme
    private val _lang = MutableStateFlow<Lang?>(null)
    /** Chosen UI language, or null to follow the system. */
    val lang: StateFlow<Lang?> = _lang
    private val _ghost = MutableStateFlow(false)
    /** Ghost mode: never send read receipts or an online status. */
    val ghost: StateFlow<Boolean> = _ghost
    private val _showIds = MutableStateFlow(false)
    val showIds: StateFlow<Boolean> = _showIds
    private val _doubleTapReply = MutableStateFlow(true)
    val doubleTapReply: StateFlow<Boolean> = _doubleTapReply
    private val _notifications = MutableStateFlow(true)
    val notifications: StateFlow<Boolean> = _notifications
    private val _background = MutableStateFlow(true)
    val background: StateFlow<Boolean> = _background

    fun load() {
        _theme.value = runCatching { SkTheme.valueOf(store.get("theme")!!) }.getOrDefault(SkTheme.SYSTEM)
        _lang.value = Lang.byTag(store.get("lang"))
        _ghost.value = bool("ghost", false)
        _showIds.value = bool("show_ids", false)
        _doubleTapReply.value = bool("double_tap_reply", true)
        _notifications.value = bool("notifications", true)
        _background.value = bool("background", true)
    }

    private fun bool(key: String, default: Boolean) = store.get(key)?.toBooleanStrictOrNull() ?: default

    fun setTheme(t: SkTheme) { _theme.value = t; store.put("theme", t.name) }
    fun setLang(l: Lang?) { _lang.value = l; store.put("lang", l?.tag) }
    fun setGhost(v: Boolean) { _ghost.value = v; store.put("ghost", v.toString()) }
    fun setShowIds(v: Boolean) { _showIds.value = v; store.put("show_ids", v.toString()) }
    fun setDoubleTapReply(v: Boolean) { _doubleTapReply.value = v; store.put("double_tap_reply", v.toString()) }
    fun setNotifications(v: Boolean) { _notifications.value = v; store.put("notifications", v.toString()) }
    fun setBackground(v: Boolean) { _background.value = v; store.put("background", v.toString()); Platform.updateBackgroundMode(v) }

    /** Credentials from the build take precedence; otherwise the ones the user entered. */
    val apiId: Int get() = builtInApiId.takeIf { it != 0 } ?: store.get("api_id")?.toIntOrNull() ?: 0
    val apiHash: String get() = builtInApiHash.ifEmpty { store.get("api_hash") ?: "" }
    val hasApiCredentials get() = apiId != 0 && apiHash.isNotEmpty()
    val apiCredentialsBuiltIn get() = builtInApiId != 0 && builtInApiHash.isNotEmpty()

    fun setApiCredentials(id: Int, hash: String) { store.put("api_id", id.toString()); store.put("api_hash", hash) }
    fun clearApiCredentials() { store.put("api_id", null); store.put("api_hash", null) }

    var accountIds: List<Int>
        get() = store.get("accounts")?.split(',')?.mapNotNull { it.toIntOrNull() } ?: emptyList()
        set(v) = store.put("accounts", v.joinToString(","))
    var currentAccountId: Int
        get() = store.get("current_account")?.toIntOrNull() ?: 0
        set(v) = store.put("current_account", v.toString())
}
