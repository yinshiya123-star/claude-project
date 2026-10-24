package com.skgram.core

import com.skgram.i18n.I18n
import dev.g000sha256.tdl.TdlClient
import dev.g000sha256.tdl.TdlResult
import dev.g000sha256.tdl.dto.AuthorizationState
import dev.g000sha256.tdl.dto.AuthorizationStateClosed
import dev.g000sha256.tdl.dto.AuthorizationStateReady
import dev.g000sha256.tdl.dto.AuthorizationStateWaitTdlibParameters
import dev.g000sha256.tdl.dto.Call
import dev.g000sha256.tdl.dto.CallStatePending
import dev.g000sha256.tdl.dto.Chat
import dev.g000sha256.tdl.dto.ChatListMain
import dev.g000sha256.tdl.dto.ChatPhotoInfo
import dev.g000sha256.tdl.dto.ChatPosition
import dev.g000sha256.tdl.dto.ChatType
import dev.g000sha256.tdl.dto.ChatTypeBasicGroup
import dev.g000sha256.tdl.dto.ChatTypeSupergroup
import dev.g000sha256.tdl.dto.ConnectionState
import dev.g000sha256.tdl.dto.File
import dev.g000sha256.tdl.dto.Message
import dev.g000sha256.tdl.dto.MessageContent
import dev.g000sha256.tdl.dto.NotificationGroup
import dev.g000sha256.tdl.dto.NotificationTypeNewMessage
import dev.g000sha256.tdl.dto.OptionValueBoolean
import dev.g000sha256.tdl.dto.OptionValueInteger
import dev.g000sha256.tdl.dto.SecretChat
import dev.g000sha256.tdl.dto.UpdateActiveNotifications
import dev.g000sha256.tdl.dto.UpdateAuthorizationState
import dev.g000sha256.tdl.dto.UpdateCall
import dev.g000sha256.tdl.dto.UpdateChatLastMessage
import dev.g000sha256.tdl.dto.UpdateChatPhoto
import dev.g000sha256.tdl.dto.UpdateChatPosition
import dev.g000sha256.tdl.dto.UpdateChatReadInbox
import dev.g000sha256.tdl.dto.UpdateChatReadOutbox
import dev.g000sha256.tdl.dto.UpdateChatTitle
import dev.g000sha256.tdl.dto.UpdateChatUnreadMentionCount
import dev.g000sha256.tdl.dto.UpdateConnectionState
import dev.g000sha256.tdl.dto.UpdateDeleteMessages
import dev.g000sha256.tdl.dto.UpdateFile
import dev.g000sha256.tdl.dto.UpdateMessageContent
import dev.g000sha256.tdl.dto.UpdateMessageSendFailed
import dev.g000sha256.tdl.dto.UpdateMessageSendSucceeded
import dev.g000sha256.tdl.dto.UpdateNewChat
import dev.g000sha256.tdl.dto.UpdateNewMessage
import dev.g000sha256.tdl.dto.UpdateNotificationGroup
import dev.g000sha256.tdl.dto.UpdateSecretChat
import dev.g000sha256.tdl.dto.UpdateUser
import dev.g000sha256.tdl.dto.User
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.sample
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
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
    data class Content(override val chatId: Long, val id: Long, val content: MessageContent) : MessageEvent
}

class TgException(val code: Int, override val message: String) : Exception(message)

/** What the UI is showing, so notifications for what the user is looking at can be skipped. */
object AppState {
    @Volatile var foreground = false
    @Volatile var openChat: Pair<Int, Long>? = null
}

/** One logged-in (or logging-in) Telegram account: its own TDLib instance, database and state. */
@OptIn(FlowPreview::class, ExperimentalCoroutinesApi::class)
class Account(val id: Int, private val dbDir: String, private val filesDir: String, private val onClosed: (Account) -> Unit) {
    val client: TdlClient = TdlClient.create()
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val _auth = MutableStateFlow<AuthorizationState?>(null)
    val auth: StateFlow<AuthorizationState?> = _auth
    private val _connection = MutableStateFlow<ConnectionState?>(null)
    val connection: StateFlow<ConnectionState?> = _connection
    private val _me = MutableStateFlow<User?>(null)
    val me: StateFlow<User?> = _me
    val isReady get() = _auth.value is AuthorizationStateReady

