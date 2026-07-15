package com.purringlabs.gitworktree.gitworktreemanager.ui.dialogs

import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.ui.components.JBLabel
import com.purringlabs.gitworktree.gitworktreemanager.models.WorktreeInfo
import java.awt.BorderLayout
import javax.swing.JComboBox
import javax.swing.JComponent
import javax.swing.JList
import javax.swing.JPanel
import javax.swing.ListCellRenderer

/**
 * Dialog to choose a target branch (from the worktree list) to merge the current branch into.
 * Displays a dropdown of other worktrees' branches.
 */
class MergeIntoBranchDialog(
    project: Project,
    private val sourceBranch: String,
    private val targetWorktrees: List<WorktreeInfo>
) : DialogWrapper(project) {

    private val comboBox: JComboBox<WorktreeInfo> = JComboBox(targetWorktrees.toTypedArray()).apply {
        renderer = object : ListCellRenderer<WorktreeInfo> {
            override fun getListCellRendererComponent(
                list: JList<out WorktreeInfo>?,
                value: WorktreeInfo?,
                index: Int,
                isSelected: Boolean,
                cellHasFocus: Boolean
            ): java.awt.Component {
                val text = if (value != null) "${value.branch ?: "detached"} — ${value.path}" else ""
                return JBLabel(text).apply {
                    if (list != null) {
                        background = if (isSelected) list.selectionBackground else list.background
                        foreground = if (isSelected) list.selectionForeground else list.foreground
                    }
                }
            }
        }
    }

    init {
        title = "Merge \"$sourceBranch\" into..."
        init()
    }

    override fun createCenterPanel(): JComponent {
        val panel = JPanel(BorderLayout())
        panel.add(JBLabel("Select target branch (worktree):"), BorderLayout.NORTH)
        panel.add(comboBox, BorderLayout.CENTER)
        return panel
    }

    /**
     * Returns the selected target worktree, or null if none / cancelled.
     */
    fun getSelectedTarget(): WorktreeInfo? {
        return comboBox.selectedItem as? WorktreeInfo
    }
}
