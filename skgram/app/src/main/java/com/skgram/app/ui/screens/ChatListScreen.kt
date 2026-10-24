package com.skgram.app.ui.screens

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
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
import androidx.compose.material.icons.filled.Close
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
import androidx.compose.runtime.derivedStateOf
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.skgram.app.R
import com.skgram.app.core.ChatItem
import com.skgram.app.core.Prefs
import com.skgram.app.core.Tg
import com.skgram.app.core.preview
import com.skgram.app.core.shortTime
import com.skgram.app.ui.components.Avatar
import com.skgram.app.ui.components.Halo
import com.skgram.app.ui.components.SkTopBar
import com.skgram.app.ui.components.StripedBackground
import com.skgram.app.ui.theme.LocalSkColors
import com.skgram.app.ui.theme.SkewShape
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
enum class Folder(val label: Int, val test: (ChatItem) -> Boolean) {
    ALL(R.string.tab_all, { true }),
    UNREAD(R.string.tab_unread, { it.unread > 0 || it.mentions > 0 }),
    PRIVATE(R.string.tab_private, { it.type is ChatTypePrivate || it.type is ChatTypeSecret }),
    GROUPS(R.string.tab_groups, { it.type is ChatTypeBasicGroup || (it.type as? ChatTypeSupergroup)?.isChannel == false }),
    CHANNELS(R.string.tab_channels, { (it.type as? ChatTypeSupergroup)?.isChannel == true }),
}

fun copyText(ctx: Context, text: String) {
    (ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("SKGram", text))
    Toast.makeText(ctx, R.string.copied, Toast.LENGTH_SHORT).show()
}