    private val chats = ConcurrentHashMap<Long, ChatItem>()
    val users = ConcurrentHashMap<Long, User>()
    val secretChats = ConcurrentHashMap<Int, SecretChat>()
    private val dirty = MutableStateFlow(0L)

    /** Main chat list, sorted like Telegram does, rebuilt at most ~12 times a second however many updates arrive. */
    val chatList: StateFlow<List<ChatItem>> = dirty.sample(80)
        .map { _ -> chats.values.filter { it.order != 0L }.sortedWith(compareByDescending<ChatItem> { it.order }.thenByDescending { it.id }) }
        .stateIn(scope, SharingStarted.Eagerly, emptyList())

    private val _messages = MutableSharedFlow<MessageEvent>(extraBufferCapacity = 512)
    val messages: SharedFlow<MessageEvent> = _messages
    private val _files = MutableSharedFlow<File>(extraBufferCapacity = 512)
    val files: SharedFlow<File> = _files
    private val _version = MutableStateFlow(0L)
    /** Bumps when users or secret chats change, so names and states get re-read. */
    val version: StateFlow<Long> = _version
    private val _incomingCall = MutableStateFlow<Call?>(null)
    val incomingCall: StateFlow<Call?> = _incomingCall

    private val notificationLines = HashMap<Int, LinkedHashMap<Int, String>>()

    @Volatile var allChatsLoaded = false
        private set

