package com.skgram.i18n

import androidx.compose.runtime.staticCompositionLocalOf
import java.util.Locale

/** The UI languages. [code] is also what Telegram's translator expects. */
enum class Lang(val tag: String, val label: String, val code: String, internal val strings: Strings) {
    ZH_CN("zh-CN", "简体中文", "zh-CN", com.skgram.i18n.ZH_CN),
    ZH_TW("zh-TW", "繁體中文", "zh-TW", com.skgram.i18n.ZH_TW),
    KO("ko", "한국어", "ko", com.skgram.i18n.KO),
    JA("ja", "日本語", "ja", com.skgram.i18n.JA),
    EN("en", "English", "en", com.skgram.i18n.EN);

    val locale: Locale get() = Locale.forLanguageTag(tag)

    companion object {
        fun byTag(tag: String?): Lang? = entries.firstOrNull { it.tag == tag }

        /** Best match for a system locale; Chinese picks the script/region, anything unknown falls back to English. */
        fun forLocale(l: Locale): Lang = when (l.language) {
            "zh" -> if (l.script == "Hant" || l.country in setOf("TW", "HK", "MO")) ZH_TW else ZH_CN
            "ko" -> KO
            "ja" -> JA
            else -> EN
        }
    }
}

val LocalStrings = staticCompositionLocalOf { Lang.EN.strings }

/** Fills %1$s-style placeholders. */
fun String.fmt(vararg args: Any?): String = String.format(this, *args)

/** The language in effect: the user's choice, or the closest match to the system's. */
object I18n {
    val lang: Lang get() = com.skgram.core.Prefs.lang.value ?: Lang.forLocale(com.skgram.core.Platform.systemLocale)
    val strings: Strings get() = lang.strings
}
