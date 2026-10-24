package com.skgram.ui.screens

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
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Phone
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
import androidx.compose.runtime.mutableIntStateOf
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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.skgram.core.Account
import com.skgram.core.AppState
import com.skgram.core.AudioPlayer
import com.skgram.core.MessageEvent
import com.skgram.core.PickedFile
import com.skgram.core.PlatformBackHandler
import com.skgram.core.Prefs
import com.skgram.core.VideoPlayer
import com.skgram.core.clockTime
import com.skgram.core.dayLabel
import com.skgram.core.displayName
import com.skgram.core.duration
import com.skgram.core.getOrNull
import com.skgram.core.getOrThrow
import com.skgram.core.preview
import com.skgram.core.rememberFilePicker
import com.skgram.core.rememberMicPermission
import com.skgram.core.rememberVoiceRecorder
import com.skgram.core.sameDay
import com.skgram.core.senderKey
import com.skgram.core.senderName
import com.skgram.core.text
import com.skgram.i18n.I18n
import com.skgram.i18n.LocalStrings
import com.skgram.i18n.fmt
import com.skgram.ui.LocalAccount
import com.skgram.ui.Toaster
import com.skgram.ui.components.Avatar
import com.skgram.ui.components.SkTopBar
import com.skgram.ui.components.StripedBackground
import com.skgram.ui.components.TdImage
import com.skgram.ui.theme.LocalSkColors
import com.skgram.ui.theme.SkewShape
import dev.g000sha256.tdl.dto.ChatTypeBasicGroup
import dev.g000sha256.tdl.dto.ChatTypePrivate
import dev.g000sha256.tdl.dto.ChatTypeSecret
import dev.g000sha256.tdl.dto.ChatTypeSupergroup
import dev.g000sha256.tdl.dto.File
import dev.g000sha256.tdl.dto.FormattedText
import dev.g000sha256.tdl.dto.InputDocument
import dev.g000sha256.tdl.dto.InputFileLocal
import dev.g000sha256.tdl.dto.InputMessageContent
import dev.g000sha256.tdl.dto.InputMessageDocument
import dev.g000sha256.tdl.dto.InputMessagePhoto
import dev.g000sha256.tdl.dto.InputMessageReplyToMessage
import dev.g000sha256.tdl.dto.InputMessageText
import dev.g000sha256.tdl.dto.InputMessageVideo
import dev.g000sha256.tdl.dto.InputMessageVoiceNote
import dev.g000sha256.tdl.dto.InputPhoto
import dev.g000sha256.tdl.dto.InputVideo
import dev.g000sha256.tdl.dto.InputVoiceNote
import dev.g000sha256.tdl.dto.Message
import dev.g000sha256.tdl.dto.MessageAnimation
import dev.g000sha256.tdl.dto.MessageAudio
import dev.g000sha256.tdl.dto.MessageCall
import dev.g000sha256.tdl.dto.MessageDocument
import dev.g000sha256.tdl.dto.MessagePhoto
import dev.g000sha256.tdl.dto.MessageReplyToMessage
import dev.g000sha256.tdl.dto.MessageSenderUser
import dev.g000sha256.tdl.dto.MessageSendingStateFailed
import dev.g000sha256.tdl.dto.MessageSendingStatePending
import dev.g000sha256.tdl.dto.MessageSticker
import dev.g000sha256.tdl.dto.MessageText
import dev.g000sha256.tdl.dto.MessageVideo
import dev.g000sha256.tdl.dto.MessageVoiceNote
import dev.g000sha256.tdl.dto.SecretChatStateClosed
import dev.g000sha256.tdl.dto.SecretChatStatePending
import dev.g000sha256.tdl.dto.StickerFormatWebp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.launch

