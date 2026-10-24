package com.skgram.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.unit.dp
import com.skgram.core.Account
import com.skgram.core.AppInfo
import com.skgram.core.Accounts
import com.skgram.core.PlatformBackHandler
import com.skgram.core.Prefs
import com.skgram.core.displayName
import com.skgram.i18n.Lang
import com.skgram.i18n.LocalStrings
import com.skgram.i18n.fmt
import com.skgram.ui.LocalAccount
import com.skgram.ui.components.Avatar
import com.skgram.ui.components.SkTopBar
import com.skgram.ui.components.StripedBackground
import com.skgram.ui.theme.LocalSkColors
import com.skgram.ui.theme.SkTheme
import com.skgram.ui.theme.SkewShape
import kotlinx.coroutines.launch

@Composable
fun SettingsScreen(onBack: () -> Unit) {
    PlatformBackHandler(onBack = onBack)
    val account = LocalAccount.current
    val s = LocalStrings.current
    val theme by Prefs.theme.collectAsState()
    val lang by Prefs.lang.collectAsState()
    val ghost by Prefs.ghost.collectAsState()
    val showIds by Prefs.showIds.collectAsState()
    val doubleTap by Prefs.doubleTapReply.collectAsState()
    val notifications by Prefs.notifications.collectAsState()
    val background by Prefs.background.collectAsState()
    val accounts by Accounts.list.collectAsState()
    var confirmLogout by remember { mutableStateOf(false) }

    StripedBackground {
        Column(Modifier.fillMaxSize()) {
            SkTopBar(s.settings, navigation = {
                IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, null, tint = Color.White) }
            })
            Column(
                Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(14.dp).navigationBarsPadding(),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                Card(s.accounts) {
                    accounts.forEach { AccountRow(it, current = it === account, showIds = showIds) }
                    Row(Modifier.fillMaxWidth().clickable { Accounts.add() }.padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Filled.Add, null, tint = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.width(12.dp))
                        Text(s.addAccount, color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.titleMedium)
                    }
                }
                Card(s.language) {
                    Chips(listOf("" to s.followSystem) + Lang.entries.map { it.tag to it.label }, lang?.tag ?: "") {
                        Prefs.setLang(Lang.byTag(it))
                    }
                }
                Card(s.theme) {
                    Chips(
                        listOf(
                            SkTheme.SYSTEM.name to s.followSystem,
                            SkTheme.SKY.name to s.themeSky,
                            SkTheme.NIGHT.name to s.themeNight,
                            SkTheme.SAKURA.name to s.themeSakura,
                        ),
                        theme.name,
                    ) { Prefs.setTheme(SkTheme.valueOf(it)) }
                }
                Card(s.notifications) {
                    Toggle(s.notifications, s.notificationsDesc, notifications, Prefs::setNotifications)
                    Toggle(s.background, s.backgroundDesc, background, Prefs::setBackground)
                }
                Card(s.extras) {
                    Toggle(s.ghostMode, s.ghostModeDesc, ghost) { v ->
                        Prefs.setGhost(v)
                        accounts.forEach { it.setOnline(true) }
                    }
                    Toggle(s.showIds, s.showIdsDesc, showIds, Prefs::setShowIds)
                    Toggle(s.doubleTapReply, s.doubleTapReplyDesc, doubleTap, Prefs::setDoubleTapReply)
                    Text(s.extrasMore, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Card {
                    Text(s.logout, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.fillMaxWidth().clickable { confirmLogout = true }.padding(vertical = 6.dp))
                }
                Text(s.versionFmt.fmt(AppInfo.version), style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.outline, modifier = Modifier.align(Alignment.CenterHorizontally))
            }
        }
    }
    if (confirmLogout) AlertDialog(
        onDismissRequest = { confirmLogout = false },
        title = { Text(s.logout) },
        text = { Text(s.logoutConfirm) },
        confirmButton = { TextButton(onClick = { confirmLogout = false; account.scope.launch { account.client.logOut() } }) { Text(s.ok) } },
        dismissButton = { TextButton(onClick = { confirmLogout = false }) { Text(s.cancel) } },
    )
}

@Composable
private fun AccountRow(a: Account, current: Boolean, showIds: Boolean) {
    val s = LocalStrings.current
    val me by a.me.collectAsState()
    Row(Modifier.fillMaxWidth().clickable { Accounts.switch(a) }.padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Avatar(me?.id ?: a.id.toLong(), me?.displayName() ?: "?", null, size = 46.dp)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(me?.displayName() ?: "…", style = MaterialTheme.typography.titleMedium)
            me?.let { Text("+" + it.phoneNumber + if (showIds) "  ·  ID ${it.id}" else "", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
        if (current) Text(s.currentAccount, color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelLarge)
    }
}

@Composable
private fun Card(title: String? = null, content: @Composable ColumnScope.() -> Unit) {
    Column(
        Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface, MaterialTheme.shapes.large).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        if (title != null) Text(title, style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.primary)
        content()
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Chips(options: List<Pair<String, String>>, selected: String, onSelect: (String) -> Unit) {
    val sk = LocalSkColors.current
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        options.forEach { (key, label) ->
            val on = key == selected
            Text(
                label,
                color = if (on) Color.White else MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.labelLarge,
                modifier = Modifier.clip(SkewShape(0.3f))
                    .background(if (on) sk.header else SolidColor(MaterialTheme.colorScheme.surfaceVariant))
                    .clickable { onSelect(key) }
                    .padding(horizontal = 16.dp, vertical = 8.dp),
            )
        }
    }
}

@Composable
private fun Toggle(title: String, desc: String, value: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().clickable { onChange(!value) }, verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(desc, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(value, onChange)
    }
}
