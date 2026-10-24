package com.skgram.app.ui.screens

import android.content.Context
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.appcompat.app.AppCompatDelegate
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.skgram.app.R
import com.skgram.app.core.MessageEvent
import com.skgram.app.core.Prefs
import com.skgram.app.core.Tg
import com.skgram.app.core.clockTime
import com.skgram.app.core.dayLabel
import com.skgram.app.core.getOrNull
import com.skgram.app.core.getOrThrow
import com.skgram.app.core.preview
import com.skgram.app.core.senderKey
import com.skgram.app.core.senderName
import com.skgram.app.core.text
import com.skgram.app.ui.components.Avatar
import com.skgram.app.ui.components.SkTopBar
import com.skgram.app.ui.components.StripedBackground
import com.skgram.app.ui.components.TdImage
import com.skgram.app.ui.theme.LocalSkColors
import com.skgram.app.ui.theme.SkewShape
import dev.g000sha256.tdl.dto.ChatTypeBasicGroup
import dev.g000sha256.tdl.dto.ChatTypePrivate
import dev.g000sha256.tdl.dto.ChatTypeSupergroup
import dev.g000sha256.tdl.dto.FormattedText
import dev.g000sha256.tdl.dto.InputMessageReplyToMessage
import dev.g000sha256.tdl.dto.InputMessageText
import dev.g000sha256.tdl.dto.Message
import dev.g000sha256.tdl.dto.MessageAnimation
import dev.g000sha256.tdl.dto.MessageDocument
import dev.g000sha256.tdl.dto.MessagePhoto
import dev.g000sha256.tdl.dto.MessageReplyToMessage
import dev.g000sha256.tdl.dto.MessageSendingStateFailed
import dev.g000sha256.tdl.dto.MessageSendingStatePending
import dev.g000sha256.tdl.dto.MessageSenderUser
import dev.g000sha256.tdl.dto.MessageSticker
import dev.g000sha256.tdl.dto.MessageText
import dev.g000sha256.tdl.dto.MessageVideo
import dev.g000sha256.tdl.dto.MessageVoiceNote
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.launch
import java.util.Calendar

/** Messages of one open chat, newest first. */
private class ChatState(val chatId: Long, private val scope: CoroutineScope) {
    val messages = mutableStateListOf<Message>()
    val replies = mutableStateMapOf<Long, Message>()
    val translations = mutableStateMapOf<Long, String>()
    var loading by mutableStateOf(false)
    private var reachedStart = false

    suspend fun loadOlder() {
        if (loading || reachedStart) return
        loading = true
        try {
            var from = messages.lastOrNull()?.id ?: 0L
            var got = 0
            // TDLib may hand back only a few cached messages per call; keep asking until a screenful arrives.
            while (got < 30) {
                val batch = Tg.client.getChatHistory(chatId, from, 0, 50, false).getOrNull()?.messages?.filterNotNull() ?: break
                val fresh = batch.filter { m -> messages.none { it.id == m.id } }
                if (batch.isEmpty()) { reachedStart = true; break }
                messages.addAll(fresh)
                got += fresh.size
                from = batch.last().id
                if (fresh.isEmpty()) break
            }
            markViewed(messages.take(50).map { it.id })
        } finally {
            loading = false
        }
    }

    fun onEvent(e: MessageEvent) {
        when (e) {
            is MessageEvent.New -> if (messages.none { it.id == e.message.id }) {
                messages.add(0, e.message)
                markViewed(listOf(e.message.id))
            }
            is MessageEvent.Replaced -> {
                val i = messages.indexOfFirst { it.id == e.oldId }
                if (i >= 0) messages[i] = e.message
            }
            is MessageEvent.Deleted -> messages.removeAll { it.id in e.ids }
            is MessageEvent.Content -> {
                val i = messages.indexOfFirst { it.id == e.id }
                if (i >= 0) {
                    // DTOs are immutable; refetch the edited message.
                    scope.launch { Tg.client.getMessage(chatId, e.id).getOrNull()?.let { m -> messages.indexOfFirst { it.id == e.id }.takeIf { it >= 0 }?.let { messages[it] = m } } }
                    translations.remove(e.id)
                }
            }
        }
    }

    fun markViewed(ids: List<Long>) {
        if (Prefs.ghost.value || ids.isEmpty()) return
        scope.launch { Tg.client.viewMessages(chatId, ids.toLongArray(), null, true) }
    }