/** Messages of one open chat, newest first. */
private class ChatState(val account: Account, val chatId: Long, private val scope: CoroutineScope) {
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
                val batch = account.client.getChatHistory(chatId, from, 0, 50, false).getOrNull()?.messages?.filterNotNull() ?: break
                if (batch.isEmpty()) { reachedStart = true; break }
                val fresh = batch.filter { m -> messages.none { it.id == m.id } }
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
            is MessageEvent.Content -> if (messages.any { it.id == e.id }) {
                // DTOs are immutable; refetch the edited message.
                scope.launch {
                    val m = account.client.getMessage(chatId, e.id).getOrNull() ?: return@launch
                    val i = messages.indexOfFirst { it.id == e.id }
                    if (i >= 0) messages[i] = m
                }
                translations.remove(e.id)
            }
        }
    }

    fun markViewed(ids: List<Long>) {
        if (Prefs.ghost.value || ids.isEmpty()) return
        scope.launch { account.client.viewMessages(chatId, ids.toLongArray(), null, true) }
    }

    fun replyTarget(m: Message): Message? {
        val r = m.replyTo as? MessageReplyToMessage ?: return null
        messages.firstOrNull { it.id == r.messageId }?.let { return it }
        replies[r.messageId]?.let { return it }
        if (r.chatId == chatId && r.messageId != 0L) scope.launch {
            account.client.getMessage(r.chatId, r.messageId).getOrNull()?.let { replies[r.messageId] = it }
        }
        return null
    }

    fun send(content: InputMessageContent, replyTo: Long? = null) {
        val reply = replyTo?.let { InputMessageReplyToMessage(it, null, 0, "") }
        scope.launch {
            runCatching { account.client.sendMessage(chatId, null, reply, null, null, content).getOrThrow() }
                .onFailure { Toaster.show(I18n.strings.errorFmt.fmt(it.message)) }
        }
    }
}

private fun fmtText(text: String) = FormattedText(text, emptyArray())

