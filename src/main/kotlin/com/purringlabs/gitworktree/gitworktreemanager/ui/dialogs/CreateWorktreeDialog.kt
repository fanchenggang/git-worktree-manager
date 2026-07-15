package com.purringlabs.gitworktree.gitworktreemanager.ui.dialogs

import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.FormBuilder
import java.awt.event.KeyAdapter
import java.awt.event.KeyEvent
import javax.swing.JComponent
import javax.swing.JPanel

class CreateWorktreeDialog(project: Project) : DialogWrapper(project) {

    private val worktreeNameField = JBTextField()
    private val branchNameField = JBTextField()
    private val createNewBranchCheckBox = JBCheckBox("Create new branch").apply { isSelected = true }
    private val copyIgnoredFilesCheckBox = JBCheckBox("Copy ignored files (e.g. build caches, node_modules)")

    private var userEditedBranch = false

    init {
        title = "Create New Worktree"
        
        // Auto-fill branch name based on worktree name if user hasn't manually edited the branch name
        worktreeNameField.addKeyListener(object : KeyAdapter() {
            override fun keyReleased(e: KeyEvent?) {
                if (!userEditedBranch) {
                    branchNameField.text = sanitizeBranchName(worktreeNameField.text)
                }
            }
        })

        branchNameField.addKeyListener(object : KeyAdapter() {
            override fun keyTyped(e: KeyEvent?) {
                userEditedBranch = true
            }
        })

        init()
    }

    override fun createCenterPanel(): JComponent {
        return FormBuilder.createFormBuilder()
            .addLabeledComponent(JBLabel("Worktree name:"), worktreeNameField, 1, false)
            .addLabeledComponent(JBLabel("Branch name:"), branchNameField, 1, false)
            .addComponent(createNewBranchCheckBox, 1)
            .addComponent(copyIgnoredFilesCheckBox, 1)
            .addComponentFillVertically(JPanel(), 0)
            .panel
    }

    override fun getPreferredFocusedComponent(): JComponent {
        return worktreeNameField
    }

    fun getWorktreeName(): String = worktreeNameField.text.trim()

    fun getBranchName(): String = branchNameField.text.trim()

    fun shouldCreateNewBranch(): Boolean = createNewBranchCheckBox.isSelected

    fun shouldCopyIgnoredFiles(): Boolean = copyIgnoredFilesCheckBox.isSelected

    private fun sanitizeBranchName(input: String): String {
        return input
            .trim()
            .lowercase()
            .replace(Regex("\\s+"), "-")
            .replace(Regex("[^a-z0-9._/-]"), "-")
            .replace(Regex("/+"), "/")
            .replace(Regex("-+"), "-")
            .replace(Regex("(^[-./]+|[-./]+$)"), "")
    }
}
