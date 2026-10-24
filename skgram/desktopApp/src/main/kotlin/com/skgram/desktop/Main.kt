package com.skgram.desktop

import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.key.key
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Notification
import androidx.compose.ui.window.Tray
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.application
import androidx.compose.ui.window.isTraySupported
import androidx.compose.ui.window.rememberTrayState
import androidx.compose.ui.window.rememberWindowState
import com.skgram.core.Accounts
import com.skgram.core.AppInfo
import com.skgram.core.BackEvents
import com.skgram.core.DesktopNotifier
import com.skgram.core.Prefs
import com.skgram.i18n.I18n
import com.skgram.ui.SkgramApp

fun main() {
    AppInfo.version = System.getProperty("skgram.version") ?: "dev"
    System.getenv("TG_API_ID")?.toIntOrNull()?.let { Prefs.builtInApiId = it }
    System.getenv("TG_API_HASH")?.let { Prefs.builtInApiHash = it }
    Prefs.load()
    Accounts.start()

    application {
        var visible by remember { mutableStateOf(true) }
        val background by Prefs.background.collectAsState()
        // Without a system tray there'd be no way back to a hidden window, so then closing quits.
        val keepInTray = background && isTraySupported
        val tray = rememberTrayState()
        val icon = painterResource("skgram.png")
        LaunchedEffect(Unit) {
            DesktopNotifier.show = { n -> tray.sendNotification(Notification(n.title, n.lines.joinToString("\n"), Notification.Type.None)) }
        }
        // With "stay connected" on, closing the window keeps SKGram running in the tray.
        if (keepInTray) Tray(
            icon = icon,
            state = tray,
            tooltip = "SKGram",
            onAction = { visible = true },
            menu = {
                Item("${I18n.strings.open} SKGram", onClick = { visible = true })
                Item(I18n.strings.quit, onClick = ::exitApplication)
            },
        )
        Window(
            onCloseRequest = { if (keepInTray) visible = false else exitApplication() },
            visible = visible,
            title = "SKGram",
            icon = icon,
            state = rememberWindowState(size = DpSize(460.dp, 820.dp), position = WindowPosition.PlatformDefault),
            onPreviewKeyEvent = { e ->
                if (e.key == Key.Escape && e.type == KeyEventType.KeyDown) BackEvents.flow.tryEmit(Unit) else false
            },
        ) {
            window.minimumSize = java.awt.Dimension(360, 560)
            window.addWindowFocusListener(object : java.awt.event.WindowFocusListener {
                override fun windowGainedFocus(e: java.awt.event.WindowEvent?) = Accounts.setForeground(true)
                override fun windowLostFocus(e: java.awt.event.WindowEvent?) = Accounts.setForeground(false)
            })
            SkgramApp()
        }
    }
}