    fun replyTarget(m: Message): Message? {
        val r = m.replyTo as? MessageReplyToMessage ?: return null
        messages.firstOrNull { it.id == r.messageId }?.let { return it }
        replies[r.messageId]?.let { return it }
        if (r.chatId == chatId && r.messageId != 0L) scope.launch {
            Tg.client.getMessage(r.chatId, r.messageId).getOrNull()?.let { replies[r.messageId] = it }
        }
        return null
    }
}

/** Telegram's translation language codes for the app's current language. */
private fun targetLanguage(): String {
    val tag = AppCompatDelegate.getApplicationLocales()[0] ?: java.util.Locale.getDefault()
    return when {
        tag.language != "zh" -> tag.language
        tag.country == "TW" || tag.country == "HK" || tag.script == "Hant" -> "zh-TW"
        else -> "zh-CN"
    }
}

private fun toast(ctx: Context, text: String) = Toast.makeText(ctx, text, Toast.LENGTH_SHORT).show()

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ChatScreen(chatId: Long, onBack: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val state = remember(chatId) { ChatState(chatId, scope) }
    val chats by Tg.chatList.collectAsState()
    val chat = remember(chats, chatId) { chats.firstOrNull { it.id == chatId } ?: Tg.chat(chatId) }
    val ghost by Prefs.ghost.collectAsState()
    val showIds by Prefs.showIds.collectAsState()
    val doubleTapReply by Prefs.doubleTapReply.collectAsState()
    Tg.userVersion.collectAsState().value // re-read sender names when users arrive
    val listState = rememberLazyListState()
    var input by rememberSaveable(chatId) { mutableStateOf("") }
    var replyTo by remember { mutableStateOf<Message?>(null) }
    val isGroup = chat?.type is ChatTypeBasicGroup || (chat?.type as? ChatTypeSupergroup)?.isChannel == false

    BackHandler(onBack = onBack)
    DisposableEffect(chatId) {
        if (!Prefs.ghost.value) scope.launch { Tg.client.openChat(chatId) }
        onDispose { Tg.scope.launch { Tg.client.closeChat(chatId) } }
    }
    LaunchedEffect(chatId) { launch { Tg.messages.filter { it.chatId == chatId }.collect(state::onEvent) }; state.loadOlder() }
    LaunchedEffect(listState) {
        snapshotFlow { listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0 }
            .distinctUntilChanged()
            .filter { it >= state.messages.size - 10 }
            .collect { state.loadOlder() }
    }
    // Keep the newest message in view when one arrives while the user is at the bottom.
    LaunchedEffect(state.messages.firstOrNull()?.id) {
        if (listState.firstVisibleItemIndex <= 2) listState.animateScrollToItem(0)
    }

    fun send() {
        val text = input.trim()
        if (text.isEmpty()) return
        val reply = replyTo?.let { InputMessageReplyToMessage(it.id, null, 0, "") }
        input = ""; replyTo = null
        scope.launch {
            runCatching {
                Tg.client.sendMessage(chatId, null, reply, null, null, InputMessageText(FormattedText(text, emptyArray()), null, true)).getOrThrow()
            }.onFailure { toast(ctx, ctx.getString(R.string.error_fmt, it.message)) }
        }
    }

    val subtitle = when (val t = chat?.type) {
        is ChatTypePrivate -> stringResource(R.string.type_private)
        is ChatTypeBasicGroup -> stringResource(R.string.type_group)
        is ChatTypeSupergroup -> stringResource(if (t.isChannel) R.string.type_channel else R.string.type_group)
        else -> null
    }.let { s -> listOfNotNull(s, if (showIds) "ID $chatId" else null, if (ghost) "👻" else null).joinToString(" · ") }

    StripedBackground {
        Column(Modifier.fillMaxSize().imePadding()) {
            SkTopBar(
                title = chat?.title ?: "",
                subtitle = subtitle,
                navigation = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, null, tint = Color.White) }
                        if (chat != null) Avatar(chat.id, chat.title, chat.photo, size = 38.dp)
                        Spacer(Modifier.width(8.dp))
                    }
                },
            )
            Box(Modifier.weight(1f)) {
                LazyColumn(
                    state = listState, reverseLayout = true, modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    itemsIndexed(state.messages, key = { _, m -> m.id }, contentType = { _, m -> m.content::class }) { i, m ->
                        val older = state.messages.getOrNull(i + 1)
                        Column {
                            if (older == null || !sameDay(older.date, m.date)) DayChip(dayLabel(ctx, m.date))
                            Bubble(
                                m = m,
                                showSender = isGroup && !m.isOutgoing && (older == null || senderKey(older.senderId) != senderKey(m.senderId) || !sameDay(older.date, m.date)),
                                reply = state.replyTarget(m),
                                translation = state.translations[m.id],
                                outgoingRead = m.isOutgoing && chat != null && m.id <= chat.lastReadOutbox,
                                showId = showIds,
                                onReply = { replyTo = m },
                                onTranslate = {
                                    val text = m.content.text() ?: return@Bubble
                                    state.translations[m.id] = ctx.getString(R.string.translating)
                                    scope.launch {
                                        val r = Tg.client.translateText(FormattedText(text, emptyArray()), targetLanguage(), "").getOrNull()
                                        state.translations[m.id] = r?.text ?: ctx.getString(R.string.translation_failed)
                                    }
                                },
                                onRepeat = {
                                    val text = m.content.text() ?: return@Bubble
                                    scope.launch {
                                        runCatching {
                                            Tg.client.sendMessage(chatId, null, null, null, null, InputMessageText(FormattedText(text, emptyArray()), null, false)).getOrThrow()
                                        }.onFailure { toast(ctx, ctx.getString(R.string.error_fmt, it.message)) }
                                    }
                                },
                                onDelete = {
                                    scope.launch {
                                        runCatching { Tg.client.deleteMessages(chatId, longArrayOf(m.id), true).getOrThrow() }
                                            .onFailure { toast(ctx, ctx.getString(R.string.error_fmt, it.message)) }
                                    }
                                },
                                doubleTapReply = doubleTapReply,
                            )
                        }
                    }
                    if (state.loading) item { Box(Modifier.fillMaxWidth().padding(12.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp) } }
                }
            }
            InputBar(input, { input = it }, replyTo, onCancelReply = { replyTo = null }, onSend = ::send)
        }
    }
}

