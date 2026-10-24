package com.skgram.app.core

import android.content.Context
import android.os.Build
import com.skgram.app.BuildConfig
import dev.g000sha256.tdl.TdlClient
import dev.g000sha256.tdl.TdlResult
import dev.g000sha256.tdl.dto.AuthorizationState
import dev.g000sha256.tdl.dto.AuthorizationStateWaitTdlibParameters
import dev.g000sha256.tdl.dto.Chat
import dev.g000sha256.tdl.dto.ChatListMain
import dev.g000sha256.tdl.dto.ChatPhotoInfo
import dev.g000sha256.tdl.dto.ChatPosition
import dev.g000sha256.tdl.dto.ChatType
import dev.g000sha256.tdl.dto.ConnectionState
import dev.g000sha256.tdl.dto.File
import dev.g000sha256.tdl.dto.Message
import dev.g000sha256.tdl.dto.OptionValueBoolean
import dev.g000sha256.tdl.dto.UpdateAuthorizationState
import dev.g000sha256.tdl.dto.UpdateChatLastMessage
import dev.g000sha256.tdl.dto.UpdateChatPhoto
import dev.g000sha256.tdl.dto.UpdateChatPosition
import dev.g000sha256.tdl.dto.UpdateChatReadInbox
import dev.g000sha256.tdl.dto.UpdateChatReadOutbox
import dev.g000sha256.tdl.dto.UpdateChatTitle
import dev.g000sha256.tdl.dto.UpdateChatUnreadMentionCount
import dev.g000sha256.tdl.dto.UpdateConnectionState
import dev.g000sha256.tdl.dto.UpdateDeleteMessages
import dev.g000sha256.tdl.dto.UpdateMessageContent
import dev.g000sha256.tdl.dto.UpdateMessageSendFailed
import dev.g000sha256.tdl.dto.UpdateMessageSendSucceeded
import dev.g000sha256.tdl.dto.UpdateNewChat
import dev.g000sha256.tdl.dto.UpdateNewMessage
import dev.g000sha256.tdl.dto.UpdateUser
import dev.g000sha256.tdl.dto.User
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.sample
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

/** The chat-list row model: just what the list needs, kept up to date from TDLib updates. */
data class ChatItem(
    val id: Long,
    val type: ChatType,
    val title: String,
    val photo: ChatPhotoInfo?,
    val lastMessage: Message?,
    val order: Long,
    val pinned: Boolean,
    val unread: Int,
    val mentions: Int,
    val lastReadOutbox: Long,
)

/** Events the open chat screen listens to. */
sealed interface MessageEvent {
    val chatId: Long
    data class New(val message: Message) : MessageEvent { override val chatId get() = message.chatId }
    data class Replaced(val oldId: Long, val message: Message) : MessageEvent { override val chatId get() = message.chatId }
    data class Deleted(override val chatId: Long, val ids: LongArray) : MessageEvent
    data class Content(override val chatId: Long, val id: Long, val content: dev.g000sha256.tdl.dto.MessageContent) : MessageEvent
}

class TgException(val code: Int, override val message: String) : Exception(message)

/** Single TDLib client for the whole app, plus the in-memory state built from its updates. */
@OptIn(FlowPreview::class, ExperimentalCoroutinesApi::class)
object Tg {
    val client: TdlClient = TdlClient.create()
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private lateinit var appContext: Context

    private val _auth = MutableStateFlow<AuthorizationState?>(null)
    val auth: StateFlow<AuthorizationState?> = _auth
    private val _connection = MutableStateFlow<ConnectionState?>(null)
    val connection: StateFlow<ConnectionState?> = _connection

    private val chats = ConcurrentHashMap<Long, ChatItem>()
    val users = ConcurrentHashMap<Long, User>()
    private val dirty = MutableStateFlow(0L)

    /** Main chat list, sorted like Telegram does, rebuilt at most ~12 times a second however many updates arrive. */
    val chatList: StateFlow<List<ChatItem>> = dirty.sample(80)
        .map { _ -> chats.values.filter { it.order != 0L }.sortedWith(compareByDescending<ChatItem> { it.order }.thenByDescending { it.id }) }
        .stateIn(scope, SharingStarted.Eagerly, emptyList())

    private val _messages = MutableSharedFlow<MessageEvent>(extraBufferCapacity = 512)
    val messages: SharedFlow<MessageEvent> = _messages
    private val _userVersion = MutableStateFlow(0L)
    val userVersion: StateFlow<Long> = _userVersion

    @Volatile private var started = false
    @Volatile var allChatsLoaded = false
        private set

