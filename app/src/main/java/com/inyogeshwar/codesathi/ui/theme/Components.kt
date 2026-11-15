package com.inyogeshwar.codesathi.ui.theme

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Extra bottom padding a top-level screen adds so its last item can
 * scroll clear of the floating tab bar. Zero on secondary screens.
 */
val LocalBottomBarInset = compositionLocalOf { 0.dp }

/**
 * The soft gradient backdrop every screen sits on: the theme background
 * with a teal glow from the top-left and a faint saffron glow top-right.
 */
@Composable
fun SathiBackdrop(modifier: Modifier = Modifier, content: @Composable BoxScope.() -> Unit) {
    val bg = MaterialTheme.colorScheme.background
    val dark = LocalSathiStatus.current.isDark
    val tealAlpha = if (dark) 0.20f else 0.16f
    val saffronAlpha = if (dark) 0.07f else 0.12f
    Box(
        modifier
            .fillMaxSize()
            .background(bg)
            .drawBehind {
                val w = size.width
                val h = size.height
                drawRect(
                    Brush.radialGradient(
                        colors = listOf(SathiColors.TealBright.copy(alpha = tealAlpha), Color.Transparent),
                        center = Offset(w * 0.05f, h * 0.02f),
                        radius = maxOf(w, h) * 0.75f
                    )
                )
                drawRect(
                    Brush.radialGradient(
                        colors = listOf(SathiColors.Saffron.copy(alpha = saffronAlpha), Color.Transparent),
                        center = Offset(w * 1.0f, h * 0.0f),
                        radius = maxOf(w, h) * 0.45f
                    )
                )
            },
        content = content
    )
}

/** Brand gradient for accents (buttons, user bubbles, the logo tile). */
fun sathiGradient(dark: Boolean): Brush =
    if (dark) Brush.linearGradient(listOf(Color(0xFF0F6B64), SathiColors.TealBright))
    else Brush.linearGradient(listOf(SathiColors.TealDeep, SathiColors.TealBright))

/**
 * Scaffold on a transparent container so the backdrop shows through.
 * The content is already padded for the bars and the keyboard (IME),
 * so screens get [PaddingValues] of zero and need no inset handling.
 */
@Composable
fun SathiScaffold(
    modifier: Modifier = Modifier,
    topBar: @Composable () -> Unit = {},
    bottomBar: @Composable () -> Unit = {},
    snackbarHost: @Composable () -> Unit = {},
    floatingActionButton: @Composable () -> Unit = {},
    content: @Composable (PaddingValues) -> Unit
) {
    Scaffold(
        modifier = modifier,
        topBar = topBar,
        bottomBar = bottomBar,
        snackbarHost = snackbarHost,
        floatingActionButton = floatingActionButton,
        containerColor = Color.Transparent,
        contentColor = MaterialTheme.colorScheme.onBackground,
    ) { inner ->
        Box(
            Modifier
                .fillMaxSize()
                .padding(inner)
                .consumeWindowInsets(inner)
                .imePadding()
        ) {
            content(PaddingValues(0.dp))
        }
    }
}

