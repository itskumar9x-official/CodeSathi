package com.inyogeshwar.codesathi.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.inyogeshwar.codesathi.ui.theme.LocalReduceMotion

/** Height the floating bar occupies above the navigation bar (bar + margins). */
val FloatingTabBarHeight = 88.dp

/**
 * CodeSathi's floating tab bar: a rounded capsule hovering above the
 * content. The selected tab becomes a tinted pill with its label; the
 * others are icon-only. One short spring, no other motion.
 */
@Composable
fun FloatingTabBar(selected: TopLevel?, onSelect: (TopLevel) -> Unit, modifier: Modifier = Modifier) {
    Box(
        modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .padding(horizontal = 24.dp, vertical = 12.dp),
        contentAlignment = Alignment.Center
    ) {
        Surface(
            shape = RoundedCornerShape(30.dp),
            color = MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.96f),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.7f)),
            shadowElevation = 10.dp,
            tonalElevation = 0.dp
        ) {
            Row(
                Modifier.height(64.dp).padding(horizontal = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(2.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                TopLevel.entries.forEach { tab ->
                    TabItem(tab, tab == selected) { onSelect(tab) }
                }
            }
        }
    }
}

@Composable
private fun TabItem(tab: TopLevel, isSelected: Boolean, onClick: () -> Unit) {
    val reduce = LocalReduceMotion.current
    val scheme = MaterialTheme.colorScheme
    val bg by animateColorAsState(
        if (isSelected) scheme.primaryContainer else scheme.primaryContainer.copy(alpha = 0f), label = "tabBg"
    )
    val fg by animateColorAsState(
        if (isSelected) scheme.onPrimaryContainer else scheme.onSurfaceVariant, label = "tabFg"
    )
    Row(
        Modifier
            .height(46.dp)
            .background(bg, RoundedCornerShape(23.dp))
            .semantics { this.selected = isSelected }
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = ripple(bounded = true),
                role = Role.Tab,
                onClickLabel = tab.label,
                onClick = onClick
            )
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            if (isSelected) tab.selectedIcon else tab.icon,
            contentDescription = tab.label,
            tint = fg,
            modifier = Modifier.size(22.dp)
        )
        val spec = spring<IntSize>(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = Spring.StiffnessMediumLow)
        AnimatedVisibility(
            visible = isSelected,
            enter = if (reduce) fadeIn() else expandHorizontally(spec) + fadeIn(),
            exit = if (reduce) fadeOut() else shrinkHorizontally(spec) + fadeOut()
        ) {
            Text(
                tab.label,
                color = fg,
                style = MaterialTheme.typography.labelLarge,
                modifier = Modifier.padding(start = 8.dp)
            )
        }
    }
}
