package dev.tomato.hongguo.tv

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Border
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.Glow
import androidx.tv.material3.Icon
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import coil.compose.AsyncImage

// Design tokens; the artboards are 1920x1080 px at 1dp = 2px, so every px value is halved here.
val Accent = Color(0xFFFF3B5C)
val Fg = Color(0xFFF1F1F1)
val FgDim = Color(0x80F1F1F1)
val FocusBg = Color(0xFFEBF0F6)
val FocusFg = Color(0xFF16171A)
val PageBg = Color(0xFF0B0B0D)
val Muted = Color(0xFFC9CDD3)

private fun icon(d: String, stroke: Boolean) = ImageVector.Builder(
    defaultWidth = 24.dp, defaultHeight = 24.dp, viewportWidth = 24f, viewportHeight = 24f,
).addPath(
    pathData = PathParser().parsePathString(d).toNodes(),
    fill = if (stroke) null else SolidColor(Color.White),
    stroke = if (stroke) SolidColor(Color.White) else null,
    strokeLineWidth = if (stroke) 2f else 0f,
    strokeLineCap = StrokeCap.Round,
    strokeLineJoin = StrokeJoin.Round,
).build()

object Icons {
    val Episodes = icon("M3 4h7v7H3zM14 4h7v7h-7zM3 14h7v7H3zM14 14h7v7h-7z", stroke = true)
    val Pause = icon("M6 4h4v16H6zM14 4h4v16h-4z", stroke = false)
    val Play = icon("M7 4.5v15l13-7.5z", stroke = false)
    val Star = icon("M12 2.8l2.9 5.9 6.5.9-4.7 4.6 1.1 6.4L12 17.6l-5.8 3 1.1-6.4-4.7-4.6 6.5-.9z", stroke = false)
    val StarLine = icon("M12 2.8l2.9 5.9 6.5.9-4.7 4.6 1.1 6.4L12 17.6l-5.8 3 1.1-6.4-4.7-4.6 6.5-.9z", stroke = true)
    val More = icon("M3 12a2 2 0 1 0 4 0a2 2 0 1 0-4 0M10 12a2 2 0 1 0 4 0a2 2 0 1 0-4 0M17 12a2 2 0 1 0 4 0a2 2 0 1 0-4 0", stroke = false)
    val Home = icon("M3 11l9-7 9 7v9a1 1 0 0 1-1 1h-5v-6H9v6H4a1 1 0 0 1-1-1z", stroke = true)
    val Theater = icon("M4 5h16v14H4zM9 5v14M4 10h5M4 14h5", stroke = true)
    val Search = icon("M11 4a7 7 0 1 0 0 14 7 7 0 0 0 0-14zM20 20l-4-4", stroke = true)
    val Follow = icon("M12 3l2.8 5.8 6.2.9-4.5 4.4 1 6.2L12 17.4 6.5 20.3l1-6.2L3 9.7l6.2-.9z", stroke = true)
    val History = icon("M12 7v5l3 2M3.5 12a8.5 8.5 0 1 0 2.5-6L3.5 8.5M3.5 4v4.5H8", stroke = true)
    val Settings = icon(
        "M9 12a3 3 0 1 0 6 0a3 3 0 1 0-6 0M19.4 15a1.7 1.7 0 0 0 .3 1.8l.1.1a2 2 0 1 1-2.8 2.8l-.1-.1a1.7 1.7 0 0 0-1.8-.3 " +
            "1.7 1.7 0 0 0-1 1.5V21a2 2 0 1 1-4 0v-.1a1.7 1.7 0 0 0-1.1-1.5 1.7 1.7 0 0 0-1.8.3l-.1.1a2 2 0 1 1-2.8-2.8l.1-.1" +
            "a1.7 1.7 0 0 0 .3-1.8 1.7 1.7 0 0 0-1.5-1H3a2 2 0 1 1 0-4h.1a1.7 1.7 0 0 0 1.5-1.1 1.7 1.7 0 0 0-.3-1.8l-.1-.1" +
            "a2 2 0 1 1 2.8-2.8l.1.1a1.7 1.7 0 0 0 1.8.3H9a1.7 1.7 0 0 0 1-1.5V3a2 2 0 1 1 4 0v.1a1.7 1.7 0 0 0 1 1.5 1.7 1.7 0 0 0 " +
            "1.8-.3l.1-.1a2 2 0 1 1 2.8 2.8l-.1.1a1.7 1.7 0 0 0-.3 1.8V9a1.7 1.7 0 0 0 1.5 1H21a2 2 0 1 1 0 4h-.1a1.7 1.7 0 0 0-1.5 1z",
        stroke = true,
    )
    val Keyboard = icon("M4 6h16a2 2 0 0 1 2 2v8a2 2 0 0 1-2 2H4a2 2 0 0 1-2-2V8a2 2 0 0 1 2-2zM6 10h.01M10 10h.01M14 10h.01M18 10h.01M7 14h10", stroke = true)
    val Mic = icon("M9 6a3 3 0 0 1 6 0v6a3 3 0 0 1-6 0zM5 11a7 7 0 0 0 14 0M12 18v3", stroke = true)
    val Down = icon("M12 5v14M5 12l7 7 7-7", stroke = true)
}

