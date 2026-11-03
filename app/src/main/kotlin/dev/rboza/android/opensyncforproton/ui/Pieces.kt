package dev.rboza.android.opensyncforproton.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Warning
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.rboza.android.opensyncforproton.R

/** A rounded card on the screen's background. */
@Composable
fun SectionCard(
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.surfaceContainer,
    corner: Dp = 20.dp,
    content: @Composable ColumnScope.() -> Unit,
) {
    Surface(modifier.fillMaxWidth(), shape = RoundedCornerShape(corner), color = color) { Column(content = content) }
}

/** A section's heading, in the primary colour; TalkBack reads it as a heading. */
@Composable
fun SectionTitle(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        modifier.padding(start = 4.dp, top = 8.dp).semantics { heading() },
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
    )
}

/** The account's initial in a circle; left out by TalkBack, since the name is next to it. */
@Composable
fun Avatar(name: String, size: Dp = 44.dp) {
    Box(
        Modifier.size(size).clip(CircleShape).background(MaterialTheme.colorScheme.primaryContainer).clearAndSetSemantics {},
        contentAlignment = Alignment.Center,
    ) {
        Text(
            name.firstOrNull(Char::isLetterOrDigit)?.uppercase() ?: "?",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onPrimaryContainer,
        )
    }
}

/**
 * The app's mark: the launcher icon's glyph on its tile, in the icon's own colours whatever the theme. The glyph's
 * 108-unit canvas is drawn half as large again as the mark, so its visible 72-unit square fills it, as on the launcher.
 */
@Composable
fun BrandMark(size: Dp = 32.dp) {
    Box(
        Modifier.size(size).clip(RoundedCornerShape(size * 0.3f)).background(colorResource(R.color.icon_tile)),
        contentAlignment = Alignment.Center,
    ) {
        Image(painterResource(R.drawable.ic_launcher_foreground), contentDescription = null, Modifier.requiredSize(size * 1.5f))
    }
}

enum class Tone { INFO, ERROR }

/** A note across the screen, with its [action] (Dismiss, Log in) below it at the end. */
@Composable
fun Banner(text: String, tone: Tone, action: (@Composable RowScope.() -> Unit)? = null) {
    val colors = MaterialTheme.colorScheme
    val container = if (tone == Tone.ERROR) colors.errorContainer else colors.secondaryContainer
    val content = if (tone == Tone.ERROR) colors.onErrorContainer else colors.onSecondaryContainer
    SectionCard(color = container) {
        Row(Modifier.padding(16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Icon(if (tone == Tone.ERROR) Icons.Outlined.Warning else Icons.Outlined.Info, contentDescription = null, tint = content)
            Text(text, style = MaterialTheme.typography.bodyMedium, color = content)
        }
        if (action != null) {
            Row(
                Modifier.fillMaxWidth().padding(start = 8.dp, end = 8.dp, bottom = 8.dp),
                horizontalArrangement = Arrangement.End,
                content = action,
            )
        }
    }
}

/** An open eye: show the password (drawn here; `material-icons-core` has no eye). */
val EyeIcon: ImageVector = eye(crossed = false)

/** A crossed-out eye: hide the password. */
val EyeCrossedIcon: ImageVector = eye(crossed = true)

private fun eye(crossed: Boolean): ImageVector {
    val stroke = SolidColor(Color.Black)
    val builder = ImageVector.Builder(if (crossed) "EyeCrossed" else "Eye", 24.dp, 24.dp, 24f, 24f)
    fun line(data: String) = builder.addPath(
        addPathNodes(data), stroke = stroke, strokeLineWidth = 2f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round,
    )
    line("M2,12 C5,6.5 8.5,5 12,5 C15.5,5 19,6.5 22,12 C19,17.5 15.5,19 12,19 C8.5,19 5,17.5 2,12 Z")
    line("M15,12 A3,3 0 1,1 9,12 A3,3 0 1,1 15,12 Z")
    if (crossed) line("M3,3 L21,21")
    return builder.build()
}
