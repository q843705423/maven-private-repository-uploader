package com.maven.privateuploader.ui.table

import com.intellij.ui.ColoredTableCellRenderer
import com.intellij.ui.ColoredTextContainer
import com.intellij.ui.SimpleTextAttributes
import javax.swing.JTable
import com.intellij.util.ui.JBUI
import com.maven.privateuploader.model.CheckStatus
import com.maven.privateuploader.model.DependencyInfo
import java.awt.Component
import javax.swing.*
import javax.swing.table.AbstractTableModel
import javax.swing.table.DefaultTableCellRenderer

/**
 * 依赖表格的数据模型
 */
class DependencyTableModel : AbstractTableModel() {

    private var dependencies: List<DependencyInfo> = mutableListOf()
    private val columnNames = arrayOf("选择", "GroupId", "ArtifactId", "Version", "Packaging", "状态", "本地路径")

    /**
     * 列名
     */
    override fun getColumnName(column: Int): String {
        return columnNames[column]
    }

    /**
     * 行数
     */
    override fun getRowCount(): Int {
        return dependencies.size
    }

    /**
     * 列数
     */
    override fun getColumnCount(): Int {
        return columnNames.size
    }

    /**
     * 单元格值
     */
    override fun getValueAt(rowIndex: Int, columnIndex: Int): Any {
        val dependency = dependencies[rowIndex]

        return when (columnIndex) {
            0 -> dependency.selected  // 选择列
            1 -> dependency.groupId
            2 -> dependency.artifactId
            3 -> dependency.version
            4 -> dependency.packaging
            5 -> dependency.checkStatus
            6 -> dependency.localPath
            else -> ""
        }
    }

    /**
     * 获取单元格类型
     */
    override fun getColumnClass(columnIndex: Int): Class<*> {
        return when (columnIndex) {
            0 -> java.lang.Boolean::class.java  // 选择列
            5 -> CheckStatus::class.java       // 状态列
            else -> String::class.java
        }
    }

    /**
     * 单元格是否可编辑
     */
    override fun isCellEditable(rowIndex: Int, columnIndex: Int): Boolean {
        // 只有选择列可编辑
        return columnIndex == 0
    }

    /**
     * 设置单元格值（仅支持选择状态）
     */
    override fun setValueAt(value: Any?, rowIndex: Int, columnIndex: Int) {
        if (columnIndex == 0 && rowIndex >= 0 && rowIndex < dependencies.size) {
            // 更新选择状态
            dependencies[rowIndex].selected = value as? Boolean ?: false
            fireTableCellUpdated(rowIndex, columnIndex)
        }
    }

    /**
     * 设置依赖列表
     */
    fun setDependencies(dependencies: List<DependencyInfo>) {
        this.dependencies = dependencies.toList()
        fireTableDataChanged()
    }

    /**
     * 获取指定行的依赖信息
     */
    fun getDependencyAt(rowIndex: Int): DependencyInfo? {
        return if (rowIndex >= 0 && rowIndex < dependencies.size) {
            dependencies[rowIndex]
        } else null
    }

    /**
     * 获取所有依赖
     */
    fun getDependencies(): List<DependencyInfo> {
        return dependencies.toList()
    }

    /**
     * 设置所有行的选择状态
     */
    fun setAllSelected(selected: Boolean) {
        dependencies.forEach { it.selected = selected }
        fireTableDataChanged()
    }

    /**
     * 切换指定行的选择状态
     */
    fun toggleSelection(rowIndex: Int) {
        if (rowIndex >= 0 && rowIndex < dependencies.size) {
            dependencies[rowIndex].selected = !dependencies[rowIndex].selected
            fireTableRowsUpdated(rowIndex, rowIndex)
        }
    }

    /**
     * 获取选中的依赖
     */
    fun getSelectedDependencies(): List<DependencyInfo> {
        return dependencies.filter { it.selected }
    }

    /**
     * 获取选中依赖的数量
     */
    fun getSelectedCount(): Int {
        return dependencies.count { it.selected }
    }

    companion object {
        /**
         * 创建带渲染器的表格
         */
        fun createTable(): JTable {
            val table = JTable(DependencyTableModel())

            // 设置选择列的渲染器和编辑器
            table.columnModel.getColumn(0).cellRenderer = javax.swing.table.DefaultTableCellRenderer()
            table.columnModel.getColumn(0).cellEditor = javax.swing.DefaultCellEditor(javax.swing.JCheckBox())

            // 设置状态列的渲染器
            table.columnModel.getColumn(5).cellRenderer = StatusCellRenderer()

            // 设置其他列的渲染器
            val defaultRenderer = DefaultTableCellRenderer()
            table.setDefaultRenderer(String::class.java, defaultRenderer)

            // 设置行高
            table.rowHeight = 24

            return table
        }
    }

    /**
     * 状态列的渲染器
     */
    private class StatusCellRenderer : ColoredTableCellRenderer() {

        override fun customizeCellRenderer(
            table: JTable,
            value: Any?,
            selected: Boolean,
            hasFocus: Boolean,
            row: Int,
            column: Int
        ) {
            if (value is CheckStatus) {
                when (value) {
                    CheckStatus.EXISTS -> {
                        append("已存在", SimpleTextAttributes(SimpleTextAttributes.STYLE_PLAIN, java.awt.Color.GREEN))
                    }
                    CheckStatus.MISSING -> {
                        append("缺失", SimpleTextAttributes(SimpleTextAttributes.STYLE_PLAIN, java.awt.Color.RED))
                    }
                    CheckStatus.CHECKING -> {
                        append("检查中", SimpleTextAttributes(SimpleTextAttributes.STYLE_PLAIN, java.awt.Color.ORANGE))
                    }
                    CheckStatus.ERROR -> {
                        append("错误", SimpleTextAttributes(SimpleTextAttributes.STYLE_PLAIN, java.awt.Color.RED))
                    }
                    CheckStatus.UNKNOWN -> {
                        append("未知", SimpleTextAttributes(SimpleTextAttributes.STYLE_PLAIN, java.awt.Color.GRAY))
                    }
                }
            } else {
                append(value?.toString() ?: "", SimpleTextAttributes.REGULAR_ATTRIBUTES)
            }
        }
    }
}

