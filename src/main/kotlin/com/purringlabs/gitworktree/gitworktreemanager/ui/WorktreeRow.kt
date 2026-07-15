package com.purringlabs.gitworktree.gitworktreemanager.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.awt.SwingPanel
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.isSecondaryPressed
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.intellij.icons.AllIcons
import com.purringlabs.gitworktree.gitworktreemanager.MyMessageBundle
import com.purringlabs.gitworktree.gitworktreemanager.isDeleteEnabled
import com.purringlabs.gitworktree.gitworktreemanager.models.WorktreeInfo
import com.purringlabs.gitworktree.gitworktreemanager.worktreeFolderName
import org.jetbrains.jewel.ui.component.Text
import java.awt.Cursor
import javax.swing.Icon
import javax.swing.JLabel

/**
 * Pure UI composable for displaying a single worktree item.
 * No dependency on Project — can be previewed with mock data.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun WorktreeItem(
    worktree: WorktreeInfo,
    isCurrent: Boolean,
    isDeleting: Boolean,
    onOpen: () -> Unit,
    onDelete: () -> Unit,
    onContextMenu: (Offset) -> Unit,
    onOpenInTerminal: () -> Unit,
    onRevealInExplorer: () -> Unit
) {
    var isHovered by remember { mutableStateOf(false) }

    val currentBackground = when {
        !isCurrent -> Color.Transparent
        isSystemInDarkTheme() -> Color(0x162F80FF) // subtle blue tint
        else -> Color(0x142F80FF)
    }

    val hoverBackground = when {
        !isHovered -> Color.Transparent
        isSystemInDarkTheme() -> Color(0x22FFFFFF)
        else -> Color(0x14000000)
    }

    // If it's both current + hovered, blend by just preferring hover.
    val rowBackground = if (isHovered) hoverBackground else currentBackground
    val secondaryColor = if (isSystemInDarkTheme()) Color(0xFFB0B0B0) else Color(0xFF5C5C5C)
    val branchLine = buildString {
        append(worktree.branch ?: MyMessageBundle.message("worktree.detachedHead"))
        append(" · ")
        append(worktree.commit.take(8))
    }
    val pathDisplay = worktree.path.replace('\\', '/')

    val rightClickModifier = Modifier.pointerInput(worktree) {
        awaitPointerEventScope {
            while (true) {
                val event = awaitPointerEvent()
                if (event.type == PointerEventType.Press && event.buttons.isSecondaryPressed) {
                    event.changes.forEach { it.consume() }
                    val pos = event.changes.firstOrNull()?.position ?: Offset.Zero
                    onContextMenu(pos)
                }
            }
        }
    }

    Box(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = rightClickModifier
                .then(
                    Modifier
                        .fillMaxWidth()
                        .onPointerEnterExit(
                            onEnter = { isHovered = true },
                            onExit = { isHovered = false }
                        )
                        .pointerHoverIcon(PointerIcon(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)))
                        .pointerInput(Unit) {
                            detectTapGestures(
                                onDoubleTap = { onOpen() }
                            )
                        }
                        .background(rowBackground)
                        .padding(horizontal = 6.dp, vertical = 3.dp)
                ),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                modifier = Modifier.weight(0.26f).fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Box(
                    modifier = Modifier.width(14.dp),
                    contentAlignment = Alignment.Center
                ) {
                    if (isCurrent) {
                        Text(text = "✓", fontWeight = FontWeight.Medium)
                    }
                }
                Text(
                    text = worktreeFolderName(worktree.path),
                    fontWeight = if (worktree.isMain) FontWeight.Bold else FontWeight.Normal,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f).fillMaxWidth()
                )
            }

            Text(
                text = branchLine,
                color = secondaryColor,
                fontWeight = FontWeight.Normal,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(0.30f).fillMaxWidth()
            )

            Text(
                text = pathDisplay,
                color = secondaryColor,
                fontWeight = FontWeight.Normal,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(0.36f).fillMaxWidth()
            )

            val deleteEnabled = isDeleteEnabled(isMain = worktree.isMain, isCurrent = isCurrent, isDeleting = isDeleting)
            var isDeleteHovered by remember { mutableStateOf(false) }

            if (isHovered) {
                fun Modifier.actionCursor(enabled: Boolean): Modifier {
                    return pointerHoverIcon(
                        if (enabled) PointerIcon(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)) else PointerIcon.Default
                    )
                }

                @Composable
                fun IdeaIcon(icon: Icon) {
                    SwingPanel(
                        modifier = Modifier.size(16.dp),
                        factory = {
                            JLabel().apply {
                                isOpaque = false
                                this.icon = icon
                            }
                        }
                    )
                }

                @Composable
                fun IconActionButton(
                    icon: Icon,
                    tooltip: String,
                    enabled: Boolean = true,
                    onClick: () -> Unit
                ) {
                    var hovered by remember { mutableStateOf(false) }
                    val bg = if (hovered) {
                        if (isSystemInDarkTheme()) Color(0x22FFFFFF) else Color(0x14000000)
                    } else Color.Transparent

                    Box(
                        modifier = Modifier
                            .actionCursor(enabled)
                            .onPointerEnterExit(
                                onEnter = { hovered = true },
                                onExit = { hovered = false }
                            )
                            .pointerInput(enabled) {
                                if (enabled) detectTapGestures(onTap = { onClick() })
                            }
                            .size(28.dp)
                            .background(bg, RoundedCornerShape(6.dp))
                            .padding(6.dp)
                    ) {
                        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            IdeaIcon(icon = icon)
                        }

                        if (hovered) {
                            Box(
                                modifier = Modifier
                                    .align(Alignment.TopCenter)
                                    .offset(y = (-28).dp)
                                    .background(
                                        if (isSystemInDarkTheme()) Color(0xEE2B2B2B) else Color(0xEEFFFFFF),
                                        RoundedCornerShape(6.dp)
                                    )
                                    .border(
                                        1.dp,
                                        if (isSystemInDarkTheme()) Color(0x55FFFFFF) else Color(0x22000000),
                                        RoundedCornerShape(6.dp)
                                    )
                                    .padding(horizontal = 8.dp, vertical = 6.dp)
                            ) {
                                Text(text = tooltip, fontWeight = FontWeight.Light)
                            }
                        }
                    }
                }

                Row(horizontalArrangement = Arrangement.spacedBy(2.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconActionButton(
                        icon = AllIcons.Actions.Execute,
                        tooltip = MyMessageBundle.message("action.openWorktree"),
                        onClick = onOpen
                    )
                    IconActionButton(
                        icon = AllIcons.Nodes.Console,
                        tooltip = MyMessageBundle.message("action.openInTerminal"),
                        onClick = onOpenInTerminal
                    )
                    IconActionButton(
                        icon = AllIcons.Nodes.Folder,
                        tooltip = MyMessageBundle.message("action.revealInExplorer"),
                        onClick = onRevealInExplorer
                    )

                    Box(
                        modifier = Modifier.onPointerEnterExit(
                            onEnter = { isDeleteHovered = true },
                            onExit = { isDeleteHovered = false }
                        )
                    ) {
                        IconActionButton(
                            icon = AllIcons.General.Remove,
                            tooltip = MyMessageBundle.message("action.deleteWorktree"),
                            enabled = deleteEnabled,
                            onClick = onDelete
                        )

                        if (!deleteEnabled && isDeleteHovered) {
                            val tooltipText = when {
                                worktree.isMain -> MyMessageBundle.message("tooltip.cannotDeleteMain")
                                isCurrent -> MyMessageBundle.message("tooltip.cannotDeleteCurrent")
                                else -> null
                            }

                            if (tooltipText != null) {
                                Box(
                                    modifier = Modifier
                                        .align(Alignment.TopEnd)
                                        .offset(y = (-28).dp)
                                        .background(
                                            if (isSystemInDarkTheme()) Color(0xEE2B2B2B) else Color(0xEEFFFFFF),
                                            RoundedCornerShape(6.dp)
                                        )
                                        .border(
                                            1.dp,
                                            if (isSystemInDarkTheme()) Color(0x55FFFFFF) else Color(0x22000000),
                                            RoundedCornerShape(6.dp)
                                        )
                                        .padding(horizontal = 8.dp, vertical = 6.dp)
                                ) {
                                    Text(text = tooltipText, fontWeight = FontWeight.Light)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun Modifier.onPointerEnterExit(
    onEnter: () -> Unit,
    onExit: () -> Unit
): Modifier = pointerInput(onEnter, onExit) {
    awaitPointerEventScope {
        while (true) {
            when (awaitPointerEvent().type) {
                PointerEventType.Enter -> onEnter()
                PointerEventType.Exit -> onExit()
            }
        }
    }
}
