package com.skgram.ui.screens

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Create
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.skgram.core.Account
import com.skgram.core.Accounts
import com.skgram.core.ChatItem
import com.skgram.core.Prefs
import com.skgram.core.displayName
import com.skgram.core.preview
import com.skgram.core.shortTime
import com.skgram.i18n.LocalStrings
import com.skgram.i18n.Strings
import com.skgram.ui.LocalAccount
import com.skgram.ui.Toaster
import com.skgram.ui.components.Avatar
import com.skgram.ui.components.Halo
import com.skgram.ui.components.SkTopBar
import com.skgram.ui.components.StripedBackground
import com.skgram.ui.theme.LocalSkColors
import com.skgram.ui.theme.SkewShape
import dev.g000sha256.tdl.dto.ChatListMain
import dev.g000sha256.tdl.dto.ChatTypeBasicGroup
import dev.g000sha256.tdl.dto.ChatTypePrivate
import dev.g000sha256.tdl.dto.ChatTypeSecret
import dev.g000sha256.tdl.dto.ChatTypeSupergroup
import dev.g000sha256.tdl.dto.ConnectionStateConnecting
import dev.g000sha256.tdl.dto.ConnectionStateConnectingToProxy
import dev.g000sha256.tdl.dto.ConnectionStateUpdating
import dev.g000sha256.tdl.dto.ConnectionStateWaitingForNetwork
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.launch

/** Quick filters over the main list; Telegram makes you build folders for these. */
enum class Folder(val label: (Strings) -> String, val test: (ChatItem) -> Boolean) {
    ALL({ it.tabAll }, { true }),
    UNREAD({ it.tabUnread }, { it.unread > 0 || it.mentions > 0 }),
    PRIVATE({ it.tabPrivate }, { it.type is ChatTypePrivate }),
    GROUPS({ it.tabGroups }, { it.type is ChatTypeBasicGroup || (it.type as? ChatTypeSupergroup)?.isChannel == false }),
    CHANNELS({ it.tabChannels }, { (it.type as? ChatTypeSupergroup)?.isChannel == true }),
    SECRET({ it.tabSecret }, { it.type is ChatTypeSecret }),
}

