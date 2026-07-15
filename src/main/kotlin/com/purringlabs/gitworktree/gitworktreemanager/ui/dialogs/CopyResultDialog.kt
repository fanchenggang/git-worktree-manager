package com.purringlabs.gitworktree.gitworktreemanager.ui.dialogs

import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.purringlabs.gitworktree.gitworktreemanager.MyMessageBundle
import com.purringlabs.gitworktree.gitworktreemanager.models.CopyResult
import java.awt.BorderLayout
import java.awt.Dimension
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.JTextArea

/**
 * Dialog for displaying the results of copying ignored files
 *
 * Shows the number of successfully copied files and any failures with error messages.
 */
class CopyResultDialog(
    project: Project,
    private val result: CopyResult
) : DialogWrapper(project) {

    init {
        title = MyMessageBundle.message("dialog.copyResults.title")
        init()
    }

    override fun createCenterPanel(): JComponent {
        val panel = JPanel(BorderLayout(0, 10))

        // Summary label
        val summaryText = if (result.hasFailures) {
            MyMessageBundle.message(
                "dialog.copyResults.summaryPartial",
                result.successCount,
                result.failureCount
            )
        } else {
            MyMessageBundle.message("dialog.copyResults.summaryOk", result.successCount)
        }
        val summaryLabel = JBLabel(summaryText)
        panel.add(summaryLabel, BorderLayout.NORTH)

        // If there are failures, show them in a text area
        if (result.hasFailures) {
            val failuresText = buildString {
                appendLine(MyMessageBundle.message("dialog.copyResults.failedHeader"))
                appendLine()
                result.failed.forEach { (path, error) ->
                    appendLine(path)
                    appendLine(MyMessageBundle.message("dialog.copyResults.errorLine", error))
                    appendLine()
                }
            }

            val textArea = JTextArea(failuresText).apply {
                isEditable = false
                lineWrap = true
                wrapStyleWord = true
            }

            val scrollPane = JBScrollPane(textArea)
            scrollPane.preferredSize = Dimension(500, 300)
            panel.add(scrollPane, BorderLayout.CENTER)
        }

        return panel
    }

    override fun createActions() = arrayOf(okAction)
}
