package com.skgram.core

import com.skgram.i18n.I18n
import com.skgram.i18n.Strings
import dev.g000sha256.tdl.dto.CallDiscardReasonMissed
import dev.g000sha256.tdl.dto.MessageAnimation
import dev.g000sha256.tdl.dto.Message
import dev.g000sha256.tdl.dto.MessageAudio
import dev.g000sha256.tdl.dto.MessageCall
import dev.g000sha256.tdl.dto.MessageContent
import dev.g000sha256.tdl.dto.MessageDocument
import dev.g000sha256.tdl.dto.MessagePhoto
import dev.g000sha256.tdl.dto.MessageSender
import dev.g000sha256.tdl.dto.MessageSenderChat
import dev.g000sha256.tdl.dto.MessageSenderUser
import dev.g000sha256.tdl.dto.MessageSticker
import dev.g000sha256.tdl.dto.MessageText
import dev.g000sha256.tdl.dto.MessageVideo
import dev.g000sha256.tdl.dto.MessageVoiceNote
import dev.g000sha256.tdl.dto.User
import java.text.DateFormat
import java.util.Calendar
import java.util.Date

/** Text of a message if it has any (text body or media caption). */
fun MessageContent.text(): String? = when (this) {
    is MessageText -> text.text
    is MessagePhoto -> caption.text.ifEmpty { null }
    is MessageVideo -> caption.text.ifEmpty { null }
    is MessageDocument -> caption.text.ifEmpty { null }
    is MessageAnimation -> caption.text.ifEmpty { null }
    is MessageVoiceNote -> caption.text.ifEmpty { null }
    is MessageAudio -> caption.text.ifEmpty { null }
    else -> null
}

fun duration(sec: Int) = "%d:%02d".format(sec / 60, sec % 60)

/** One-line summary for the chat list, notifications and reply previews. */
fun MessageContent.preview(s: Strings = I18n.strings, outgoing: Boolean = false): String {
    val kind = when (this) {
        is MessageText -> return text.text.replace('\n', ' ')
        is MessagePhoto -> "🖼 " + s.msgPhoto
        is MessageVideo -> "🎬 " + s.msgVideo
        is MessageSticker -> "${sticker.emoji} ${s.msgSticker}"
        is MessageVoiceNote -> "🎤 ${s.msgVoice} ${duration(voiceNote.duration)}"
        is MessageAudio -> "🎵 " + listOf(audio.performer, audio.title).filter { it.isNotEmpty() }.joinToString(" – ").ifEmpty { s.msgAudio }
        is MessageDocument -> "📎 " + document.fileName.ifEmpty { s.msgDocument }
        is MessageAnimation -> s.msgAnimation
        is MessageCall -> {
            val label = when {
                discardReason is CallDiscardReasonMissed -> s.msgCallMissed
                outgoing -> s.msgCallOut
                else -> s.msgCallIn
            }
            "📞 " + if (duration > 0) "$label ${duration(duration)}" else label
        }
        else -> s.msgUnsupported
    }
    val caption = text()
    return if (caption != null) "$kind · ${caption.replace('\n', ' ')}" else kind
}

fun Message.preview(s: Strings = I18n.strings): String = content.preview(s, isOutgoing)

fun User.displayName(): String = listOf(firstName, lastName).filter { it.isNotEmpty() }.joinToString(" ").ifEmpty { "Deleted Account" }

fun Account.senderName(sender: MessageSender?): String? = when (sender) {
    is MessageSenderUser -> users[sender.userId]?.displayName()
    is MessageSenderChat -> chat(sender.chatId)?.title
    else -> null
}

fun senderKey(sender: MessageSender?): Long = when (sender) {
    is MessageSenderUser -> sender.userId
    is MessageSenderChat -> sender.chatId
    else -> 0
}

private fun cal(unixSeconds: Int) = Calendar.getInstance().apply { timeInMillis = unixSeconds * 1000L }

fun sameDay(a: Int, b: Int): Boolean {
    val ca = cal(a); val cb = cal(b)
    return ca.get(Calendar.YEAR) == cb.get(Calendar.YEAR) && ca.get(Calendar.DAY_OF_YEAR) == cb.get(Calendar.DAY_OF_YEAR)
}

fun clockTime(unixSeconds: Int): String =
    DateFormat.getTimeInstance(DateFormat.SHORT, I18n.lang.locale).format(Date(unixSeconds * 1000L))

fun dayLabel(unixSeconds: Int): String =
    DateFormat.getDateInstance(DateFormat.LONG, I18n.lang.locale).format(Date(unixSeconds * 1000L))

/** Time for today, weekday this week, date otherwise. */
fun shortTime(unixSeconds: Int): String {
    if (unixSeconds == 0) return ""
    val now = (System.currentTimeMillis() / 1000).toInt()
    val locale = I18n.lang.locale
    val date = Date(unixSeconds * 1000L)
    return when {
        sameDay(now, unixSeconds) -> clockTime(unixSeconds)
        now - unixSeconds < 6 * 86400 -> java.text.SimpleDateFormat("EEE", locale).format(date)
        else -> DateFormat.getDateInstance(DateFormat.SHORT, locale).format(date)
    }
}
