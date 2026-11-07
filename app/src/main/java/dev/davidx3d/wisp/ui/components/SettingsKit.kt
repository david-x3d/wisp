package dev.davidx3d.wisp.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsBottomHeight
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberTopAppBarState
import androidx.compose.material3.toShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.graphics.shapes.RoundedPolygon
import dev.davidx3d.wisp.i18n.tr

/** Tonal pairs for icon badges, so every row gets its own colour like Pixel settings. */
enum class Tint { PRIMARY, SECONDARY, TERTIARY, ERROR, NEUTRAL }

@Composable
private fun Tint.colors(): Pair<Color, Color> {
    val c = MaterialTheme.colorScheme
    return when (this) {
        Tint.PRIMARY -> c.primaryContainer to c.onPrimaryContainer
        Tint.SECONDARY -> c.secondaryContainer to c.onSecondaryContainer
        Tint.TERTIARY -> c.tertiaryContainer to c.onTertiaryContainer
        Tint.ERROR -> c.errorContainer to c.onErrorContainer
        Tint.NEUTRAL -> c.surfaceContainerHighest to c.onSurfaceVariant
    }
}

@Composable
fun IconBadge(icon: ImageVector, tint: Tint, shape: RoundedPolygon = MaterialShapes.Cookie4Sided, size: Int = 40) {
    val (bg, fg) = tint.colors()
    Box(
        Modifier.size(size.dp).clip(shape.toShape()).background(bg),
        contentAlignment = Alignment.Center,
    ) { Icon(icon, null, tint = fg, modifier = Modifier.size((size * 0.55f).dp)) }
}

/** Scaffold with the big collapsing expressive title used by every settings page. */
@Composable
fun SettingsScaffold(
    title: String,
    onBack: (() -> Unit)?,
    subtitle: String? = null,
    content: LazyListScope.() -> Unit,
) {
    val scroll = TopAppBarDefaults.exitUntilCollapsedScrollBehavior(rememberTopAppBarState())
    Scaffold(
        modifier = Modifier.nestedScroll(scroll.nestedScrollConnection),
        containerColor = MaterialTheme.colorScheme.surfaceContainer,
        topBar = {
            LargeFlexibleTopAppBar(
                title = { Text(title) },
                subtitle = subtitle?.let { { Text(it) } },
                navigationIcon = {
                    if (onBack != null) {
                        FilledTonalIconButton(onClick = onBack, modifier = Modifier.padding(start = 8.dp)) {
                            Icon(Icons.AutoMirrored.Rounded.ArrowBack, tr("Zurück", "Back"))
                        }
                    }
                },
                scrollBehavior = scroll,
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainer,
                    scrolledContainerColor = MaterialTheme.colorScheme.surfaceContainer,
                ),
            )
        },
    ) { padding ->
        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = padding.calculateTopPadding() + 4.dp),
        ) {
            content()
            item { Spacer(Modifier.height(24.dp).windowInsetsBottomHeight(WindowInsets.navigationBars)) }
            item { Spacer(Modifier.height(32.dp)) }
        }
    }
}

class GroupScope internal constructor() {
    internal val rows = mutableListOf<@Composable (Modifier, androidx.compose.ui.graphics.Shape) -> Unit>()

    fun row(content: @Composable (Modifier, androidx.compose.ui.graphics.Shape) -> Unit) {
        rows += content
    }
}

/**
 * A group of rows drawn as separate tiles with big outer and small inner corners
 * (the Android 16 / Pixel settings look).
 */
fun LazyListScope.group(title: String? = null, build: GroupScope.() -> Unit) {
    val scope = GroupScope().apply(build)
    if (title != null) {
        item(key = "h-$title") {
            Text(
                title,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(start = 20.dp, top = 22.dp, bottom = 8.dp),
            )
        }
    } else {
        item { Spacer(Modifier.height(12.dp)) }
    }
    val n = scope.rows.size
    scope.rows.forEachIndexed { i, r ->
        item {
            val big = 26.dp
            val small = 6.dp
            val shape = RoundedCornerShape(
                topStart = if (i == 0) big else small, topEnd = if (i == 0) big else small,
                bottomStart = if (i == n - 1) big else small, bottomEnd = if (i == n - 1) big else small,
            )
            r(Modifier.padding(bottom = if (i == n - 1) 0.dp else 3.dp), shape)
        }
    }
}

@Composable
fun SettingRow(
    title: String,
    modifier: Modifier,
    shape: androidx.compose.ui.graphics.Shape,
    subtitle: String? = null,
    icon: ImageVector? = null,
    tint: Tint = Tint.PRIMARY,
    badgeShape: RoundedPolygon = MaterialShapes.Cookie4Sided,
    onClick: (() -> Unit)? = null,
    trailing: @Composable (RowScope.() -> Unit)? = null,
    below: @Composable (ColumnScope.() -> Unit)? = null,
) {
    Surface(
        shape = shape,
        color = MaterialTheme.colorScheme.surfaceBright,
        modifier = modifier.fillMaxWidth().clip(shape).then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier),
    ) {
        Column(Modifier.animateContentSize().padding(horizontal = 16.dp, vertical = 14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (icon != null) {
                    IconBadge(icon, tint, badgeShape)
                    Spacer(Modifier.width(16.dp))
                }
                Column(Modifier.weight(1f)) {
                    Text(title, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    if (subtitle != null) {
                        Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                if (trailing != null) {
                    Spacer(Modifier.width(12.dp))
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp), content = trailing)
                }
            }
            if (below != null) {
                Spacer(Modifier.height(12.dp))
                below()
            }
        }
    }
}

@Composable
fun WispSwitch(checked: Boolean, onChange: (Boolean) -> Unit, enabled: Boolean = true) {
    Switch(
        checked = checked, onCheckedChange = onChange, enabled = enabled,
        thumbContent = {
            Icon(if (checked) Icons.Rounded.Check else Icons.Rounded.Close, null, Modifier.size(SwitchDefaults.IconSize))
        },
    )
}

/** Small status pill: green-ish "Bereit", red "fehlt" etc. */
@Composable
fun StatusPill(text: String, ok: Boolean) {
    val bg by animateColorAsState(if (ok) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.errorContainer, label = "pill")
    val fg = if (ok) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onErrorContainer
    Surface(shape = RoundedCornerShape(50), color = bg, contentColor = fg) {
        Text(text, style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp))
    }
}

/** Explanatory footer below a group. */
fun LazyListScope.footnote(text: String) {
    item {
        Text(
            text,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 10.dp),
        )
    }
}