@Composable
fun ChatListScreen(onOpen: (Long) -> Unit, onSettings: () -> Unit, onNewChat: () -> Unit) {
    val account = LocalAccount.current
    val s = LocalStrings.current
    val chats by account.chatList.collectAsState()
    val connection by account.connection.collectAsState()
    val ghost by Prefs.ghost.collectAsState()
    val me by account.me.collectAsState()
    val accounts by Accounts.list.collectAsState()
    var folder by rememberSaveable { mutableStateOf(Folder.ALL) }
    var searching by rememberSaveable { mutableStateOf(false) }
    var query by rememberSaveable { mutableStateOf("") }
    var accountMenu by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()

    val shown = remember(chats, folder, query) {
        val q = query.trim()
        chats.filter { folder.test(it) && (q.isEmpty() || it.title.contains(q, ignoreCase = true)) }
    }
    val unreadByFolder = remember(chats) { Folder.entries.associateWith { f -> chats.count { f.test(it) && it.unread > 0 } } }
    val folders = remember(chats) { Folder.entries.filter { it != Folder.SECRET || chats.any(Folder.SECRET.test) } }

    LaunchedEffect(Unit) { account.loadMoreChats() }
    // Page in more chats as the user nears the end of the list.
    LaunchedEffect(listState) {
        snapshotFlow { listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0 }
            .distinctUntilChanged()
            .filter { it >= listState.layoutInfo.totalItemsCount - 15 }
            .collect { account.loadMoreChats() }
    }

    val status = when (connection) {
        is ConnectionStateWaitingForNetwork -> s.waitingNetwork
        is ConnectionStateConnecting, is ConnectionStateConnectingToProxy -> s.connecting
        is ConnectionStateUpdating -> s.updating
        else -> if (accounts.size > 1) me?.displayName() else null
    }

    StripedBackground {
        Column(Modifier.fillMaxSize()) {
            if (searching) {
                SearchBar(query, { query = it }, s) { searching = false; query = "" }
            } else {
                Box {
                    SkTopBar(
                        title = "SKGram" + if (ghost) " 👻" else "",
                        subtitle = status,
                        navigation = { Box(Modifier.padding(horizontal = 10.dp)) { Halo(size = 30.dp) } },
                        onTitleClick = { accountMenu = true },
                        actions = {
                            IconButton(onClick = { searching = true }) { Icon(Icons.Filled.Search, s.search, tint = Color.White) }
                            IconButton(onClick = onNewChat) { Icon(Icons.Filled.Create, s.newChat, tint = Color.White) }
                            IconButton(onClick = onSettings) { Icon(Icons.Filled.Settings, s.settings, tint = Color.White) }
                        },
                    )
                    AccountMenu(accountMenu, accounts, account) { accountMenu = false }
                }
            }
            FolderTabs(folders, folder, unreadByFolder) { folder = it }
            if (shown.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Halo(size = 64.dp, color = MaterialTheme.colorScheme.outline)
                        Text(s.noChats, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            } else {
                LazyColumn(state = listState, modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
                    items(shown, key = { it.id }, contentType = { 0 }) { chat ->
                        ChatRow(chat, onClick = { onOpen(chat.id) })
                    }
                    item { Spacer(Modifier.navigationBarsPadding()) }
                }
            }
        }
    }
}

/** Quick account switcher under the title. */
@Composable
private fun AccountMenu(open: Boolean, accounts: List<Account>, current: Account, onClose: () -> Unit) {
    val s = LocalStrings.current
    DropdownMenu(open, onClose) {
        accounts.forEach { a ->
            val me by a.me.collectAsState()
            DropdownMenuItem(
                text = { Text((if (a === current) "✓ " else "") + (me?.displayName() ?: "…")) },
                onClick = { onClose(); Accounts.switch(a) },
            )
        }
        DropdownMenuItem(
            text = { Text(s.addAccount) }, leadingIcon = { Icon(Icons.Filled.Add, null) },
            onClick = { onClose(); Accounts.add() },
        )
    }
}

@Composable
private fun SearchBar(query: String, onQuery: (String) -> Unit, s: Strings, onClose: () -> Unit) {
    val sk = LocalSkColors.current
    Box(Modifier.fillMaxWidth().background(sk.header).statusBarsPadding()) {
        TextField(
            query, onQuery,
            modifier = Modifier.fillMaxWidth().padding(8.dp),
            placeholder = { Text(s.searchHint) },
            singleLine = true,
            leadingIcon = { Icon(Icons.Filled.Search, null) },
            trailingIcon = { IconButton(onClick = onClose) { Icon(Icons.Filled.Close, null) } },
            shape = SkewShape(0.08f),
            colors = TextFieldDefaults.colors(focusedIndicatorColor = Color.Transparent, unfocusedIndicatorColor = Color.Transparent),
        )
    }
}

@Composable
private fun FolderTabs(folders: List<Folder>, selected: Folder, unread: Map<Folder, Int>, onSelect: (Folder) -> Unit) {
    val sk = LocalSkColors.current
    val s = LocalStrings.current
    LazyRow(
        Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface.copy(alpha = 0.9f)),
        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        items(folders) { f ->
            val on = f == selected
            Row(
                Modifier.clip(SkewShape(0.3f))
                    .background(if (on) sk.header else SolidColor(MaterialTheme.colorScheme.surfaceVariant))
                    .clickable { onSelect(f) }
                    .padding(horizontal = 16.dp, vertical = 7.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(f.label(s), color = if (on) Color.White else MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.labelLarge)
                val n = unread[f] ?: 0
                if (n > 0 && f != Folder.ALL) {
                    Spacer(Modifier.width(5.dp))
                    Text(n.toString(), color = if (on) Color.White else sk.badge, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Black)
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ChatRow(chat: ChatItem, onClick: () -> Unit) {
    val account = LocalAccount.current
    val s = LocalStrings.current
    val sk = LocalSkColors.current
    val clipboard = LocalClipboardManager.current
    val showIds by Prefs.showIds.collectAsState()
    val scope = rememberCoroutineScope()
    var menu by remember { mutableStateOf(false) }
    val last = chat.lastMessage
    val preview = remember(last?.id, last?.content, s) { last?.preview(s) ?: "" }
    val outgoingRead = last != null && last.isOutgoing && last.id <= chat.lastReadOutbox

    Box {
        Row(
            Modifier.fillMaxWidth()
                .background(if (chat.pinned) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f) else Color.Transparent)
                .combinedClickable(onClick = onClick, onLongClick = { menu = true })
                .padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Avatar(chat.id, chat.title, chat.photo)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (chat.type is ChatTypeSecret) Text("🔒 ", color = Color(0xFF14A37F))
                    Text(chat.title, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                        color = if (chat.type is ChatTypeSecret) Color(0xFF14A37F) else MaterialTheme.colorScheme.onSurface)
                    if (chat.pinned) Text(" 📌", style = MaterialTheme.typography.labelSmall)
                    Spacer(Modifier.weight(1f))
                    if (last != null && last.isOutgoing) {
                        Text(if (outgoingRead) "✓✓ " else "✓ ", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelSmall)
                    }
                    Text(shortTime(last?.date ?: 0), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        (if (last?.isOutgoing == true) s.you + ": " else "") + preview,
                        style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f),
                    )
                    if (chat.mentions > 0) Badge("@", sk.badge)
                    if (chat.unread > 0) Badge(if (chat.unread > 999) "999+" else chat.unread.toString(), MaterialTheme.colorScheme.primary)
                }
                if (showIds) Text("ID ${chat.id}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
            }
        }
        DropdownMenu(menu, { menu = false }) {
            DropdownMenuItem(text = { Text(if (chat.pinned) s.unpin else s.pin) }, onClick = {
                menu = false
                scope.launch { account.client.toggleChatIsPinned(ChatListMain(), chat.id, !chat.pinned) }
            })
            if (chat.unread > 0 || chat.mentions > 0) DropdownMenuItem(text = { Text(s.markRead) }, onClick = {
                menu = false
                // Marks read even in ghost mode: the user asked for it explicitly.
                scope.launch {
                    chat.lastMessage?.let { account.client.viewMessages(chat.id, longArrayOf(it.id), null, true) }
                    account.client.readAllChatMentions(chat.id)
                }
            })
            DropdownMenuItem(text = { Text(s.copyId) }, onClick = {
                menu = false
                clipboard.setText(AnnotatedString(chat.id.toString()))
                Toaster.show(s.copied)
            })
        }
    }
    HorizontalDivider(Modifier.padding(start = 78.dp), color = MaterialTheme.colorScheme.outline.copy(alpha = 0.25f))
}

@Composable
private fun Badge(text: String, color: Color) {
    Box(
        Modifier.padding(start = 6.dp).widthIn(min = 22.dp).clip(CircleShape).background(color).padding(horizontal = 6.dp, vertical = 2.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, color = Color.White, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
    }
}