private fun PickedFile.toContent(caption: String): InputMessageContent {
    val file = InputFileLocal(path)
    val cap = fmtText(caption)
    return when (kind) {
        PickedFile.Kind.PHOTO -> InputMessagePhoto(InputPhoto(file, null, null, IntArray(0), width, height), cap, false, null, false)
        PickedFile.Kind.VIDEO -> InputMessageVideo(InputVideo(file, null, null, 0, IntArray(0), durationSec, width, height, true), cap, false, null, false)
        PickedFile.Kind.FILE -> InputMessageDocument(InputDocument(file, null, false), cap)
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ChatScreen(chatId: Long, onBack: () -> Unit) {
    val account = LocalAccount.current
    val s = LocalStrings.current
    val scope = rememberCoroutineScope()
    val state = remember(chatId) { ChatState(account, chatId, scope) }
    val chats by account.chatList.collectAsState()
    val chat = remember(chats, chatId) { chats.firstOrNull { it.id == chatId } ?: account.chat(chatId) }
    val ghost by Prefs.ghost.collectAsState()
    val showIds by Prefs.showIds.collectAsState()
    val doubleTapReply by Prefs.doubleTapReply.collectAsState()
    account.version.collectAsState().value // re-read names and secret chat state when they change
    val listState = rememberLazyListState()
    var input by rememberSaveable(chatId) { mutableStateOf("") }
    var replyTo by remember { mutableStateOf<Message?>(null) }
    var videoPath by remember { mutableStateOf<String?>(null) }
    val isGroup = chat?.type is ChatTypeBasicGroup || (chat?.type as? ChatTypeSupergroup)?.isChannel == false

    PlatformBackHandler(onBack = onBack)
    DisposableEffect(chatId) {
        AppState.openChat = account.id to chatId
        if (!Prefs.ghost.value) scope.launch { account.client.openChat(chatId) }
        onDispose {
            AppState.openChat = null
            account.scope.launch { account.client.closeChat(chatId) }
        }
    }
    LaunchedEffect(chatId) {
        launch { account.messages.filter { it.chatId == chatId }.collect(state::onEvent) }
        state.loadOlder()
    }
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

    val askMic = rememberMicPermission()
    val pick = rememberFilePicker { files ->
        val caption = input.trim()
        input = ""
        files.forEachIndexed { i, f -> state.send(f.toContent(if (i == 0) caption else "")) }
    }

    val subtitle = when (val t = chat?.type) {
        is ChatTypePrivate -> s.typePrivate
        is ChatTypeBasicGroup -> s.typeGroup
        is ChatTypeSupergroup -> if (t.isChannel) s.typeChannel else s.typeGroup
        is ChatTypeSecret -> when (account.secretChats[t.secretChatId]?.state) {
            is SecretChatStatePending -> s.secretPending.fmt(account.users[t.userId]?.displayName() ?: "")
            is SecretChatStateClosed -> s.secretClosed
            else -> "🔒 " + s.secretReady
        }
        else -> null
    }.let { listOfNotNull(it, if (showIds) "ID $chatId" else null, if (ghost) "👻" else null).joinToString(" · ") }

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
                actions = {
                    val peer = (chat?.type as? ChatTypePrivate)?.userId
                    if (peer != null && peer != account.me.value?.id) {
                        IconButton(onClick = {
                            askMic { granted -> if (granted) account.calls.startCall(peer) else Toaster.show(s.micDenied) }
                        }) { Icon(Icons.Filled.Phone, s.callAction, tint = Color.White) }
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
                            if (older == null || !sameDay(older.date, m.date)) DayChip(dayLabel(m.date))
                            Bubble(
                                m = m,
                                showSender = isGroup && !m.isOutgoing && (older == null || senderKey(older.senderId) != senderKey(m.senderId) || !sameDay(older.date, m.date)),
                                reply = state.replyTarget(m),
                                translation = state.translations[m.id],
                                outgoingRead = m.isOutgoing && chat != null && m.id <= chat.lastReadOutbox,
                                showId = showIds,
                                doubleTapReply = doubleTapReply,
                                onReply = { replyTo = m },
                                onTranslate = {
                                    val text = m.content.text() ?: return@Bubble
                                    state.translations[m.id] = s.translating
                                    scope.launch {
                                        val r = account.client.translateText(fmtText(text), I18n.lang.code, "").getOrNull()
                                        state.translations[m.id] = r?.text ?: s.translationFailed
                                    }
                                },
                                onRepeat = { m.content.text()?.let { state.send(InputMessageText(fmtText(it), null, false)) } },
                                onDelete = {
                                    scope.launch {
                                        runCatching { account.client.deleteMessages(chatId, longArrayOf(m.id), true).getOrThrow() }
                                            .onFailure { Toaster.show(s.errorFmt.fmt(it.message)) }
                                    }
                                },
                                onPlayVideo = { videoPath = it },
                            )
                        }
                    }
                    if (state.loading) item {
                        Box(Modifier.fillMaxWidth().padding(12.dp), contentAlignment = Alignment.Center) {
                            CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
                        }
                    }
                }
            }
            InputBar(
                text = input, onText = { input = it },
                replyTo = replyTo, onCancelReply = { replyTo = null },
                onSend = {
                    val text = input.trim()
                    if (text.isNotEmpty()) {
                        state.send(InputMessageText(fmtText(text), null, true), replyTo?.id)
                        input = ""; replyTo = null
                    }
                },
                onAttach = pick,
                onVoice = { voice -> state.send(InputMessageVoiceNote(InputVoiceNote(InputFileLocal(voice.path), voice.durationSec, ByteArray(0)), null, null), replyTo?.id); replyTo = null },
            )
        }
    }
    videoPath?.let { VideoPlayer(it) { videoPath = null } }
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
    doubleTapReply: Boolean,
    onReply: () -> Unit,
    onTranslate: () -> Unit,
    onRepeat: () -> Unit,
    onDelete: () -> Unit,
    onPlayVideo: (String) -> Unit,
) {
    val account = LocalAccount.current
    val s = LocalStrings.current
    val sk = LocalSkColors.current
    val clipboard = LocalClipboardManager.current
    var menu by remember { mutableStateOf(false) }
    val out = m.isOutgoing
    val shape = if (out) RoundedCornerShape(18.dp, 4.dp, 18.dp, 18.dp) else RoundedCornerShape(4.dp, 18.dp, 18.dp, 18.dp)
    val fg = if (out) sk.onBubbleOut else sk.onBubbleIn
    val text = m.content.text()

    Row(Modifier.fillMaxWidth(), horizontalArrangement = if (out) Arrangement.End else Arrangement.Start) {
        Box {
            Column(
                Modifier.widthIn(max = 320.dp)
                    .clip(shape)
                    .background(if (out) sk.bubbleOut else Brush.linearGradient(listOf(sk.bubbleIn, sk.bubbleIn)))
                    .let { if (out) it else it.border(1.dp, sk.bubbleInBorder, shape) }
                    .combinedClickable(onClick = {}, onLongClick = { menu = true }, onDoubleClick = if (doubleTapReply) onReply else null)
                    .padding(horizontal = 12.dp, vertical = 8.dp),
            ) {
                if (showSender) {
                    val key = senderKey(m.senderId)
                    LaunchedEffect(key) { (m.senderId as? MessageSenderUser)?.let { account.user(it.userId) } }
                    Text(account.senderName(m.senderId) ?: "…", color = nameColors[((key % nameColors.size + nameColors.size) % nameColors.size).toInt()],
                        style = MaterialTheme.typography.labelLarge, maxLines = 1)
                }
                if (m.replyTo is MessageReplyToMessage) ReplyQuote(reply, fg)
                Media(m, fg, onPlayVideo)
                if (text != null) Text(text, color = fg, style = MaterialTheme.typography.bodyLarge)
                if (translation != null) {
                    HorizontalDivider(Modifier.padding(vertical = 4.dp), color = fg.copy(alpha = 0.3f))
                    Text("🌐 $translation", color = fg, style = MaterialTheme.typography.bodyMedium, fontStyle = FontStyle.Italic)
                }
                val meta = buildString {
                    if (showId) append("#${m.id}  ")
                    if (m.editDate != 0) append(s.edited).append(' ')
                    append(clockTime(m.date))
                    if (out) append(
                        when (m.sendingState) {
                            is MessageSendingStatePending -> "  ⏱"
                            is MessageSendingStateFailed -> "  ⚠"
                            else -> if (outgoingRead) "  ✓✓" else "  ✓"
                        }
                    )
                }
                Text(meta, color = if (out) sk.timeOut else sk.timeIn, style = MaterialTheme.typography.labelSmall, modifier = Modifier.align(Alignment.End))
            }
            DropdownMenu(menu, { menu = false }) {
                DropdownMenuItem(text = { Text(s.reply) }, onClick = { menu = false; onReply() })
                if (text != null) {
                    DropdownMenuItem(text = { Text(s.copy) }, onClick = { menu = false; clipboard.setText(AnnotatedString(text)); Toaster.show(s.copied) })
                    DropdownMenuItem(text = { Text(s.translate) }, onClick = { menu = false; onTranslate() })
                    DropdownMenuItem(text = { Text(s.repeat) }, onClick = { menu = false; onRepeat() })
                }
                DropdownMenuItem(text = { Text(s.copyId) }, onClick = { menu = false; clipboard.setText(AnnotatedString(m.id.toString())); Toaster.show(s.copied) })
                DropdownMenuItem(text = { Text(s.delete, color = MaterialTheme.colorScheme.error) }, onClick = { menu = false; onDelete() })
            }
        }
    }
}

