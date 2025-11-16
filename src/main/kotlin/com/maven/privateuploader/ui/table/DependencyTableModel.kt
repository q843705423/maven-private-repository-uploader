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

    /**
     * 列名
     */
    override fun getColumnName(column: Int): String {
        return DependencyTableColumn.getByIndex(column)?.displayName ?: ""
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
        return DependencyTableColumn.allColumns.size
    }

    /**
     * 单元格值
     */
    override fun getValueAt(rowIndex: Int, columnIndex: Int): Any {
        val column = DependencyTableColumn.getByIndex(columnIndex) ?: return ""
        
        if (rowIndex < 0 || rowIndex >= dependencies.size) {
            return column.defaultValue
        }
        
        val dependency = dependencies[rowIndex]
        return column.valueExtractor(dependency)
    }

    /**
     * 获取单元格类型
     */
    override fun getColumnClass(columnIndex: Int): Class<*> {
        return DependencyTableColumn.getByIndex(columnIndex)?.columnClass ?: String::class.java
    }

    /**
     * 单元格是否可编辑
     */
    override fun isCellEditable(rowIndex: Int, columnIndex: Int): Boolean {
        return DependencyTableColumn.getByIndex(columnIndex)?.editable ?: false
    }

    /**
     * 设置单元格值（仅支持选择状态）
     */
    override fun setValueAt(value: Any?, rowIndex: Int, columnIndex: Int) {
        val column = DependencyTableColumn.getByIndex(columnIndex)
        if (column == DependencyTableColumn.SELECTED && rowIndex >= 0 && rowIndex < dependencies.size) {
            // 更新选择状态
            dependencies[rowIndex].selected = value as? Boolean ?: false
            fireTableCellUpdated(rowIndex, columnIndex)
        }
    }

    /**
     * 设置依赖列表
     * @param dependencies 依赖列表
     * @param sortMissingFirst 是否将缺失的记录排到前面
     */
    fun setDependencies(dependencies: List<DependencyInfo>, sortMissingFirst: Boolean = false) {
        val sortedDeps = if (sortMissingFirst) {
            // 将缺失的记录排到前面，其他记录保持原有顺序
            dependencies.sortedWith(compareBy<DependencyInfo> { it.checkStatus != CheckStatus.MISSING })
        } else {
            dependencies
        }
        this.dependencies = sortedDeps.toMutableList()
        // 使用fireTableDataChanged()通知所有监听器数据已更改
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
            val model = DependencyTableModel()
            val table = object : JTable(model) {
                override fun getToolTipText(e: java.awt.event.MouseEvent?): String? {
                    if (e == null) return null
                    
                    val point = e.point
                    val row = rowAtPoint(point)
                    val column = columnAtPoint(point)
                    
                    if (row < 0 || column < 0) return null
                    
                    val tableColumn = DependencyTableColumn.getByIndex(column)
                    val dependency = model.getDependencyAt(row)
                    
                    if (tableColumn == null || dependency == null) return null
                    
                    // 根据列类型返回不同的tooltip
                    return when (tableColumn) {
                        DependencyTableColumn.STATUS -> {
                            when (dependency.checkStatus) {
                                CheckStatus.EXISTS -> "该依赖已存在于私仓中，无需上传"
                                CheckStatus.MISSING -> "该依赖在私仓中缺失，需要上传到私仓"
                                CheckStatus.CHECKING -> "正在检查该依赖在私仓中的状态..."
                                CheckStatus.ERROR -> "检查私仓状态时出错: ${dependency.errorMessage.ifEmpty { "未知错误" }}"
                                CheckStatus.UNKNOWN -> "尚未检查该依赖在私仓中的状态，请点击'重新检查私仓'按钮"
                            }
                        }
                        DependencyTableColumn.LOCAL_PATH -> {
                            if (dependency.isLocalFileExists()) {
                                "本地文件存在: ${dependency.localPath}"
                            } else {
                                "本地文件不存在！\n这是预期的本地仓库路径（根据Maven规范计算）\n实际文件可能已被删除或尚未下载"
                            }
                        }
                        else -> null
                    }
                }
            }

            // 根据列配置设置渲染器和编辑器
            DependencyTableColumn.allColumns.forEachIndexed { index, column ->
                val tableColumn = table.columnModel.getColumn(index)
                
                // 设置选择列的编辑器
                if (column == DependencyTableColumn.SELECTED) {
                    // JTable会自动为Boolean类型使用复选框渲染器
                    tableColumn.cellEditor = javax.swing.DefaultCellEditor(javax.swing.JCheckBox())
                }
                
                // 设置状态列的渲染器
                if (column == DependencyTableColumn.STATUS) {
                    tableColumn.cellRenderer = StatusCellRenderer()
                }
                
                // 设置本地路径列的渲染器
                if (column == DependencyTableColumn.LOCAL_PATH) {
                    tableColumn.cellRenderer = LocalPathCellRenderer()
                }
            }

            // 设置其他列的默认渲染器
            val defaultRenderer = DefaultTableCellRenderer()
            table.setDefaultRenderer(String::class.java, defaultRenderer)

            // 设置行高
            table.rowHeight = 24

            return table
        }
    }

    /**
     * 状态列的渲染器
     * 明确标注是"私仓状态"，避免与本地仓库混淆
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
                        append("私仓-已存在", SimpleTextAttributes(SimpleTextAttributes.STYLE_PLAIN, java.awt.Color.GREEN))
                    }
                    CheckStatus.MISSING -> {
                        append("私仓-缺失", SimpleTextAttributes(SimpleTextAttributes.STYLE_PLAIN, java.awt.Color.RED))
                    }
                    CheckStatus.CHECKING -> {
                        append("私仓-检查中", SimpleTextAttributes(SimpleTextAttributes.STYLE_PLAIN, java.awt.Color.ORANGE))
                    }
                    CheckStatus.ERROR -> {
                        append("私仓-错误", SimpleTextAttributes(SimpleTextAttributes.STYLE_PLAIN, java.awt.Color.RED))
                    }
                    CheckStatus.UNKNOWN -> {
                        append("未检查", SimpleTextAttributes(SimpleTextAttributes.STYLE_PLAIN, java.awt.Color.GRAY))
                    }
                }
            } else {
                append(value?.toString() ?: "", SimpleTextAttributes.REGULAR_ATTRIBUTES)
            }
        }
    }
    
    /**
     * 本地路径列的渲染器
     * 当文件不存在时，显示预期路径并添加提示
     */
    private class LocalPathCellRenderer : ColoredTableCellRenderer() {
        
        override fun customizeCellRenderer(
            table: JTable,
            value: Any?,
            selected: Boolean,
            hasFocus: Boolean,
            row: Int,
            column: Int
        ) {
            val model = table.model as? DependencyTableModel
            val dependency = model?.getDependencyAt(row)
            
            if (dependency != null) {
                val fileExists = dependency.isLocalFileExists()
                
                if (fileExists) {
                    // 文件存在，显示实际路径（可能和预期路径不同，比如有classifier的情况）
                    val actualPath = dependency.localPath
                    append(actualPath, SimpleTextAttributes.REGULAR_ATTRIBUTES)
                } else {
                    // 文件不存在，显示预期路径并添加提示（灰色斜体）
                    val expectedPath = value?.toString() ?: dependency.getExpectedLocalPath()
                    append(expectedPath, SimpleTextAttributes(
                        SimpleTextAttributes.STYLE_ITALIC,
                        java.awt.Color.GRAY
                    ))
                    append(" ⚠ 文件不存在", SimpleTextAttributes(
                        SimpleTextAttributes.STYLE_BOLD or SimpleTextAttributes.STYLE_ITALIC,
                        java.awt.Color(255, 140, 0) // 橙色，更醒目
                    ))
                }
            } else {
                // 无法获取依赖信息，显示原始值
                append(value?.toString() ?: "", SimpleTextAttributes.REGULAR_ATTRIBUTES)
            }
        }
    }
}

