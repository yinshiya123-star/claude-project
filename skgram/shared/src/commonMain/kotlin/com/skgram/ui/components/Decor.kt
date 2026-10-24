package com.skgram.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.skgram.ui.theme.LocalSkColors
import com.skgram.ui.theme.SkewShape
import com.skgram.ui.theme.TitleTag

/** A halo: a ring with short rays, the app's emblem. */
@Composable
fun Halo(modifier: Modifier = Modifier, color: Color = LocalSkColors.current.halo, size: Dp = 28.dp) {
    Canvas(modifier.size(size)) {
        val r = this.size.minDimension / 2
        val c = center
        drawCircle(color, radius = r * 0.62f, center = c, style = Stroke(width = r * 0.16f))
        for (i in 0 until 8) {
            rotate(i * 45f, c) {
                drawLine(color, Offset(c.x, c.y - r * 0.80f), Offset(c.x, c.y - r * 0.98f), strokeWidth = r * 0.12f)
            }
        }
        drawCircle(color.copy(alpha = 0.35f), radius = r * 0.30f, center = c)
    }
}

/** Faint diagonal stripes behind content, like the game's menus. */
fun Modifier.stripes(color: Color, gap: Float = 46f): Modifier = drawBehind {
    var x = -size.height
    while (x < size.width) {
        drawLine(color, Offset(x, size.height), Offset(x + size.height, 0f), strokeWidth = 14f)
        x += gap
    }
}

@Composable
fun StripedBackground(content: @Composable BoxScope.() -> Unit) {
    Box(
        Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).stripes(LocalSkColors.current.stripe),
        content = content,
    )
}

/** Gradient header with the title on a slanted white tag. */
@Composable
fun SkTopBar(
    title: String,
    subtitle: String? = null,
    navigation: (@Composable () -> Unit)? = null,
    actions: @Composable RowScope.() -> Unit = {},
    onTitleClick: (() -> Unit)? = null,
) {
    val sk = LocalSkColors.current
    Box(Modifier.fillMaxWidth().background(sk.header).stripes(Color(0x14FFFFFF), 38f).statusBarsPadding()) {
        Row(Modifier.fillMaxWidth().height(60.dp).padding(horizontal = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            if (navigation != null) navigation() else Spacer(Modifier.width(10.dp))
            Box(
                Modifier.weight(1f, fill = false)
                    .clip(SkewShape())
                    .background(Color.White.copy(alpha = 0.95f))
                    .let { if (onTitleClick != null) it.clickable(onClick = onTitleClick) else it }
                    .padding(horizontal = 18.dp, vertical = 4.dp)
            ) {
                androidx.compose.foundation.layout.Column {
                    Text(title, style = TitleTag, color = sk.headerStart, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (subtitle != null) {
                        Text(subtitle, style = MaterialTheme.typography.labelSmall, color = sk.headerStart.copy(alpha = 0.75f), maxLines = 1)
                    }
                }
            }
            Spacer(Modifier.weight(1f))
            actions()
        }
    }
}

/** Slanted primary button. */
@Composable
fun SkButton(text: String, enabled: Boolean = true, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val sk = LocalSkColors.current
    Box(
        modifier
            .clip(SkewShape(0.25f))
            .background(if (enabled) sk.header else androidx.compose.ui.graphics.SolidColor(Color(0x55888888)))
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 34.dp, vertical = 14.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, color = Color.White, style = MaterialTheme.typography.labelLarge)
    }
}
