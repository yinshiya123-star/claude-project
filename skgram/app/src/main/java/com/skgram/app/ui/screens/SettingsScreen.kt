package com.skgram.app.ui.screens

import androidx.activity.compose.BackHandler
import androidx.appcompat.app.AppCompatDelegate
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.os.LocaleListCompat
import com.skgram.app.BuildConfig
import com.skgram.app.R
import com.skgram.app.core.Prefs
import com.skgram.app.core.Tg
import com.skgram.app.core.displayName
import com.skgram.app.core.getOrNull
import com.skgram.app.ui.components.Avatar
import com.skgram.app.ui.components.SkTopBar
import com.skgram.app.ui.components.StripedBackground
import com.skgram.app.ui.theme.LocalSkColors
import com.skgram.app.ui.theme.SkTheme
import com.skgram.app.ui.theme.SkewShape
import dev.g000sha256.tdl.dto.User
import kotlinx.coroutines.launch

/** Language choices, each shown in its own language. Empty tag = follow the system. */
private val languages = listOf("" to null, "zh-CN" to "简体中文", "zh-TW" to "繁體中文", "ko" to "한국어", "ja" to "日本語", "en" to "English")

@Composable
fun SettingsScreen(onBack: () -> Unit) {
    BackHandler(onBack = onBack)
    val scope = rememberCoroutineScope()
    val theme by Prefs.theme.collectAsState()
    val ghost by Prefs.ghost.collectAsState()
    val showIds by Prefs.showIds.collectAsState()
    val doubleTap by Prefs.doubleTapReply.collectAsState()
    var me by remember { mutableStateOf<User?>(null) }
    var confirmLogout by remember { mutableStateOf(false) }
    val currentLang = AppCompatDelegate.getApplicationLocales().toLanguageTags()
    LaunchedEffect(Unit) { me = Tg.client.getMe().getOrNull() }

    StripedBackground {
        Column(Modifier.fillMaxSize()) {
            SkTopBar(stringResource(R.string.settings), navigation = {
                IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, null, tint = Color.White) }
            })
            Column(
                Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(14.dp).navigationBarsPadding(),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                me?.let { u ->
                    Card {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Avatar(u.id, u.displayName(), null, size = 56.dp)
                            Spacer(Modifier.width(14.dp))
                            Column {
                                Text(u.displayName(), style = MaterialTheme.typography.titleMedium)
                                Text("+" + u.phoneNumber, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                if (showIds) Text("ID ${u.id}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
                            }
                        }
                    }
                }
                Card(stringResource(R.string.language)) {
                    Chips(languages.map { (tag, name) -> tag to (name ?: stringResource(R.string.follow_system)) }, currentLang) { tag ->
                        AppCompatDelegate.setApplicationLocales(
                            if (tag.isEmpty()) LocaleListCompat.getEmptyLocaleList() else LocaleListCompat.forLanguageTags(tag)
                        )
                    }
                }
                Card(stringResource(R.string.theme)) {
                    Chips(
                        listOf(
                            SkTheme.SYSTEM.name to stringResource(R.string.follow_system),
                            SkTheme.SKY.name to stringResource(R.string.theme_sky),
                            SkTheme.NIGHT.name to stringResource(R.string.theme_night),
                            SkTheme.SAKURA.name to stringResource(R.string.theme_sakura),
                        ),
                        theme.name,
                    ) { Prefs.setTheme(SkTheme.valueOf(it)) }
                }
                Card(stringResource(R.string.extras)) {
                    Toggle(stringResource(R.string.ghost_mode), stringResource(R.string.ghost_mode_desc), ghost) {
                        Prefs.setGhost(it); Tg.setOnline(true)
                    }
                    Toggle(stringResource(R.string.show_ids), stringResource(R.string.show_ids_desc), showIds, Prefs::setShowIds)
                    Toggle(stringResource(R.string.double_tap_reply), stringResource(R.string.double_tap_reply_desc), doubleTap, Prefs::setDoubleTapReply)
                    Text(stringResource(R.string.extras_more), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Card {
                    Text(stringResource(R.string.logout), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.fillMaxWidth().clickable { confirmLogout = true }.padding(vertical = 6.dp))
                }
                Text(stringResource(R.string.version_fmt, BuildConfig.VERSION_NAME), style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.outline, modifier = Modifier.align(Alignment.CenterHorizontally))
            }
        }
    }
    if (confirmLogout) AlertDialog(
        onDismissRequest = { confirmLogout = false },
        title = { Text(stringResource(R.string.logout)) },
        text = { Text(stringResource(R.string.logout_confirm)) },
        confirmButton = { TextButton(onClick = { confirmLogout = false; scope.launch { Tg.client.logOut() } }) { Text(stringResource(R.string.ok)) } },
        dismissButton = { TextButton(onClick = { confirmLogout = false }) { Text(stringResource(R.string.cancel)) } },
    )
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
