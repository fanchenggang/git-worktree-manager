package com.purringlabs.gitworktree.gitworktreemanager.ui.dialogs

import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.FormBuilder
import com.purringlabs.gitworktree.gitworktreemanager.MyMessageBundle
import com.purringlabs.gitworktree.gitworktreemanager.util.BranchNameSanitizer
import java.awt.event.KeyAdapter
import java.awt.event.KeyEvent
import javax.swing.JComponent
import javax.swing.JPanel

class CreateWorktreeDialog(project: Project) : DialogWrapper(project) {

    private val worktreeNameField = JBTextField()
    private val branchNameField = JBTextField()
    private val createNewBranchCheckBox = JBCheckBox(MyMessageBundle.message("dialog.create.createNewBranch")).apply {
        isSelected = true
    }
    private val copyIgnoredFilesCheckBox = JBCheckBox(MyMessageBundle.message("dialog.create.copyIgnoredFiles"))
    private val copyAgentContextCheckBox = JBCheckBox(MyMessageBundle.message("dialog.create.copyAgentContext"))

    private var userEditedBranch = false

    init {
        title = MyMessageBundle.message("dialog.create.title")
        
        // Auto-fill branch name based on worktree name if user hasn't manually edited the branch name
        worktreeNameField.addKeyListener(object : KeyAdapter() {
            override fun keyReleased(e: KeyEvent?) {
                if (!userEditedBranch) {
                    branchNameField.text = BranchNameSanitizer.sanitize(worktreeNameField.text)
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
            .addLabeledComponent(JBLabel(MyMessageBundle.message("dialog.create.worktreeName")), worktreeNameField, 1, false)
            .addLabeledComponent(JBLabel(MyMessageBundle.message("dialog.create.branchName")), branchNameField, 1, false)
            .addComponent(createNewBranchCheckBox, 1)
            .addComponent(copyIgnoredFilesCheckBox, 1)
            .addComponent(copyAgentContextCheckBox, 1)
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

    fun shouldCopyAgentContext(): Boolean = copyAgentContextCheckBox.isSelected
}