private fun sameDay(a: Int, b: Int): Boolean {
    val ca = Calendar.getInstance().apply { timeInMillis = a * 1000L }
    val cb = Calendar.getInstance().apply { timeInMillis = b * 1000L }
    return ca.get(Calendar.YEAR) == cb.get(Calendar.YEAR) && ca.get(Calendar.DAY_OF_YEAR) == cb.get(Calendar.DAY_OF_YEAR)
}

@Composable
private fun DayChip(label: String) {
    Box(Modifier.fillMaxWidth().padding(vertical = 8.dp), contentAlignment = Alignment.Center) {
        Text(
            label, color = Color.White, style = MaterialTheme.typography.labelMedium,
            modifier = Modifier.clip(SkewShape(0.3f)).background(LocalSkColors.current.header).padding(horizontal = 16.dp, vertical = 4.dp),
        )
    }
}

private val nameColors = listOf(Color(0xFF128AFA), Color(0xFFE85A9B), Color(0xFF7C6CFF), Color(0xFF14A37F), Color(0xFFE08A1E), Color(0xFF0096C7))

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun Bubble(
    m: Message,
    showSender: Boolean,
    reply: Message?,
    translation: String?,
    outgoingRead: Boolean,
    showId: Boolean,
    onReply: () -> Unit,
    onTranslate: () -> Unit,
    onRepeat: () -> Unit,
    onDelete: () -> Unit,
    doubleTapReply: Boolean,
) {
    val ctx = LocalContext.current
    val sk = LocalSkColors.current
    var menu by remember { mutableStateOf(false) }
    val out = m.isOutgoing
    val shape = if (out) RoundedCornerShape(18.dp, 4.dp, 18.dp, 18.dp) else RoundedCornerShape(4.dp, 18.dp, 18.dp, 18.dp)
    val fg = if (out) sk.onBubbleOut else sk.onBubbleIn
    val text = m.content.text()

    Row(Modifier.fillMaxWidth(), horizontalArrangement = if (out) Arrangement.End else Arrangement.Start) {
        Box {
            Column(
                Modifier.widthIn(max = 300.dp)
                    .clip(shape)
                    .background(if (out) sk.bubbleOut else Brush.linearGradient(listOf(sk.bubbleIn, sk.bubbleIn)))
                    .let { if (out) it else it.border(1.dp, sk.bubbleInBorder, shape) }
                    .combinedClickable(
                        onClick = {},
                        onLongClick = { menu = true },
                        onDoubleClick = if (doubleTapReply) onReply else null,
                    )
                    .padding(horizontal = 12.dp, vertical = 8.dp),
            ) {
                if (showSender) {
                    val key = senderKey(m.senderId)
                    LaunchedEffect(key) { (m.senderId as? MessageSenderUser)?.let { Tg.user(it.userId) } }
                    Text(senderName(m.senderId) ?: "…", color = nameColors[((key % nameColors.size + nameColors.size) % nameColors.size).toInt()],
                        style = MaterialTheme.typography.labelLarge, maxLines = 1)
                }
                if (m.replyTo is MessageReplyToMessage) ReplyQuote(reply, fg)
                Media(m)
                if (text != null) Text(text, color = fg, style = MaterialTheme.typography.bodyLarge)
                if (translation != null) {
                    HorizontalDivider(Modifier.padding(vertical = 4.dp), color = fg.copy(alpha = 0.3f))
                    Text("🌐 $translation", color = fg, style = MaterialTheme.typography.bodyMedium, fontStyle = FontStyle.Italic)
                }
                Row(Modifier.align(Alignment.End), verticalAlignment = Alignment.CenterVertically) {
                    val meta = buildString {
                        if (showId) append("#${m.id}  ")
                        if (m.editDate != 0) append(ctx.getString(R.string.edited)).append(' ')
                        append(clockTime(ctx, m.date))
                        if (out) append(
                            when (m.sendingState) {
                                is MessageSendingStatePending -> "  ⏱"
                                is MessageSendingStateFailed -> "  ⚠"
                                else -> if (outgoingRead) "  ✓✓" else "  ✓"
                            }
                        )
                    }
                    Text(meta, color = if (out) sk.timeOut else sk.timeIn, style = MaterialTheme.typography.labelSmall)
                }
            }
            DropdownMenu(menu, { menu = false }) {
                DropdownMenuItem(text = { Text(stringResource(R.string.reply)) }, onClick = { menu = false; onReply() })
                if (text != null) {
                    DropdownMenuItem(text = { Text(stringResource(R.string.copy)) }, onClick = { menu = false; copyText(ctx, text) })
                    DropdownMenuItem(text = { Text(stringResource(R.string.translate)) }, onClick = { menu = false; onTranslate() })
                    DropdownMenuItem(text = { Text(stringResource(R.string.repeat)) }, onClick = { menu = false; onRepeat() })
                }
                DropdownMenuItem(text = { Text(stringResource(R.string.copy_id)) }, onClick = { menu = false; copyText(ctx, m.id.toString()) })
                DropdownMenuItem(text = { Text(stringResource(R.string.delete), color = MaterialTheme.colorScheme.error) }, onClick = { menu = false; onDelete() })
            }
        }
    }
}

