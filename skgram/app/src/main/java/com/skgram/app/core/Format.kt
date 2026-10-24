package com.skgram.app.core

import android.content.Context
import android.text.format.DateFormat
import android.text.format.DateUtils
import com.skgram.app.R
import dev.g000sha256.tdl.dto.MessageAnimation
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
import java.util.Date

/** Text of a message if it has any (text body or media caption). */
fun MessageContent.text(): String? = when (this) {
    is MessageText -> text.text
    is MessagePhoto -> caption.text.ifEmpty { null }
    is MessageVideo -> caption.text.ifEmpty { null }
    is MessageDocument -> caption.text.ifEmpty { null }
    is MessageAnimation -> caption.text.ifEmpty { null }
    is MessageVoiceNote -> caption.text.ifEmpty { null }
    else -> null
}

/** One-line summary for the chat list and reply previews. */
fun MessageContent.preview(ctx: Context): String {
    val kind = when (this) {
        is MessageText -> return text.text.replace('\n', ' ')
        is MessagePhoto -> ctx.getString(R.string.msg_photo)
        is MessageVideo -> ctx.getString(R.string.msg_video)
        is MessageSticker -> "${sticker.emoji} ${ctx.getString(R.string.msg_sticker)}"
        is MessageVoiceNote -> ctx.getString(R.string.msg_voice)
        is MessageDocument -> "📎 " + document.fileName.ifEmpty { ctx.getString(R.string.msg_document) }
        is MessageAnimation -> ctx.getString(R.string.msg_animation)
        else -> ctx.getString(R.string.msg_unsupported)
    }
    val caption = text()
    return if (caption != null) "$kind · ${caption.replace('\n', ' ')}" else kind
}

fun User.displayName(): String = listOf(firstName, lastName).filter { it.isNotEmpty() }.joinToString(" ").ifEmpty { "Deleted Account" }

fun senderName(sender: MessageSender?): String? = when (sender) {
    is MessageSenderUser -> Tg.users[sender.userId]?.displayName()
    is MessageSenderChat -> Tg.chat(sender.chatId)?.title
    else -> null
}

fun senderKey(sender: MessageSender?): Long = when (sender) {
    is MessageSenderUser -> sender.userId
    is MessageSenderChat -> sender.chatId
    else -> 0
}

fun shortTime(ctx: Context, unixSeconds: Int): String {
    if (unixSeconds == 0) return ""
    val ms = unixSeconds * 1000L
    return when {
        DateUtils.isToday(ms) -> DateFormat.getTimeFormat(ctx).format(Date(ms))
        System.currentTimeMillis() - ms < 6 * DateUtils.DAY_IN_MILLIS ->
            DateUtils.formatDateTime(ctx, ms, DateUtils.FORMAT_SHOW_WEEKDAY or DateUtils.FORMAT_ABBREV_WEEKDAY)
        else -> DateUtils.formatDateTime(ctx, ms, DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_NUMERIC_DATE)
    }
}

fun clockTime(ctx: Context, unixSeconds: Int): String = DateFormat.getTimeFormat(ctx).format(Date(unixSeconds * 1000L))

fun dayLabel(ctx: Context, unixSeconds: Int): String =
    DateUtils.formatDateTime(ctx, unixSeconds * 1000L, DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_SHOW_YEAR)