@Composable
fun ChatListScreen(onOpen: (Long) -> Unit, onSettings: () -> Unit) {
    val chats by Tg.chatList.collectAsState()
    val connection by Tg.connection.collectAsState()
    val ghost by Prefs.ghost.collectAsState()
    var folder by rememberSaveable { mutableStateOf(Folder.ALL) }
    var searching by rememberSaveable { mutableStateOf(false) }
    var query by rememberSaveable { mutableStateOf("") }
    val listState = rememberLazyListState()

    val shown = remember(chats, folder, query) {
        val q = query.trim()
        chats.filter { folder.test(it) && (q.isEmpty() || it.title.contains(q, ignoreCase = true)) }
    }
    val unreadByFolder = remember(chats) { Folder.entries.associateWith { f -> chats.count { f.test(it) && it.unread > 0 } } }

    LaunchedEffect(Unit) { Tg.loadMoreChats() }
    // Page in more chats as the user nears the end of the list.
    LaunchedEffect(listState) {
        snapshotFlow { listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0 }
            .distinctUntilChanged()
            .filter { it >= listState.layoutInfo.totalItemsCount - 15 }
            .collect { Tg.loadMoreChats() }
    }

    val status = when (connection) {
        is ConnectionStateWaitingForNetwork -> stringResource(R.string.waiting_network)
        is ConnectionStateConnecting, is ConnectionStateConnectingToProxy -> stringResource(R.string.connecting)
        is ConnectionStateUpdating -> stringResource(R.string.updating)
        else -> null
    }

    StripedBackground {
        Column(Modifier.fillMaxSize()) {
            if (searching) {
                SearchBar(query, { query = it }) { searching = false; query = "" }
            } else {
                SkTopBar(
                    title = "SKGram" + if (ghost) " 👻" else "",
                    subtitle = status,
                    navigation = { Box(Modifier.padding(horizontal = 10.dp)) { Halo(size = 30.dp) } },
                    actions = {
                        IconButton(onClick = { searching = true }) { Icon(Icons.Filled.Search, stringResource(R.string.search), tint = Color.White) }
                        IconButton(onClick = onSettings) { Icon(Icons.Filled.Settings, stringResource(R.string.settings), tint = Color.White) }
                    },
                )
            }
            FolderTabs(folder, unreadByFolder) { folder = it }
            if (shown.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Halo(size = 64.dp, color = MaterialTheme.colorScheme.outline)
                        Text(stringResource(R.string.no_chats), color = MaterialTheme.colorScheme.onSurfaceVariant)
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

@Composable
private fun SearchBar(query: String, onQuery: (String) -> Unit, onClose: () -> Unit) {
    val sk = LocalSkColors.current
    Box(Modifier.fillMaxWidth().background(sk.header).statusBarsPadding()) {
        TextField(
            query, onQuery,
            modifier = Modifier.fillMaxWidth().padding(8.dp),
            placeholder = { Text(stringResource(R.string.search_hint)) },
            singleLine = true,
            leadingIcon = { Icon(Icons.Filled.Search, null) },
            trailingIcon = { IconButton(onClick = onClose) { Icon(Icons.Filled.Close, null) } },
            shape = SkewShape(0.08f),
            colors = TextFieldDefaults.colors(focusedIndicatorColor = Color.Transparent, unfocusedIndicatorColor = Color.Transparent),
        )
    }
}

@Composable
private fun FolderTabs(selected: Folder, unread: Map<Folder, Int>, onSelect: (Folder) -> Unit) {
    val sk = LocalSkColors.current
    LazyRow(
        Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface.copy(alpha = 0.9f)),
        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        items(Folder.entries) { f ->
            val on = f == selected
            Row(
                Modifier.clip(SkewShape(0.3f))
                    .background(if (on) sk.header else androidx.compose.ui.graphics.SolidColor(MaterialTheme.colorScheme.surfaceVariant))
                    .clickable { onSelect(f) }
                    .padding(horizontal = 16.dp, vertical = 7.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(stringResource(f.label), color = if (on) Color.White else MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.labelLarge)
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
    val ctx = LocalContext.current
    val sk = LocalSkColors.current
    val showIds by Prefs.showIds.collectAsState()
    val scope = rememberCoroutineScope()
    var menu by remember { mutableStateOf(false) }
    val last = chat.lastMessage
    val preview = remember(last?.id, last?.content) { last?.content?.preview(ctx) ?: "" }
    val outgoingRead by remember(chat) { derivedStateOf { last != null && last.isOutgoing && last.id <= chat.lastReadOutbox } }

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
                    Text(chat.title, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false))
                    if (chat.pinned) Text(" 📌", style = MaterialTheme.typography.labelSmall)
                    Spacer(Modifier.weight(1f))
                    if (last != null && last.isOutgoing) {
                        Text(if (outgoingRead) "✓✓ " else "✓ ", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelSmall)
                    }
                    Text(shortTime(ctx, last?.date ?: 0), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        (if (last?.isOutgoing == true) stringResource(R.string.you) + ": " else "") + preview,
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
            DropdownMenuItem(text = { Text(stringResource(if (chat.pinned) R.string.unpin else R.string.pin)) }, onClick = {
                menu = false
                scope.launch { Tg.client.toggleChatIsPinned(ChatListMain(), chat.id, !chat.pinned) }
            })
            if (chat.unread > 0 || chat.mentions > 0) DropdownMenuItem(text = { Text(stringResource(R.string.mark_read)) }, onClick = {
                menu = false
                scope.launch { markChatRead(chat) }
            })
            DropdownMenuItem(text = { Text(stringResource(R.string.copy_id)) }, onClick = { menu = false; copyText(ctx, chat.id.toString()) })
        }
    }
    HorizontalDivider(Modifier.padding(start = 78.dp), color = MaterialTheme.colorScheme.outline.copy(alpha = 0.25f))
}

/** Marks everything read even in ghost mode: the user asked for it explicitly. */
private suspend fun markChatRead(chat: ChatItem) {
    chat.lastMessage?.let { Tg.client.viewMessages(chat.id, longArrayOf(it.id), null, true) }
    Tg.client.readAllChatMentions(chat.id)
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