@Composable
fun T(text: String, size: TextUnit, color: Color = Fg, weight: FontWeight = FontWeight.Normal, maxLines: Int = 1, modifier: Modifier = Modifier) =
    Text(text, modifier, color = color, fontSize = size, fontWeight = weight, maxLines = maxLines, overflow = TextOverflow.Ellipsis, lineHeight = size * 1.4f)

/** A focusable pill: solid #EBF0F6 with dark text when focused (TikTok TV style). */
@Composable
fun Pill(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    selected: Boolean = false,
    icon: ImageVector? = null,
    size: TextUnit = 14.sp,
    height: Dp = 36.dp,
    radius: Dp = 6.dp,
    container: Color = if (selected) Color(0x33FFFFFF) else Color(0x14FFFFFF),
    content: Color = if (selected) Fg else Color(0xB3F1F1F1),
) {
    Surface(
        onClick = onClick,
        modifier = modifier.height(height),
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(radius)),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = container, contentColor = content,
            focusedContainerColor = FocusBg, focusedContentColor = FocusFg,
            pressedContainerColor = FocusBg, pressedContentColor = FocusFg,
        ),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1f),
    ) {
        Row(
            Modifier.fillMaxSize().padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally),
        ) {
            if (icon != null) Icon(icon, null, Modifier.size(16.dp))
            Text(text, fontSize = size, fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal, maxLines = 1)
        }
    }
}

/** A poster card: focus draws a 5dp white border, scales 1.06 and casts a shadow. */
@Composable
fun PosterCard(
    title: String,
    cover: String?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    posterHeight: Dp = 190.dp,
    meta: String = "",
    rank: Int? = null,
    progress: Float? = null,
) {
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(10.dp)
    Column(modifier, verticalArrangement = Arrangement.spacedBy(7.dp)) {
        Surface(
            onClick = onClick,
            modifier = Modifier.fillMaxWidth().height(posterHeight).onFocusChanged { focused = it.isFocused },
            shape = ClickableSurfaceDefaults.shape(shape),
            colors = ClickableSurfaceDefaults.colors(containerColor = Color(0xFF2A2A30), focusedContainerColor = Color(0xFF2A2A30)),
            scale = ClickableSurfaceDefaults.scale(focusedScale = 1.06f),
            border = ClickableSurfaceDefaults.border(focusedBorder = Border(BorderStroke(5.dp, Color.White), shape = shape)),
            glow = ClickableSurfaceDefaults.glow(focusedGlow = Glow(Color(0xB3000000), 15.dp)),
        ) {
            AsyncImage(cover, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            Box(
                Modifier.fillMaxWidth().height(60.dp).align(Alignment.BottomStart)
                    .background(Brush.verticalGradient(listOf(Color.Transparent, Color(0xB3000000)))),
            )
            if (rank != null) {
                Box(
                    Modifier.background(if (rank <= 3) Accent else Color(0x8C000000), RoundedCornerShape(topStart = 10.dp, bottomEnd = 8.dp))
                        .padding(horizontal = 6.dp, vertical = 2.dp).width(14.dp),
                    contentAlignment = Alignment.Center,
                ) { T("$rank", 13.sp, Color.White, FontWeight.Black) }
            }
            T(meta, 11.sp, FocusBg, modifier = Modifier.align(Alignment.BottomStart).padding(start = 8.dp, bottom = if (progress != null) 13.dp else 7.dp))
            if (progress != null) {
                Box(Modifier.fillMaxWidth().height(4.dp).align(Alignment.BottomStart).background(Color(0x33FFFFFF))) {
                    Box(Modifier.fillMaxWidth(progress.coerceIn(0f, 1f)).height(4.dp).background(Accent))
                }
            }
        }
        T(title, 14.sp, if (focused) Color.White else Color(0xBFF1F1F1), if (focused) FontWeight.Bold else FontWeight.Normal)
    }
}

/** A 37dp round control button with a 2.5dp ring when focused. */
@Composable
fun RoundButton(label: String, onClick: () -> Unit, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    Column(Modifier.width(52.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Surface(
            onClick = onClick,
            modifier = modifier.size(37.dp).onFocusChanged { focused = it.isFocused },
            shape = ClickableSurfaceDefaults.shape(CircleShape),
            colors = ClickableSurfaceDefaults.colors(
                containerColor = Color(0x14FFFFFF), contentColor = Fg,
                focusedContainerColor = Color(0x38FFFFFF), focusedContentColor = Fg,
            ),
            scale = ClickableSurfaceDefaults.scale(focusedScale = 1f),
            border = ClickableSurfaceDefaults.border(focusedBorder = Border(BorderStroke(2.5.dp, Color(0xE6FFFFFF)), shape = CircleShape)),
        ) { Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { content() } }
        Box(Modifier.height(15.dp)) { if (focused) T(label, 11.sp, Color.White) }
    }
}