    fun start() {
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
                    is UpdateUser -> {
                        users[u.user.id] = u.user
                        if (u.user.id == _me.value?.id) _me.value = u.user
                        _version.value++
                    }
                    is UpdateSecretChat -> { secretChats[u.secretChat.id] = u.secretChat; _version.value++ }
                    is UpdateNewMessage -> _messages.tryEmit(MessageEvent.New(u.message))
                    is UpdateMessageSendSucceeded -> _messages.tryEmit(MessageEvent.Replaced(u.oldMessageId, u.message))
                    is UpdateMessageSendFailed -> _messages.tryEmit(MessageEvent.Replaced(u.oldMessageId, u.message))
                    is UpdateDeleteMessages -> if (u.isPermanent) _messages.tryEmit(MessageEvent.Deleted(u.chatId, u.messageIds))
                    is UpdateMessageContent -> _messages.tryEmit(MessageEvent.Content(u.chatId, u.messageId, u.newContent))
                    is UpdateFile -> _files.tryEmit(u.file)
                    is UpdateNotificationGroup -> onNotificationGroup(u)
                    is UpdateActiveNotifications -> u.groups.forEach(::onActiveGroup)
                    is UpdateCall -> onCall(u.call)
                    else -> Unit
                }
            }
        }
        // TDLib only starts working after the first request.
        scope.launch { client.getAuthorizationState().getOrNull()?.let { onAuth(it) } }
    }

    private fun onAuth(state: AuthorizationState) {
        _auth.value = state
        when (state) {
            is AuthorizationStateWaitTdlibParameters -> if (Prefs.hasApiCredentials) scope.launch { sendParameters() }
            is AuthorizationStateReady -> scope.launch {
                client.setOption("notification_group_count_max", OptionValueInteger(20))
                client.setOption("notification_group_size_max", OptionValueInteger(6))
                _me.value = client.getMe().getOrNull()
                setOnline(AppState.foreground)
            }
            is AuthorizationStateClosed -> {
                notificationLines.keys.forEach { Platform.cancelNotification(notificationKey(it)) }
                onClosed(this)
                scope.cancel()
            }
            else -> Unit
        }
        Accounts.authChanged()
    }

    suspend fun sendParameters(): Result<Unit> = client.setTdlibParameters(
        false, dbDir, filesDir, ByteArray(0),
        true, true, true, true,
        Prefs.apiId, Prefs.apiHash,
        Platform.systemLocale.language, Platform.deviceModel, Platform.systemVersion, "SKGram ${AppInfo.version}",
    ).unit()

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

    /** Makes sure a chat (e.g. from a search result) is known locally, then returns it. */
    suspend fun ensureChat(id: Long): ChatItem? = chats[id] ?: client.getChat(id).getOrNull()?.let { putChat(it); chats[id] }

    suspend fun loadMoreChats() {
        if (allChatsLoaded) return
        val r = client.loadChats(ChatListMain(), 100)
        if (r is TdlResult.Failure && r.code == 404) allChatsLoaded = true
    }

    suspend fun user(id: Long): User? = users[id] ?: client.getUser(id).getOrNull()?.also { users[id] = it }

    fun setOnline(online: Boolean) {
        if (!isReady) return
        scope.launch { client.setOption("online", OptionValueBoolean(online && !Prefs.ghost.value)) }
    }

    /** Downloads a file (if needed) and returns its local path. */
    suspend fun download(file: File, priority: Int = 16): String? {
        if (file.local.isDownloadingCompleted && file.local.path.isNotEmpty()) return file.local.path
        val f = client.downloadFile(file.id, priority, 0, 0, true).getOrNull() ?: return null
        return f.local.path.takeIf { f.local.isDownloadingCompleted && it.isNotEmpty() }
    }

    // --- notifications -------------------------------------------------------------------

    private fun notificationKey(groupId: Int) = (id shl 22) xor groupId

    private fun lineFor(type: dev.g000sha256.tdl.dto.NotificationType): String? {
        val n = type as? NotificationTypeNewMessage ?: return null
        val s = I18n.strings
        if (!n.showPreview) return s.newMessage
        val text = n.message.preview(s)
        val chat = chats[n.message.chatId]
        val isGroup = chat?.type is ChatTypeBasicGroup || (chat?.type as? ChatTypeSupergroup)?.isChannel == false
        val sender = if (isGroup) senderName(n.message.senderId) else null
        return if (sender != null) "$sender: $text" else text
    }

    private fun onNotificationGroup(u: UpdateNotificationGroup) {
        val lines = notificationLines.getOrPut(u.notificationGroupId) { LinkedHashMap() }
        u.removedNotificationIds.forEach { lines.remove(it) }
        u.addedNotifications.forEach { n -> lineFor(n.type)?.let { lines[n.id] = it } }
        showGroup(u.notificationGroupId, u.chatId, lines, posted = u.addedNotifications.isNotEmpty())
    }

    private fun onActiveGroup(g: NotificationGroup) {
        val lines = notificationLines.getOrPut(g.id) { LinkedHashMap() }
        g.notifications.forEach { n -> lineFor(n.type)?.let { lines[n.id] = it } }
        showGroup(g.id, g.chatId, lines, posted = true)
    }

    private fun showGroup(groupId: Int, chatId: Long, lines: Map<Int, String>, posted: Boolean) {
        val key = notificationKey(groupId)
        if (lines.isEmpty()) { Platform.cancelNotification(key); return }
        if (!posted || !Prefs.notifications.value || AppState.foreground) return
        Platform.notify(ChatNotification(key, id, chatId, chats[chatId]?.title ?: "SKGram", lines.values.toList().takeLast(6)))
    }

    // --- calls ---------------------------------------------------------------------------

    private fun onCall(call: Call) {
        val key = (id shl 22) xor 0x3FFFFF
        if (!call.isOutgoing && call.state is CallStatePending) {
            _incomingCall.value = call
            if (Prefs.notifications.value) scope.launch {
                val name = user(call.userId)?.displayName() ?: ""
                Platform.notify(ChatNotification(key, id, call.userId, "SKGram", listOf(I18n.strings.incomingCall.format(name))))
            }
        } else if (_incomingCall.value?.id == call.id) {
            _incomingCall.value = null
            Platform.cancelNotification(key)
        }
    }

    fun declineCall(call: Call) {
        _incomingCall.value = null
        scope.launch { client.discardCall(call.id, false, "", 0, false, 0) }
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
