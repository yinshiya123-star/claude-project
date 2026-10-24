package com.skgram.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.skgram.core.ChatItem
import com.skgram.core.PlatformBackHandler
import com.skgram.core.displayName
import com.skgram.core.getOrNull
import com.skgram.core.getOrThrow
import com.skgram.i18n.LocalStrings
import com.skgram.i18n.fmt
import com.skgram.ui.LocalAccount
import com.skgram.ui.Toaster
import com.skgram.ui.components.Avatar
import com.skgram.ui.components.SkTopBar
import com.skgram.ui.components.StripedBackground
import com.skgram.ui.theme.SkewShape
import dev.g000sha256.tdl.dto.ChatTypeSupergroup
import dev.g000sha256.tdl.dto.User
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Start a chat or secret chat with a contact, or find and join public groups and channels. */
@Composable
fun NewChatScreen(onOpen: (Long) -> Unit, onBack: () -> Unit) {
    val account = LocalAccount.current
    val s = LocalStrings.current
    val scope = rememberCoroutineScope()
    var query by remember { mutableStateOf("") }
    val contacts = remember { mutableStateListOf<User>() }
    val publicChats = remember { mutableStateListOf<ChatItem>() }
    val joined = remember { mutableStateListOf<Long>() }
    PlatformBackHandler(onBack = onBack)

    LaunchedEffect(query) {
        delay(250) // debounce typing
        val q = query.trim()
        val ids = if (q.isEmpty()) account.client.getContacts().getOrNull()?.userIds
        else account.client.searchContacts(q, 50).getOrNull()?.userIds
        contacts.clear()
        contacts.addAll((ids ?: LongArray(0)).toList().mapNotNull { account.user(it) }.sortedBy { it.displayName().lowercase() })
        publicChats.clear()
        if (q.length >= 3) {
            val found = account.client.searchPublicChats(q, null).getOrNull()?.chatIds ?: LongArray(0)
            publicChats.addAll(found.toList().mapNotNull { account.ensureChat(it) })
        }
    }

    fun run(block: suspend () -> Long) {
        scope.launch { runCatching { block() }.onSuccess(onOpen).onFailure { Toaster.show(s.errorFmt.fmt(it.message)) } }
    }

    StripedBackground {
        Column(Modifier.fillMaxSize()) {
            SkTopBar(s.newChat, navigation = {
                IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, null, tint = Color.White) }
            })
            TextField(
                query, { query = it }, singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(10.dp),
                placeholder = { Text(s.search) },
                leadingIcon = { Icon(Icons.Filled.Search, null) },
                shape = SkewShape(0.06f),
                colors = TextFieldDefaults.colors(focusedIndicatorColor = Color.Transparent, unfocusedIndicatorColor = Color.Transparent),
            )
            LazyColumn(Modifier.fillMaxSize()) {
                item { Section(s.contacts) }
                if (contacts.isEmpty()) item { Text(s.noContacts, Modifier.padding(16.dp), color = MaterialTheme.colorScheme.onSurfaceVariant) }
                items(contacts, key = { "u${it.id}" }) { u ->
                    Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Avatar(u.id, u.displayName(), null, size = 44.dp)
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(u.displayName(), style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            u.usernames?.activeUsernames?.firstOrNull()?.let { Text("@$it", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                        }
                        OutlinedButton(onClick = { run { account.client.createPrivateChat(u.id, false).getOrThrow().id } }) { Text(s.messageAction) }
                        Spacer(Modifier.width(6.dp))
                        OutlinedButton(onClick = { run { account.client.createNewSecretChat(u.id).getOrThrow().id } }) { Text("🔒 " + s.secretChat) }
                    }
                }
                if (publicChats.isNotEmpty()) item { Section(s.globalSearch) }
                items(publicChats, key = { "c${it.id}" }) { c ->
                    Row(
                        Modifier.fillMaxWidth().clickable { onOpen(c.id) }.padding(horizontal = 14.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Avatar(c.id, c.title, c.photo, size = 44.dp)
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(c.title, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(if ((c.type as? ChatTypeSupergroup)?.isChannel == true) s.typeChannel else s.typeGroup,
                                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        if (c.id in joined) Text(s.joined, color = MaterialTheme.colorScheme.primary)
                        else OutlinedButton(onClick = {
                            scope.launch {
                                runCatching { account.client.joinChat(c.id).getOrThrow() }
                                    .onSuccess { joined += c.id }
                                    .onFailure { Toaster.show(s.errorFmt.fmt(it.message)) }
                            }
                        }) { Text(s.join) }
                    }
                }
                item { Spacer(Modifier.navigationBarsPadding()) }
            }
        }
    }
}

@Composable
private fun Section(title: String) {
    Text(
        title, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)).padding(horizontal = 16.dp, vertical = 6.dp),
    )
}