@Composable
private fun ReplyQuote(reply: Message?, fg: Color) {
    val ctx = LocalContext.current
    Row(Modifier.padding(bottom = 4.dp).clip(RoundedCornerShape(6.dp)).background(fg.copy(alpha = 0.12f))) {
        Box(Modifier.width(3.dp).height(36.dp).background(fg.copy(alpha = 0.8f)))
        Column(Modifier.padding(horizontal = 8.dp, vertical = 2.dp)) {
            Text(reply?.let { senderName(it.senderId) } ?: "…", color = fg, style = MaterialTheme.typography.labelMedium, maxLines = 1, fontWeight = FontWeight.Bold)
            Text(reply?.content?.preview(ctx) ?: "", color = fg.copy(alpha = 0.85f), style = MaterialTheme.typography.bodySmall,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun Media(m: Message) {
    val ctx = LocalContext.current
    when (val c = m.content) {
        is MessagePhoto -> {
            val best = c.photo.sizes.lastOrNull { it.width <= 1280 } ?: c.photo.sizes.lastOrNull()
            if (best != null) {
                val ratio = (best.width.toFloat() / best.height.coerceAtLeast(1)).coerceIn(0.5f, 2f)
                TdImage(best.photo, c.photo.minithumbnail, Modifier.width(260.dp).aspectRatio(ratio).clip(RoundedCornerShape(12.dp)).padding(bottom = 2.dp))
            }
        }
        is MessageSticker -> {
            val s = c.sticker
            val file = if (s.format is dev.g000sha256.tdl.dto.StickerFormatWebp) s.sticker else s.thumbnail?.file
            if (file != null) TdImage(file, null, Modifier.size(150.dp), maxPx = 320, contentScale = androidx.compose.ui.layout.ContentScale.Fit)
            else Text(s.emoji, style = MaterialTheme.typography.displayMedium)
        }
        is MessageVideo -> Thumb(c.video.thumbnail?.file, c.video.minithumbnail, c.video.width, c.video.height, "▶ " + ctx.getString(R.string.msg_video))
        is MessageAnimation -> Thumb(c.animation.thumbnail?.file, c.animation.minithumbnail, c.animation.width, c.animation.height, "GIF")
        is MessageDocument -> Text("📎 " + c.document.fileName.ifEmpty { ctx.getString(R.string.msg_document) },
            style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold, color = LocalSkColors.current.let { if (m.isOutgoing) it.onBubbleOut else it.onBubbleIn })
        is MessageVoiceNote -> Text("🎤 " + ctx.getString(R.string.msg_voice) + " · " + "%d:%02d".format(c.voiceNote.duration / 60, c.voiceNote.duration % 60),
            style = MaterialTheme.typography.bodyMedium, color = LocalSkColors.current.let { if (m.isOutgoing) it.onBubbleOut else it.onBubbleIn })
        is MessageText -> Unit
        else -> Text(m.content.preview(ctx), fontStyle = FontStyle.Italic, style = MaterialTheme.typography.bodyMedium,
            color = LocalSkColors.current.let { if (m.isOutgoing) it.onBubbleOut else it.onBubbleIn })
    }
}

@Composable
private fun Thumb(file: dev.g000sha256.tdl.dto.File?, mini: dev.g000sha256.tdl.dto.Minithumbnail?, w: Int, h: Int, label: String) {
    val ratio = if (w > 0 && h > 0) (w.toFloat() / h).coerceIn(0.5f, 2f) else 16f / 9f
    Box(Modifier.width(260.dp).aspectRatio(ratio).clip(RoundedCornerShape(12.dp)).padding(bottom = 2.dp), contentAlignment = Alignment.Center) {
        TdImage(file, mini, Modifier.fillMaxSize())
        Text(label, color = Color.White, style = MaterialTheme.typography.labelLarge,
            modifier = Modifier.clip(CircleShape).background(Color(0x99000000)).padding(horizontal = 12.dp, vertical = 6.dp))
    }
}

@Composable
private fun InputBar(text: String, onText: (String) -> Unit, replyTo: Message?, onCancelReply: () -> Unit, onSend: () -> Unit) {
    val ctx = LocalContext.current
    val sk = LocalSkColors.current
    Column(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface).navigationBarsPadding()) {
        if (replyTo != null) {
            Row(Modifier.fillMaxWidth().padding(start = 14.dp, top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.width(3.dp).height(32.dp).background(MaterialTheme.colorScheme.primary))
                Column(Modifier.weight(1f).padding(horizontal = 8.dp)) {
                    Text(stringResource(R.string.replying_to, senderName(replyTo.senderId) ?: ""), color = MaterialTheme.colorScheme.primary,
                        style = MaterialTheme.typography.labelMedium, maxLines = 1)
                    Text(replyTo.content.preview(ctx), style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                IconButton(onClick = onCancelReply) { Icon(Icons.Filled.Close, null) }
            }
        }
        Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.Bottom) {
            TextField(
                text, onText, modifier = Modifier.weight(1f),
                placeholder = { Text(stringResource(R.string.message_hint)) },
                maxLines = 6, shape = RoundedCornerShape(22.dp),
                colors = TextFieldDefaults.colors(focusedIndicatorColor = Color.Transparent, unfocusedIndicatorColor = Color.Transparent),
            )
            Spacer(Modifier.width(8.dp))
            Box(
                Modifier.size(52.dp).clip(SkewShape(0.2f)).background(sk.header)
                    .clickable(enabled = text.isNotBlank(), onClick = onSend),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.AutoMirrored.Filled.Send, stringResource(R.string.send), tint = Color.White)
            }
        }
    }
}