@Composable
private fun ReplyQuote(reply: Message?, fg: Color) {
    val account = LocalAccount.current
    val s = LocalStrings.current
    Row(Modifier.padding(bottom = 4.dp).clip(RoundedCornerShape(6.dp)).background(fg.copy(alpha = 0.12f))) {
        Box(Modifier.width(3.dp).height(36.dp).background(fg.copy(alpha = 0.8f)))
        Column(Modifier.padding(horizontal = 8.dp, vertical = 2.dp)) {
            Text(reply?.let { account.senderName(it.senderId) } ?: "…", color = fg, style = MaterialTheme.typography.labelMedium, maxLines = 1, fontWeight = FontWeight.Bold)
            Text(reply?.preview(s) ?: "", color = fg.copy(alpha = 0.85f), style = MaterialTheme.typography.bodySmall,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

/** Download state of one file, following TDLib's progress updates. */
private class FileState(file: File) {
    var path by mutableStateOf(file.local.path.takeIf { file.local.isDownloadingCompleted && it.isNotEmpty() })
    var progress by mutableStateOf<Float?>(null)
}

@Composable
private fun rememberFileState(file: File): FileState {
    val account = LocalAccount.current
    val st = remember(file.id) { FileState(file) }
    LaunchedEffect(file.id) {
        account.files.filter { it.id == file.id }.collect { f ->
            if (f.local.isDownloadingCompleted) { st.path = f.local.path; st.progress = null }
            else if (f.local.isDownloadingActive) st.progress = f.local.downloadedSize.toFloat() / (f.size.takeIf { it > 0 } ?: f.expectedSize).coerceAtLeast(1)
        }
    }
    return st
}

/** Tapping a file: use it if downloaded, otherwise download it first and then use it. */
@Composable
private fun fileAction(file: File, action: (String) -> Unit): Pair<FileState, () -> Unit> {
    val account = LocalAccount.current
    val st = rememberFileState(file)
    var pending by remember(file.id) { mutableStateOf(false) }
    LaunchedEffect(st.path, pending) {
        val p = st.path
        if (pending && p != null) { pending = false; action(p) }
    }
    return st to {
        val p = st.path
        if (p != null) action(p) else {
            pending = true
            st.progress = 0f
            account.scope.launch { account.client.downloadFile(file.id, 32, 0, 0, false) }
        }
    }
}

/** File id of the voice note / audio currently playing. */
private val playing = mutableIntStateOf(0)

@Composable
private fun Media(m: Message, fg: Color, onPlayVideo: (String) -> Unit) {
    val s = LocalStrings.current
    when (val c = m.content) {
        is MessagePhoto -> {
            val best = c.photo.sizes.lastOrNull { it.width <= 1280 } ?: c.photo.sizes.lastOrNull()
            if (best != null) {
                val ratio = (best.width.toFloat() / best.height.coerceAtLeast(1)).coerceIn(0.5f, 2f)
                TdImage(best.photo, c.photo.minithumbnail, Modifier.width(260.dp).aspectRatio(ratio).clip(RoundedCornerShape(12.dp)).padding(bottom = 2.dp))
            }
        }
        is MessageSticker -> {
            val st = c.sticker
            val file = if (st.format is StickerFormatWebp) st.sticker else st.thumbnail?.file
            if (file != null) TdImage(file, null, Modifier.size(150.dp), maxPx = 320, contentScale = ContentScale.Fit)
            else Text(st.emoji, style = MaterialTheme.typography.displayMedium)
        }
        is MessageVideo -> {
            val (st, tap) = fileAction(c.video.video, onPlayVideo)
            Thumb(c.video.thumbnail?.file, c.video.minithumbnail, c.video.width, c.video.height,
                st.progress?.let { s.downloading.fmt("${(it * 100).toInt()}%") } ?: "▶ ${duration(c.video.duration)}", tap)
        }
        is MessageAnimation -> {
            val (st, tap) = fileAction(c.animation.animation, onPlayVideo)
            Thumb(c.animation.thumbnail?.file, c.animation.minithumbnail, c.animation.width, c.animation.height,
                st.progress?.let { s.downloading.fmt("${(it * 100).toInt()}%") } ?: "▶ GIF", tap)
        }
        is MessageVoiceNote -> AudioRow(c.voiceNote.voice, "🎤 " + s.msgVoice, duration(c.voiceNote.duration), fg)
        is MessageAudio -> AudioRow(c.audio.audio, "🎵 " + listOf(c.audio.performer, c.audio.title).filter { it.isNotEmpty() }.joinToString(" – ").ifEmpty { c.audio.fileName },
            duration(c.audio.duration), fg)
        is MessageDocument -> {
            val (st, tap) = fileAction(c.document.document) { com.skgram.core.Platform.openFile(it, c.document.mimeType) }
            Row(Modifier.clip(RoundedCornerShape(10.dp)).clickable(onClick = tap).padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(42.dp).clip(CircleShape).background(fg.copy(alpha = 0.15f)), contentAlignment = Alignment.Center) {
                    val p = st.progress
                    if (p != null) CircularProgressIndicator(progress = { p }, modifier = Modifier.size(30.dp), color = fg, strokeWidth = 3.dp)
                    else Text(if (st.path != null) "📄" else "⬇", color = fg)
                }
                Spacer(Modifier.width(10.dp))
                Column {
                    Text(c.document.fileName.ifEmpty { s.msgDocument }, color = fg, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text(sizeLabel(c.document.document.size) + if (st.path != null) " · " + s.open else "", color = fg.copy(alpha = 0.7f), style = MaterialTheme.typography.labelSmall)
                }
            }
        }
        is MessageCall -> Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.Phone, null, tint = fg, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(6.dp))
            Text(m.preview(s).removePrefix("📞 "), color = fg, fontWeight = FontWeight.Bold)
        }
        is MessageText -> Unit
        else -> Text(m.preview(s), fontStyle = FontStyle.Italic, style = MaterialTheme.typography.bodyMedium, color = fg)
    }
}

private fun sizeLabel(bytes: Long): String = when {
    bytes >= 1L shl 30 -> "%.1f GB".format(bytes / (1L shl 30).toDouble())
    bytes >= 1L shl 20 -> "%.1f MB".format(bytes / (1L shl 20).toDouble())
    bytes >= 1L shl 10 -> "%d KB".format(bytes shr 10)
    else -> "$bytes B"
}

@Composable
private fun AudioRow(file: File, title: String, length: String, fg: Color) {
    val s = LocalStrings.current
    val isPlaying = playing.intValue == file.id
    val (st, tap) = fileAction(file) { path ->
        if (playing.intValue == file.id) { AudioPlayer.stop(); playing.intValue = 0 }
        else { playing.intValue = file.id; AudioPlayer.play(path) { if (playing.intValue == file.id) playing.intValue = 0 } }
    }
    Row(Modifier.clip(RoundedCornerShape(22.dp)).clickable(onClick = tap).padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(42.dp).clip(CircleShape).background(fg.copy(alpha = 0.18f)), contentAlignment = Alignment.Center) {
            val p = st.progress
            if (p != null) CircularProgressIndicator(progress = { p }, modifier = Modifier.size(30.dp), color = fg, strokeWidth = 3.dp)
            else Text(if (isPlaying) "⏸" else "▶", color = fg, fontWeight = FontWeight.Black)
        }
        Spacer(Modifier.width(10.dp))
        Column {
            Text(title, color = fg, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(st.progress?.let { s.downloading.fmt("${(it * 100).toInt()}%") } ?: length, color = fg.copy(alpha = 0.7f), style = MaterialTheme.typography.labelSmall)
        }
    }
}

@Composable
private fun Thumb(file: File?, mini: dev.g000sha256.tdl.dto.Minithumbnail?, w: Int, h: Int, label: String, onClick: () -> Unit) {
    val ratio = if (w > 0 && h > 0) (w.toFloat() / h).coerceIn(0.5f, 2f) else 16f / 9f
    Box(
        Modifier.width(260.dp).aspectRatio(ratio).clip(RoundedCornerShape(12.dp)).clickable(onClick = onClick).padding(bottom = 2.dp),
        contentAlignment = Alignment.Center,
    ) {
        TdImage(file, mini, Modifier.fillMaxSize())
        Text(label, color = Color.White, style = MaterialTheme.typography.labelLarge,
            modifier = Modifier.clip(CircleShape).background(Color(0x99000000)).padding(horizontal = 12.dp, vertical = 6.dp))
    }
}

@Composable
private fun InputBar(
    text: String,
    onText: (String) -> Unit,
    replyTo: Message?,
    onCancelReply: () -> Unit,
    onSend: () -> Unit,
    onAttach: (PickedFile.Kind) -> Unit,
    onVoice: (com.skgram.core.RecordedVoice) -> Unit,
) {
    val account = LocalAccount.current
    val s = LocalStrings.current
    val sk = LocalSkColors.current
    var attachMenu by remember { mutableStateOf(false) }
    var recording by remember { mutableStateOf(false) }
    var seconds by remember { mutableIntStateOf(0) }
    val recorder = rememberVoiceRecorder(onDenied = { Toaster.show(s.micDenied) })
    LaunchedEffect(recording) {
        seconds = 0
        while (recording) { delay(1000); seconds++ }
    }

    Column(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface).navigationBarsPadding()) {
        if (replyTo != null) {
            Row(Modifier.fillMaxWidth().padding(start = 14.dp, top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.width(3.dp).height(32.dp).background(MaterialTheme.colorScheme.primary))
                Column(Modifier.weight(1f).padding(horizontal = 8.dp)) {
                    Text(s.replyingTo.fmt(account.senderName(replyTo.senderId) ?: ""), color = MaterialTheme.colorScheme.primary,
                        style = MaterialTheme.typography.labelMedium, maxLines = 1)
                    Text(replyTo.preview(s), style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                IconButton(onClick = onCancelReply) { Icon(Icons.Filled.Close, null) }
            }
        }
        Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.Bottom) {
            if (recording && recorder != null) {
                IconButton(onClick = { recorder.cancel(); recording = false }) { Icon(Icons.Filled.Close, s.cancel, tint = MaterialTheme.colorScheme.error) }
                Text("🔴 " + s.recording.fmt(duration(seconds)), modifier = Modifier.weight(1f).padding(12.dp), color = MaterialTheme.colorScheme.error)
                ActionButton(Icons.Filled.Check, s.send) {
                    recording = false
                    recorder.stop()?.let(onVoice)
                }
                return@Row
            }
            Box {
                IconButton(onClick = { attachMenu = true }) { Icon(Icons.Filled.Add, null, tint = MaterialTheme.colorScheme.primary) }
                DropdownMenu(attachMenu, { attachMenu = false }) {
                    DropdownMenuItem(text = { Text("🖼  " + s.attachMedia) }, onClick = { attachMenu = false; onAttach(PickedFile.Kind.PHOTO) })
                    DropdownMenuItem(text = { Text("📎  " + s.attachFile) }, onClick = { attachMenu = false; onAttach(PickedFile.Kind.FILE) })
                }
            }
            TextField(
                text, onText, modifier = Modifier.weight(1f),
                placeholder = { Text(s.messageHint) },
                maxLines = 6, shape = RoundedCornerShape(22.dp),
                colors = TextFieldDefaults.colors(focusedIndicatorColor = Color.Transparent, unfocusedIndicatorColor = Color.Transparent),
            )
            Spacer(Modifier.width(8.dp))
            if (text.isBlank() && recorder != null) {
                Box(
                    Modifier.size(52.dp).clip(SkewShape(0.2f)).background(sk.header).clickable { recorder.start { recording = true } },
                    contentAlignment = Alignment.Center,
                ) { Text("🎤", color = Color.White) }
            } else {
                ActionButton(Icons.AutoMirrored.Filled.Send, s.send, enabled = text.isNotBlank(), onClick = onSend)
            }
        }
    }
}

@Composable
private fun ActionButton(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, enabled: Boolean = true, onClick: () -> Unit) {
    Box(
        Modifier.size(52.dp).clip(SkewShape(0.2f)).background(LocalSkColors.current.header).clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, label, tint = Color.White)
    }
}
