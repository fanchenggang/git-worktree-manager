package com.purringlabs.gitworktree.gitworktreemanager.ui.dialogs

import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.table.JBTable
import com.purringlabs.gitworktree.gitworktreemanager.MyMessageBundle
import com.purringlabs.gitworktree.gitworktreemanager.models.AgentContextCopyOption
import java.awt.BorderLayout
import java.awt.Dimension
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.table.AbstractTableModel

class AgentContextCopyDialog(
    project: Project,
    options: List<AgentContextCopyOption>
) : DialogWrapper(project) {
    private val tableModel = AgentContextCopyTableModel(options.toMutableList())
    private val table = JBTable(tableModel)

    init {
        title = MyMessageBundle.message("dialog.agentContext.title")
        init()
    }

    override fun createCenterPanel(): JComponent {
        val panel = JPanel(BorderLayout(0, 10))
        panel.add(
            JBLabel(MyMessageBundle.message("dialog.agentContext.prompt")),
            BorderLayout.NORTH
        )

        table.setShowGrid(true)
        table.rowSelectionAllowed = false
        table.columnSelectionAllowed = false
        table.columnModel.getColumn(0).apply {
            maxWidth = 50
            preferredWidth = 50
        }
        table.columnModel.getColumn(1).preferredWidth = 240
        table.columnModel.getColumn(2).preferredWidth = 420

        val scrollPane = JBScrollPane(table)
        scrollPane.preferredSize = Dimension(760, 180)
        panel.add(scrollPane, BorderLayout.CENTER)
        return panel
    }

    fun selectedOptions(): List<AgentContextCopyOption> = tableModel.options()

    private class AgentContextCopyTableModel(
        private val options: MutableList<AgentContextCopyOption>
    ) : AbstractTableModel() {
        private val columnNames = arrayOf(
            MyMessageBundle.message("dialog.agentContext.col.copy"),
            MyMessageBundle.message("dialog.agentContext.col.context"),
            MyMessageBundle.message("dialog.agentContext.col.description")
        )

        override fun getRowCount(): Int = options.size
        override fun getColumnCount(): Int = columnNames.size
        override fun getColumnName(column: Int): String = columnNames[column]
        override fun getColumnClass(columnIndex: Int): Class<*> =
            if (columnIndex == 0) java.lang.Boolean::class.java else String::class.java

        override fun getValueAt(rowIndex: Int, columnIndex: Int): Any {
            val option = options[rowIndex]
            return when (columnIndex) {
                0 -> option.selected
                1 -> option.displayName
                2 -> option.description
                else -> ""
            }
        }

        override fun isCellEditable(rowIndex: Int, columnIndex: Int): Boolean = columnIndex == 0

        override fun setValueAt(aValue: Any?, rowIndex: Int, columnIndex: Int) {
            if (columnIndex == 0 && aValue is Boolean) {
                options[rowIndex] = options[rowIndex].copy(selected = aValue)
                fireTableCellUpdated(rowIndex, columnIndex)
            }
        }

        fun options(): List<AgentContextCopyOption> = options.toList()
    }
}