/** Transparent top bar with the CodeSathi title style. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SathiTopBar(
    title: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    navigationIcon: @Composable () -> Unit = {},
    actions: @Composable RowScope.() -> Unit = {}
) {
    TopAppBar(
        title = title,
        modifier = modifier,
        navigationIcon = navigationIcon,
        actions = actions,
        colors = TopAppBarDefaults.topAppBarColors(
            containerColor = Color.Transparent,
            scrolledContainerColor = Color.Transparent,
            titleContentColor = MaterialTheme.colorScheme.onBackground,
        )
    )
}

/** Title + optional subtitle for a top bar. */
@Composable
fun TopBarTitle(title: String, subtitle: String? = null) {
    Column {
        Text(title, style = MaterialTheme.typography.titleLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
        if (!subtitle.isNullOrBlank()) {
            Text(
                subtitle,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

/** Flat card with a hairline border: the standard CodeSathi surface. */
@Composable
fun SathiCard(
    modifier: Modifier = Modifier,
    shape: Shape = MaterialTheme.shapes.large,
    color: Color = MaterialTheme.colorScheme.surfaceContainerHighest,
    contentPadding: Dp = 16.dp,
    onClick: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit
) {
    val border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f))
    if (onClick != null) {
        Surface(onClick = onClick, modifier = modifier, shape = shape, color = color, border = border) {
            Column(Modifier.padding(contentPadding), content = content)
        }
    } else {
        Surface(modifier = modifier, shape = shape, color = color, border = border) {
            Column(Modifier.padding(contentPadding), content = content)
        }
    }
}

/** Small uppercase-free section label with an optional trailing action. */
@Composable
fun SectionHeader(
    text: String,
    modifier: Modifier = Modifier,
    trailing: @Composable (RowScope.() -> Unit)? = null
) {
    Row(modifier.fillMaxWidth().padding(top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(
            text,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onBackground,
            modifier = Modifier.weight(1f)
        )
        trailing?.invoke(this)
    }
}

/** A rounded tinted pill, used for chips like the model, mode and status. */
@Composable
fun Pill(
    text: String,
    modifier: Modifier = Modifier,
    container: Color = MaterialTheme.colorScheme.secondaryContainer,
    content: Color = MaterialTheme.colorScheme.onSecondaryContainer,
    leading: @Composable (() -> Unit)? = null,
    onClick: (() -> Unit)? = null
) {
    val inner: @Composable () -> Unit = {
        Row(
            Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (leading != null) {
                leading()
                Box(Modifier.padding(start = 6.dp))
            }
            Text(text, style = MaterialTheme.typography.labelMedium, color = content, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
    if (onClick != null) {
        Surface(onClick = onClick, modifier = modifier, shape = MaterialTheme.shapes.extraLarge, color = container) { inner() }
    } else {
        Surface(modifier = modifier, shape = MaterialTheme.shapes.extraLarge, color = container) { inner() }
    }
}

// ---------------------------------------------------------------------------
// Clozed-style layout language (Phase 8).
//
// These are CodeSathi's own components, written from the layout patterns
// in the Clozed screenshots (large-radius grey section cards, blue section
// headers, pill segmented controls, rounded bottom sheets). No Clozed
// assets or text are used anywhere.
// ---------------------------------------------------------------------------

/** Grey-blue section card with a large (24dp) radius — the Clozed card shape. */
@Composable
fun ClozedCard(
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.surfaceContainer,
    onClick: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit
) {
    SathiCard(
        modifier = modifier,
        shape = androidx.compose.foundation.shape.RoundedCornerShape(24.dp),
        color = color,
        contentPadding = 16.dp,
        onClick = onClick,
        content = content
    )
}

/** Blue section header, as on every Clozed settings section. */
@Composable
fun ClozedSectionHeader(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        style = MaterialTheme.typography.titleMedium,
        color = MaterialTheme.colorScheme.primary,
        modifier = modifier.fillMaxWidth().padding(top = 16.dp, bottom = 8.dp)
    )
}

/**
 * Pill-shaped segmented control: the selected segment is light-blue with
 * a ✓. Segments share the width evenly (weight) and never wrap — the
 * "Mediu m" bug in Clozed's Reasoning segment is not reproduced.
 */
@Composable
fun ClozedSegmentedControl(
    options: List<String>,
    selected: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        shape = MaterialTheme.shapes.extraLarge,
        color = MaterialTheme.colorScheme.surfaceContainerHighest,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = modifier
    ) {
        Row(Modifier.padding(3.dp)) {
            options.forEachIndexed { index, label ->
                val isSelected = index == selected
                Surface(
                    onClick = { if (!isSelected) onSelect(index) },
                    shape = MaterialTheme.shapes.extraLarge,
                    color = if (isSelected) MaterialTheme.colorScheme.primaryContainer else Color.Transparent,
                    contentColor = if (isSelected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f)
                ) {
                    Row(
                        Modifier.padding(horizontal = 8.dp, vertical = 7.dp),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        if (isSelected) {
                            Icon(
                                Icons.Filled.Check,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(Modifier.width(4.dp))
                        }
                        Text(label, style = MaterialTheme.typography.labelLarge, maxLines = 1)
                    }
                }
            }
        }
    }
}

/** Light-blue info card with an icon — the Clozed explainer / warning card. */
@Composable
fun ClozedInfoCard(
    text: String,
    modifier: Modifier = Modifier,
    title: String? = null,
    onClick: (() -> Unit)? = null
) {
    SathiCard(
        modifier = modifier,
        shape = androidx.compose.foundation.shape.RoundedCornerShape(24.dp),
        color = MaterialTheme.colorScheme.primaryContainer,
        contentPadding = 16.dp,
        onClick = onClick
    ) {
        Row(verticalAlignment = Alignment.Top) {
            Icon(
                Icons.Filled.Info,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onPrimaryContainer,
                modifier = Modifier.size(22.dp)
            )
            Spacer(Modifier.padding(start = 12.dp))
            Column(Modifier.weight(1f)) {
                if (title != null) {
                    Text(title, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onPrimaryContainer)
                    Spacer(Modifier.padding(top = 2.dp))
                }
                Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onPrimaryContainer)
            }
        }
    }
}

/** Rounded bottom sheet (32dp top corners, centre drag handle). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ClozedSheet(
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit
) {
    androidx.compose.material3.ModalBottomSheet(
        onDismissRequest = onDismiss,
        modifier = modifier,
        sheetState = androidx.compose.material3.rememberModalBottomSheetState(),
        shape = androidx.compose.foundation.shape.RoundedCornerShape(topStart = 32.dp, topEnd = 32.dp),
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        dragHandle = { androidx.compose.material3.BottomSheetDefaults.DragHandle() },
        content = content
    )
}
