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

    private var allDependencies: List<DependencyInfo> = mutableListOf()
    private var filteredDependencies: List<DependencyInfo> = mutableListOf()
    
    // 过滤和搜索条件
    private var filterType: FilterType = FilterType.ALL
    private var searchText: String = ""
    
    /**
     * 过滤类型枚举
     */
    enum class FilterType {
        ALL,           // 显示全部
        MISSING_ONLY,  // 只看缺失（包括私仓缺失和本地缺失）
        ERROR_ONLY,    // 只看错误
        EXISTS_ONLY    // 只看已存在
    }

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
        return filteredDependencies.size
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
        
        if (rowIndex < 0 || rowIndex >= filteredDependencies.size) {
            return column.defaultValue
        }
        
        val dependency = filteredDependencies[rowIndex]
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
        if (column == DependencyTableColumn.SELECTED && rowIndex >= 0 && rowIndex < filteredDependencies.size) {
            // 更新选择状态（需要更新原始数据）
            val dependency = filteredDependencies[rowIndex]
            dependency.selected = value as? Boolean ?: false
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
        this.allDependencies = sortedDeps.toMutableList()
        applyFilters()
    }
    
    /**
     * 设置过滤类型
     */
    fun setFilterType(filterType: FilterType) {
        this.filterType = filterType
        applyFilters()
    }
    
    /**
     * 设置搜索文本
     */
    fun setSearchText(text: String) {
        this.searchText = text.trim()
        applyFilters()
    }
    
    /**
     * 应用过滤和搜索条件
     */
    private fun applyFilters() {
        var result = allDependencies
        
        // 应用过滤类型
        result = when (filterType) {
            FilterType.ALL -> result
            FilterType.MISSING_ONLY -> result.filter { 
                // 缺失：私仓缺失或本地缺失
                it.checkStatus == CheckStatus.MISSING || !it.isLocalFileExists()
            }
            FilterType.ERROR_ONLY -> result.filter { 
                it.checkStatus == CheckStatus.ERROR 
            }
            FilterType.EXISTS_ONLY -> result.filter { 
                it.checkStatus == CheckStatus.EXISTS 
            }
        }
        
        // 应用搜索文本
        if (searchText.isNotEmpty()) {
            val searchLower = searchText.lowercase()
            result = result.filter { dep ->
                dep.groupId.lowercase().contains(searchLower) ||
                dep.artifactId.lowercase().contains(searchLower) ||
                dep.version.lowercase().contains(searchLower)
            }
        }
        
        filteredDependencies = result
        fireTableDataChanged()
    }

    /**
     * 获取指定行的依赖信息
     */
    fun getDependencyAt(rowIndex: Int): DependencyInfo? {
        return if (rowIndex >= 0 && rowIndex < filteredDependencies.size) {
            filteredDependencies[rowIndex]
        } else null
    }

    /**
     * 获取所有依赖（包括未过滤的）
     */
    fun getDependencies(): List<DependencyInfo> {
        return allDependencies.toList()
    }
    
    /**
     * 获取过滤后的依赖
     */
    fun getFilteredDependencies(): List<DependencyInfo> {
        return filteredDependencies.toList()
    }

    /**
     * 设置所有行的选择状态（仅对过滤后的依赖）
     */
    fun setAllSelected(selected: Boolean) {
        filteredDependencies.forEach { it.selected = selected }
        fireTableDataChanged()
    }

    /**
     * 切换指定行的选择状态
     */
    fun toggleSelection(rowIndex: Int) {
        if (rowIndex >= 0 && rowIndex < filteredDependencies.size) {
            filteredDependencies[rowIndex].selected = !filteredDependencies[rowIndex].selected
            fireTableRowsUpdated(rowIndex, rowIndex)
        }
    }

    /**
     * 获取选中的依赖（从所有依赖中获取，不限于过滤后的）
     */
    fun getSelectedDependencies(): List<DependencyInfo> {
        return allDependencies.filter { it.selected }
    }

    /**
     * 获取选中依赖的数量（从所有依赖中统计）
     */
    fun getSelectedCount(): Int {
        return allDependencies.count { it.selected }
    }
    
    /**
     * 获取当前过滤后的数量
     */
    fun getFilteredCount(): Int {
        return filteredDependencies.size
    }
    
    /**
     * 获取总数
     */
    fun getTotalCount(): Int {
        return allDependencies.size
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

            // 设置其他列的默认渲染器（带颜色区分）
            // 为没有特殊渲染器的列设置行颜色渲染器
            DependencyTableColumn.allColumns.forEachIndexed { index, column ->
                val tableColumn = table.columnModel.getColumn(index)
                // 如果该列没有设置特殊渲染器，则使用行颜色渲染器
                if (column != DependencyTableColumn.STATUS && 
                    column != DependencyTableColumn.LOCAL_PATH &&
                    column != DependencyTableColumn.SELECTED) {
                    tableColumn.cellRenderer = RowColorRenderer()
                }
            }

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
            val model = table.model as? DependencyTableModel
            val dependency = model?.getDependencyAt(row)
            
            if (value is CheckStatus) {
                when (value) {
                    CheckStatus.EXISTS -> {
                        append("私仓-已存在", SimpleTextAttributes(SimpleTextAttributes.STYLE_PLAIN, java.awt.Color.GREEN))
                    }
                    CheckStatus.MISSING -> {
                        append("私仓-缺失", SimpleTextAttributes(SimpleTextAttributes.STYLE_PLAIN, java.awt.Color.YELLOW.darker()))
                    }
                    CheckStatus.CHECKING -> {
                        append("私仓-检查中", SimpleTextAttributes(SimpleTextAttributes.STYLE_PLAIN, java.awt.Color.ORANGE))
                    }
                    CheckStatus.ERROR -> {
                        append("私仓-错误", SimpleTextAttributes(SimpleTextAttributes.STYLE_PLAIN, java.awt.Color.RED))
                    }
                    CheckStatus.UNKNOWN -> {
                        // 如果本地文件也不存在，显示为缺失状态
                        val isLocalMissing = dependency != null && !dependency.isLocalFileExists()
                        if (isLocalMissing) {
                            append("未检查（本地缺失）", SimpleTextAttributes(SimpleTextAttributes.STYLE_PLAIN, java.awt.Color.YELLOW.darker()))
                        } else {
                            append("未检查", SimpleTextAttributes(SimpleTextAttributes.STYLE_PLAIN, java.awt.Color.GRAY))
                        }
                    }
                }
            } else {
                append(value?.toString() ?: "", SimpleTextAttributes.REGULAR_ATTRIBUTES)
            }
        }
    }
    
    /**
     * 行颜色渲染器
     * 根据依赖状态为整行设置文字颜色
     */
    private class RowColorRenderer : ColoredTableCellRenderer() {
        
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
                // 判断是否为缺失状态（私仓缺失或本地缺失）
                val isMissing = dependency.checkStatus == CheckStatus.MISSING || !dependency.isLocalFileExists()
                val isError = dependency.checkStatus == CheckStatus.ERROR
                val isExists = dependency.checkStatus == CheckStatus.EXISTS
                
                val textColor = when {
                    isError -> java.awt.Color.RED
                    isMissing -> java.awt.Color(200, 150, 0) // 黄色（深一点，更易读）
                    isExists -> java.awt.Color(100, 150, 100) // 绿色（深一点，更易读）
                    else -> java.awt.Color.GRAY
                }
                
                append(value?.toString() ?: "", SimpleTextAttributes(SimpleTextAttributes.STYLE_PLAIN, textColor))
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