    fun start(context: Context) {
        if (started) return
        started = true
        appContext = context.applicationContext
        scope.launch { client.setLogVerbosityLevel(1) }
        scope.launch {
            client.allUpdates.collect { u ->
                when (u) {
                    is UpdateAuthorizationState -> onAuth(u.authorizationState)
                    is UpdateConnectionState -> _connection.value = u.state
                    is UpdateNewChat -> putChat(u.chat)
                    is UpdateChatTitle -> edit(u.chatId) { it.copy(title = u.title) }
                    is UpdateChatPhoto -> edit(u.chatId) { it.copy(photo = u.photo) }
                    is UpdateChatLastMessage -> edit(u.chatId) { applyPositions(it.copy(lastMessage = u.lastMessage), u.positions) }
                    is UpdateChatPosition -> edit(u.chatId) { applyPositions(it, arrayOf(u.position)) }
                    is UpdateChatReadInbox -> edit(u.chatId) { it.copy(unread = u.unreadCount) }
                    is UpdateChatReadOutbox -> edit(u.chatId) { it.copy(lastReadOutbox = u.lastReadOutboxMessageId) }
                    is UpdateChatUnreadMentionCount -> edit(u.chatId) { it.copy(mentions = u.unreadMentionCount) }
                    is UpdateUser -> { users[u.user.id] = u.user; _userVersion.value++ }
                    is UpdateNewMessage -> _messages.tryEmit(MessageEvent.New(u.message))
                    is UpdateMessageSendSucceeded -> _messages.tryEmit(MessageEvent.Replaced(u.oldMessageId, u.message))
                    is UpdateMessageSendFailed -> _messages.tryEmit(MessageEvent.Replaced(u.oldMessageId, u.message))
                    is UpdateDeleteMessages -> if (u.isPermanent) _messages.tryEmit(MessageEvent.Deleted(u.chatId, u.messageIds))
                    is UpdateMessageContent -> _messages.tryEmit(MessageEvent.Content(u.chatId, u.messageId, u.newContent))
                    else -> Unit
                }
            }
        }
        // TDLib only starts working after the first request.
        scope.launch { client.getAuthorizationState().getOrNull()?.let { onAuth(it) } }
    }

    private fun onAuth(state: AuthorizationState) {
        _auth.value = state
        if (state is AuthorizationStateWaitTdlibParameters && Prefs.hasApiCredentials) scope.launch { sendParameters() }
    }

    suspend fun sendParameters(): Result<Unit> {
        val dir = appContext.filesDir.absolutePath
        return client.setTdlibParameters(
            false, "$dir/td", "$dir/td_files", ByteArray(0),
            true, true, true, false,
            Prefs.apiId, Prefs.apiHash,
            Locale.getDefault().language, Build.MODEL ?: "Android", "Android ${Build.VERSION.RELEASE}", "SKGram ${BuildConfig.VERSION_NAME}",
        ).unit()
    }

    private fun putChat(chat: Chat) {
        var item = ChatItem(
            id = chat.id, type = chat.type, title = chat.title, photo = chat.photo, lastMessage = chat.lastMessage,
            order = 0, pinned = false, unread = chat.unreadCount, mentions = chat.unreadMentionCount,
            lastReadOutbox = chat.lastReadOutboxMessageId,
        )
        item = applyPositions(item, chat.positions)
        chats[chat.id] = item
        dirty.value++
    }

    private inline fun edit(chatId: Long, f: (ChatItem) -> ChatItem) {
        val old = chats[chatId] ?: return
        chats[chatId] = f(old)
        dirty.value++
    }

    private fun applyPositions(item: ChatItem, positions: Array<ChatPosition>): ChatItem {
        val main = positions.firstOrNull { it.list is ChatListMain } ?: return item
        return item.copy(order = main.order, pinned = main.isPinned)
    }

    fun chat(id: Long): ChatItem? = chats[id]

    suspend fun loadMoreChats() {
        if (allChatsLoaded) return
        val r = client.loadChats(ChatListMain(), 100)
        if (r is TdlResult.Failure && r.code == 404) allChatsLoaded = true
    }

    suspend fun user(id: Long): User? = users[id] ?: client.getUser(id).getOrNull()?.also { users[id] = it }

    fun setOnline(online: Boolean) {
        if (_auth.value !is dev.g000sha256.tdl.dto.AuthorizationStateReady) return
        scope.launch { client.setOption("online", OptionValueBoolean(online && !Prefs.ghost.value)) }
    }

    /** Downloads a file (if needed) and returns its local path. */
    suspend fun download(file: File, priority: Int = 16): String? {
        if (file.local.isDownloadingCompleted && file.local.path.isNotEmpty()) return file.local.path
        val f = client.downloadFile(file.id, priority, 0, 0, true).getOrNull() ?: return null
        return f.local.path.takeIf { f.local.isDownloadingCompleted && it.isNotEmpty() }
    }
}

fun <T> TdlResult<T>.getOrNull(): T? = (this as? TdlResult.Success<T>)?.result

fun <T> TdlResult<T>.getOrThrow(): T = when (this) {
    is TdlResult.Success -> result
    is TdlResult.Failure -> throw TgException(code, message)
}

fun <T> TdlResult<T>.unit(): Result<Unit> = when (this) {
    is TdlResult.Success -> Result.success(Unit)
    is TdlResult.Failure -> Result.failure(TgException(code, message))
}
