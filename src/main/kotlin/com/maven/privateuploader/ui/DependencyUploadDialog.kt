package com.maven.privateuploader.ui

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.Messages
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextField
import javax.swing.JTable
import com.intellij.util.ui.JBUI
import javax.swing.event.DocumentEvent
import javax.swing.event.DocumentListener
import com.maven.privateuploader.client.PrivateRepositoryClient
import com.maven.privateuploader.config.PrivateRepoConfigurable
import com.maven.privateuploader.model.CheckStatus
import com.maven.privateuploader.model.DependencyInfo
import com.maven.privateuploader.model.RepositoryConfig
import com.maven.privateuploader.service.DependencyUploadService
import com.maven.privateuploader.ui.table.DependencyTableColumn
import com.maven.privateuploader.ui.table.DependencyTableModel
import java.awt.*
import javax.swing.*

/**
 * 依赖上传主对话框
 * 显示依赖列表，提供预检查和上传功能
 */
class DependencyUploadDialog(private val project: Project) : DialogWrapper(project) {

    private val logger = thisLogger()
    private val uploadService = ApplicationManager.getApplication().getService(DependencyUploadService::class.java)

    // 使用 lateinit var 避免 nullable 和 !! 的使用
    private lateinit var dependencyTable: JTable
    private lateinit var tableModel: DependencyTableModel
    private lateinit var tableScrollPane: JBScrollPane
    private lateinit var statusLabel: JBLabel
    private lateinit var projectInfoLabel: JBLabel
    private lateinit var checkAllButton: JButton
    private lateinit var uncheckAllButton: JButton
    private lateinit var scanButton: JButton
    private lateinit var refreshButton: JButton
    private lateinit var configButton: JButton
    private lateinit var uploadButton: JButton
    private lateinit var oneClickButton: JButton
    private lateinit var advancedToggleButton: JButton
    private lateinit var advancedPanel: JPanel
    private lateinit var showSearchButton: JButton
    
    // 过滤和搜索组件
    private lateinit var filterPanel: JPanel
    private lateinit var searchTextField: JBTextField
    private lateinit var filterAllButton: JButton
    private lateinit var filterMissingButton: JButton
    private lateinit var filterErrorButton: JButton
    private lateinit var filterExistsButton: JButton

    private var dependencies: List<DependencyInfo> = emptyList()
    private var config: RepositoryConfig? = null

    init {
        title = "Maven依赖上传到私仓"
        isModal = true
        setOKButtonText("关闭")
        init()
        
        // Dialog打开时只扫描依赖，不检查私仓（保持原有行为，不自动执行一键操作）
        SwingUtilities.invokeLater { 
            scanDependenciesOnly() 
        }
    }
    
    override fun doOKAction() {
        close(OK_EXIT_CODE)
    }

    override fun createCenterPanel(): JComponent {
        val panel = createMainPanel()
        // 设置面板的 preferredSize，让 DialogWrapper 使用这个大小
        // 表格各列总宽度约1000像素，加上边距和滚动条，设置为1500x900以提供更好的显示空间
        panel.preferredSize = Dimension(1500, 900)
        
        // 在面板添加到窗口后，再次确保窗口大小正确
        SwingUtilities.invokeLater {
            window?.setSize(1500, 900)
            window?.setLocationRelativeTo(null)
        }
        
        return panel
    }

    private fun createMainPanel(): JPanel {
        // 项目信息面板
        val projectInfoPanel = createProjectInfoPanel()

        // 过滤和搜索面板
        val filterSearchPanel = createFilterSearchPanel()

        // 工具栏面板
        val toolbarPanel = createToolbarPanel()

        // 依赖表格
        createDependencyTable()
        tableScrollPane = JBScrollPane(dependencyTable)

        // 状态栏
        createStatusBar()

        // 主面板布局
        val mainPanel = JPanel(BorderLayout(0, JBUI.scale(5)))
        mainPanel.border = JBUI.Borders.empty(10)

        // 将项目信息、过滤搜索和工具栏放在一个垂直面板中
        val topPanel = JPanel(BorderLayout())
        topPanel.add(projectInfoPanel, BorderLayout.NORTH)
        topPanel.add(filterSearchPanel, BorderLayout.CENTER)
        topPanel.add(toolbarPanel, BorderLayout.SOUTH)
        mainPanel.add(topPanel, BorderLayout.NORTH)

        // 表格面板
        val tablePanel = JPanel(BorderLayout())
        tablePanel.add(JBLabel("依赖列表:"), BorderLayout.NORTH)
        tablePanel.add(tableScrollPane, BorderLayout.CENTER)
        mainPanel.add(tablePanel, BorderLayout.CENTER)

        mainPanel.add(statusLabel, BorderLayout.SOUTH)

        return mainPanel
    }

    private fun createProjectInfoPanel(): JPanel {
        projectInfoLabel = JBLabel()
        projectInfoLabel.border = JBUI.Borders.emptyBottom(10)

        val panel = JPanel(BorderLayout())
        panel.add(projectInfoLabel, BorderLayout.CENTER)
        panel.border = javax.swing.BorderFactory.createCompoundBorder(
            javax.swing.BorderFactory.createLineBorder(java.awt.Color.GRAY),
            javax.swing.BorderFactory.createEmptyBorder(10, 10, 10, 10)
        )

        return panel
    }

    private fun createFilterSearchPanel(): JPanel {
        filterPanel = JPanel()
        filterPanel.layout = BoxLayout(filterPanel, BoxLayout.X_AXIS)
        filterPanel.border = javax.swing.BorderFactory.createEmptyBorder(5, 0, 5, 0)
        // 默认隐藏搜索和过滤面板
        filterPanel.isVisible = false

        // 搜索框
        val searchLabel = JBLabel("搜索:")
        searchTextField = JBTextField()
        searchTextField.toolTipText = "输入 artifactId、groupId 或 version 进行过滤"
        searchTextField.preferredSize = Dimension(300, searchTextField.preferredSize.height)
        searchTextField.maximumSize = Dimension(300, searchTextField.preferredSize.height)
        
        // 搜索框文本变化监听
        searchTextField.document.addDocumentListener(object : DocumentListener {
            override fun insertUpdate(e: DocumentEvent?) {
                applySearchFilter()
            }
            override fun removeUpdate(e: DocumentEvent?) {
                applySearchFilter()
            }
            override fun changedUpdate(e: DocumentEvent?) {
                applySearchFilter()
            }
        })

        // 过滤标签按钮
        filterAllButton = JButton("全部")
        filterAllButton.isSelected = true
        filterAllButton.addActionListener { 
            setFilterType(DependencyTableModel.FilterType.ALL)
            updateFilterButtons(filterAllButton)
        }

        filterMissingButton = JButton("只看缺失")
        filterMissingButton.toolTipText = "显示私仓缺失或本地缺失的依赖"
        filterMissingButton.addActionListener { 
            setFilterType(DependencyTableModel.FilterType.MISSING_ONLY)
            updateFilterButtons(filterMissingButton)
        }

        filterErrorButton = JButton("只看错误")
        filterErrorButton.toolTipText = "显示检查出错的依赖"
        filterErrorButton.addActionListener { 
            setFilterType(DependencyTableModel.FilterType.ERROR_ONLY)
            updateFilterButtons(filterErrorButton)
        }

        filterExistsButton = JButton("只看已存在")
        filterExistsButton.toolTipText = "显示已存在于私仓的依赖"
        filterExistsButton.addActionListener { 
            setFilterType(DependencyTableModel.FilterType.EXISTS_ONLY)
            updateFilterButtons(filterExistsButton)
        }

        // 设置按钮样式（切换按钮样式）
        val filterButtons = listOf(filterAllButton, filterMissingButton, filterErrorButton, filterExistsButton)
        filterButtons.forEach { button ->
            button.isFocusPainted = false
            button.border = javax.swing.BorderFactory.createCompoundBorder(
                javax.swing.BorderFactory.createLineBorder(java.awt.Color.GRAY),
                javax.swing.BorderFactory.createEmptyBorder(5, 10, 5, 10)
            )
        }

        // 添加到面板
        filterPanel.add(searchLabel)
        filterPanel.add(Box.createHorizontalStrut(5))
        filterPanel.add(searchTextField)
        filterPanel.add(Box.createHorizontalStrut(15))
        filterPanel.add(filterAllButton)
        filterPanel.add(Box.createHorizontalStrut(5))
        filterPanel.add(filterMissingButton)
        filterPanel.add(Box.createHorizontalStrut(5))
        filterPanel.add(filterErrorButton)
        filterPanel.add(Box.createHorizontalStrut(5))
        filterPanel.add(filterExistsButton)
        filterPanel.add(Box.createHorizontalGlue())

        return filterPanel
    }

    /**
     * 设置过滤类型
     */
    private fun setFilterType(filterType: DependencyTableModel.FilterType) {
        tableModel.setFilterType(filterType)
        updateStatusWithCounts()
    }

    /**
     * 应用搜索过滤
     */
    private fun applySearchFilter() {
        val searchText = searchTextField.text
        tableModel.setSearchText(searchText)
        updateStatusWithCounts()
    }

    /**
     * 更新过滤按钮的选中状态
     */
    private fun updateFilterButtons(selectedButton: JButton) {
        val buttons = listOf(filterAllButton, filterMissingButton, filterErrorButton, filterExistsButton)
        buttons.forEach { button ->
            if (button == selectedButton) {
                button.isSelected = true
                button.background = java.awt.Color(200, 220, 255) // 浅蓝色背景
            } else {
                button.isSelected = false
                button.background = null // 默认背景
            }
        }
    }

    /**
     * 更新状态栏，显示过滤后的数量
     */
    private fun updateStatusWithCounts() {
        val total = tableModel.getTotalCount()
        val filtered = tableModel.getFilteredCount()
        val selected = tableModel.getSelectedCount()
        
        val statusText = if (filtered < total) {
            "显示 $filtered / $total 个依赖（已选中 $selected 个）"
        } else {
            "共 $total 个依赖（已选中 $selected 个）"
        }
        updateStatus(statusText)
    }

    private fun createToolbarPanel(): JPanel {
        // 主按钮：一键扫描并检查缺失依赖
        oneClickButton = JButton("一键扫描并检查缺失依赖")
        oneClickButton.addActionListener { oneClickScanAndCheck() }
        // 设置主按钮样式，使其更突出
        oneClickButton.font = oneClickButton.font.deriveFont(java.awt.Font.BOLD, oneClickButton.font.size + 1f)

        // 上传按钮（核心功能，从高级面板移出）
        uploadButton = JButton("上传到私仓")
        uploadButton.addActionListener { uploadSelectedDependencies() }
        // 初始状态禁用上传按钮
        uploadButton.isEnabled = false

        // 高级操作按钮（切换显示/隐藏）
        advancedToggleButton = JButton("高级操作 ▼")
        advancedToggleButton.addActionListener { toggleAdvancedOptions() }

        // 高级操作面板（默认隐藏）
        advancedPanel = JPanel()
        advancedPanel.layout = BoxLayout(advancedPanel, BoxLayout.X_AXIS)
        advancedPanel.isVisible = false

        checkAllButton = JButton("全部勾选")
        checkAllButton.addActionListener { selectAllDependencies(true) }

        uncheckAllButton = JButton("全部取消")
        uncheckAllButton.addActionListener { selectAllDependencies(false) }

        scanButton = JButton("重新扫描依赖")
        scanButton.addActionListener { rescanDependencies() }

        refreshButton = JButton("重新检查私仓")
        refreshButton.addActionListener { recheckRepositoryStatus() }

        configButton = JButton("私仓设置")
        configButton.addActionListener { openSettings() }

        // 打开搜索框按钮（添加到高级面板）
        showSearchButton = JButton("打开搜索框")
        showSearchButton.addActionListener { toggleSearchPanel() }

        // 将高级操作按钮添加到高级面板
        advancedPanel.add(checkAllButton)
        advancedPanel.add(Box.createHorizontalStrut(10))
        advancedPanel.add(uncheckAllButton)
        advancedPanel.add(Box.createHorizontalStrut(10))
        advancedPanel.add(scanButton)
        advancedPanel.add(Box.createHorizontalStrut(10))
        advancedPanel.add(refreshButton)
        advancedPanel.add(Box.createHorizontalStrut(10))
        advancedPanel.add(configButton)
        advancedPanel.add(Box.createHorizontalStrut(10))
        advancedPanel.add(showSearchButton)
        advancedPanel.add(Box.createHorizontalGlue())

        // 主工具栏面板
        val toolbarPanel = JPanel()
        toolbarPanel.layout = BoxLayout(toolbarPanel, BoxLayout.Y_AXIS)
        toolbarPanel.border = javax.swing.BorderFactory.createEmptyBorder(5, 0, 5, 0)

        // 第一行：主按钮、上传按钮和高级操作切换按钮
        val mainButtonPanel = JPanel()
        mainButtonPanel.layout = BoxLayout(mainButtonPanel, BoxLayout.X_AXIS)
        mainButtonPanel.add(oneClickButton)
        mainButtonPanel.add(Box.createHorizontalStrut(10))
        mainButtonPanel.add(uploadButton)
        mainButtonPanel.add(Box.createHorizontalStrut(10))
        mainButtonPanel.add(advancedToggleButton)
        mainButtonPanel.add(Box.createHorizontalGlue())

        toolbarPanel.add(mainButtonPanel)
        toolbarPanel.add(Box.createVerticalStrut(5))
        toolbarPanel.add(advancedPanel)

        return toolbarPanel
    }

    private fun createDependencyTable() {
        // 使用DependencyTableModel的createTable方法来创建带渲染器的表格
        dependencyTable = DependencyTableModel.createTable()
        tableModel = dependencyTable.model as DependencyTableModel

        // 设置表格属性
        dependencyTable.autoResizeMode = JTable.AUTO_RESIZE_OFF  // 关闭自动调整，使用固定列宽
        dependencyTable.selectionModel.selectionMode = ListSelectionModel.MULTIPLE_INTERVAL_SELECTION
        dependencyTable.rowHeight = 24
        dependencyTable.fillsViewportHeight = true  // 确保表格填充视口

        // 根据列配置设置列宽
        val columnModel = dependencyTable.columnModel
        DependencyTableColumn.allColumns.forEachIndexed { index, column ->
            columnModel.getColumn(index).preferredWidth = column.preferredWidth
        }
    }

    private fun createStatusBar() {
        statusLabel = JBLabel("就绪")
        statusLabel.border = JBUI.Borders.empty(5, 0, 0, 0)
    }

    /**
     * 统一管理按钮启用/禁用状态
     * 注意：上传按钮的启用状态单独管理，不在此方法中控制
     */
    private fun setButtonsEnabled(enabled: Boolean) {
        oneClickButton.isEnabled = enabled
        advancedToggleButton.isEnabled = enabled
        checkAllButton.isEnabled = enabled
        uncheckAllButton.isEnabled = enabled
        scanButton.isEnabled = enabled
        refreshButton.isEnabled = enabled
        configButton.isEnabled = enabled
        // 上传按钮的启用状态单独管理，不在此方法中控制
    }

    override fun getPreferredFocusedComponent(): JComponent {
        return dependencyTable
    }

    /**
     * 仅扫描依赖（不检查私仓）
     * @param autoCheckAfterScan 扫描完成后是否自动检查私仓
     */
    private fun scanDependenciesOnly(autoCheckAfterScan: Boolean = false) {
        if (!uploadService.isMavenProject(project)) {
            Messages.showErrorDialog(
                project,
                "当前项目不是Maven项目，无法使用此功能",
                "错误"
            )
            return
        }

        // 更新项目信息
        val projectInfo = uploadService.getMavenProjectInfo(project)
        projectInfoLabel.text = projectInfo

        // 加载配置（但不检查）
        config = PrivateRepoConfigurable.getConfig()

        updateStatus("正在扫描 Maven 依赖…")
        setButtonsEnabled(false)

        ProgressManager.getInstance().run(object : Task.Backgroundable(project, "扫描Maven依赖", true) {
            override fun run(indicator: ProgressIndicator) {
                try {
                    indicator.isIndeterminate = true
                    indicator.text = "正在扫描 Maven 依赖…"
                    
                    val analyzer = com.maven.privateuploader.analyzer.MavenDependencyAnalyzer(project)
                    val deps = analyzer.analyzeDependencies(indicator)

                    ApplicationManager.getApplication().invokeLater {
                        updateTableData(deps, "扫描完成，共发现 ${deps.size} 个依赖")
                        setButtonsEnabled(true)
                        
                        // 如果配置完整且需要自动检查，则自动检查私仓
                        if (autoCheckAfterScan) {
                            val currentConfig = config
                            if (currentConfig != null && currentConfig.enabled && currentConfig.isValid()) {
                                checkRepositoryStatus()
                            }
                        }
                    }
                } catch (e: Exception) {
                    logger.error("依赖扫描时发生错误", e)
                    ApplicationManager.getApplication().invokeLater {
                        updateStatus("依赖扫描失败: ${e.message}")
                        setButtonsEnabled(true)
                    }
                }
            }
        })
    }

    /**
     * 检查私仓状态
     */
    private fun checkRepositoryStatus() {
        if (dependencies.isEmpty()) {
            Messages.showInfoMessage(
                project,
                "没有发现依赖，无法检查私仓状态",
                "提示"
            )
            return
        }

        val currentConfig = config
        if (currentConfig == null || !currentConfig.enabled || !currentConfig.isValid()) {
            val result = Messages.showDialog(
                project,
                "私仓配置不完整，无法检查依赖状态。请先配置私仓设置。",
                "配置错误",
                arrayOf("去配置", "取消"),
                0,
                Messages.getWarningIcon(),
                null
            )
            if (result == 0) {
                openSettings()
            }
            return
        }

        updateStatus("正在检查私仓依赖状态…")
        setButtonsEnabled(false)

        ProgressManager.getInstance().run(object : Task.Backgroundable(project, "检查私仓依赖状态", true) {
            override fun run(indicator: ProgressIndicator) {
                try {
                    indicator.isIndeterminate = true
                    indicator.text = "正在检查私仓依赖状态…"
                    
                    val client = PrivateRepositoryClient(currentConfig)
                    client.checkDependenciesExist(dependencies.toMutableList(), indicator)
                    
                    ApplicationManager.getApplication().invokeLater {
                        val missingCount = dependencies.count { it.checkStatus == CheckStatus.MISSING }
                        val existsCount = dependencies.count { it.checkStatus == CheckStatus.EXISTS }
                        val errorCount = dependencies.count { it.checkStatus == CheckStatus.ERROR }
                        // 检查完成后，自动将缺失的记录排到前面
                        updateTableData(dependencies, "私仓检查完成：缺失 $missingCount 个，已存在 $existsCount 个", sortMissingFirst = true)
                        setButtonsEnabled(true)
                        // 检查完成后启用上传按钮
                        uploadButton.isEnabled = true
                    }
                } catch (e: Exception) {
                    logger.error("检查私仓依赖状态时发生错误", e)
                    ApplicationManager.getApplication().invokeLater {
                        updateStatus("私仓检查失败: ${e.message}")
                        setButtonsEnabled(true)
                    }
                }
            }
        })
    }

    /**
     * 更新表格数据（统一方法）
     * @param deps 依赖列表
     * @param statusMessage 状态消息
     * @param sortMissingFirst 是否将缺失的记录排到前面（检查完成后自动排序）
     */
    private fun updateTableData(deps: List<DependencyInfo>, statusMessage: String, sortMissingFirst: Boolean = false) {
        logger.info("更新表格数据，依赖数量: ${deps.size}")
        
        dependencies = deps
        
        // 更新模型数据（这会触发fireTableDataChanged）
        tableModel.setDependencies(deps, sortMissingFirst)
        
        logger.info("表格数据已更新，行数: ${tableModel.rowCount}")
        
        // 更新状态（显示过滤后的数量）
        updateStatusWithCounts()
    }

    /**
     * 重新扫描依赖
     */
    private fun rescanDependencies() {
        scanDependenciesOnly()
    }

    /**
     * 重新检查私仓状态
     */
    private fun recheckRepositoryStatus() {
        checkRepositoryStatus()
    }

    /**
     * 一键扫描并检查缺失依赖
     * 执行流程：扫描 Maven 依赖 → 检查私仓存在性 → 展示缺失依赖（并默认勾选）
     */
    private fun oneClickScanAndCheck() {
        if (!uploadService.isMavenProject(project)) {
            Messages.showErrorDialog(
                project,
                "当前项目不是Maven项目，无法使用此功能",
                "错误"
            )
            return
        }

        // 更新项目信息
        val projectInfo = uploadService.getMavenProjectInfo(project)
        projectInfoLabel.text = projectInfo

        // 加载配置
        config = PrivateRepoConfigurable.getConfig()
        val currentConfig = config
        if (currentConfig == null || !currentConfig.enabled || !currentConfig.isValid()) {
            val result = Messages.showDialog(
                project,
                "私仓配置不完整，无法检查依赖状态。请先配置私仓设置。",
                "配置错误",
                arrayOf("去配置", "取消"),
                0,
                Messages.getWarningIcon(),
                null
            )
            if (result == 0) {
                openSettings()
            }
            return
        }

        updateStatus("正在扫描并检查依赖…")
        setButtonsEnabled(false)

        ProgressManager.getInstance().run(object : Task.Backgroundable(project, "一键扫描并检查缺失依赖", true) {
            override fun run(indicator: ProgressIndicator) {
                try {
                    // 第一步：扫描 Maven 依赖
                    indicator.isIndeterminate = true
                    indicator.text = "正在扫描 Maven 依赖…"
                    
                    val analyzer = com.maven.privateuploader.analyzer.MavenDependencyAnalyzer(project)
                    val deps = analyzer.analyzeDependencies(indicator)

                    ApplicationManager.getApplication().invokeLater {
                        updateTableData(deps, "扫描完成，共发现 ${deps.size} 个依赖，正在检查私仓…")
                    }

                    // 第二步：检查私仓存在性
                    indicator.text = "正在检查私仓依赖状态…"
                    val client = PrivateRepositoryClient(currentConfig)
                    client.checkDependenciesExist(deps.toMutableList(), indicator)

                    // 第三步：自动勾选缺失的依赖
                    deps.forEach { dep ->
                        if (dep.checkStatus == CheckStatus.MISSING) {
                            dep.selected = true
                        } else {
                            dep.selected = false
                        }
                    }

                    ApplicationManager.getApplication().invokeLater {
                        val missingCount = deps.count { it.checkStatus == CheckStatus.MISSING }
                        val existsCount = deps.count { it.checkStatus == CheckStatus.EXISTS }
                        val errorCount = deps.count { it.checkStatus == CheckStatus.ERROR }
                        val selectedCount = deps.count { it.selected }
                        
                        // 检查完成后，自动将缺失的记录排到前面
                        updateTableData(deps, "检查完成：缺失 $missingCount 个（已自动勾选 $selectedCount 个），已存在 $existsCount 个", sortMissingFirst = true)
                        setButtonsEnabled(true)
                        // 检查完成后启用上传按钮
                        uploadButton.isEnabled = true
                    }
                } catch (e: Exception) {
                    logger.error("一键扫描并检查时发生错误", e)
                    ApplicationManager.getApplication().invokeLater {
                        updateStatus("操作失败: ${e.message}")
                        setButtonsEnabled(true)
                    }
                }
            }
        })
    }

    /**
     * 切换高级操作面板的显示/隐藏
     */
    private fun toggleAdvancedOptions() {
        advancedPanel.isVisible = !advancedPanel.isVisible
        advancedToggleButton.text = if (advancedPanel.isVisible) "高级操作 ▲" else "高级操作 ▼"
        // 重新布局
        advancedPanel.parent?.revalidate()
        advancedPanel.parent?.repaint()
    }

    /**
     * 切换搜索面板的显示/隐藏
     */
    private fun toggleSearchPanel() {
        filterPanel.isVisible = !filterPanel.isVisible
        showSearchButton.text = if (filterPanel.isVisible) "关闭搜索框" else "打开搜索框"
        // 重新布局
        filterPanel.parent?.revalidate()
        filterPanel.parent?.repaint()
    }

    /**
     * 选择/取消选择所有依赖
     */
    private fun selectAllDependencies(selected: Boolean) {
        tableModel.setAllSelected(selected)
        updateStatusWithCounts()
    }


    /**
     * 打开设置页面
     */
    private fun openSettings() {
        com.intellij.openapi.options.ShowSettingsUtil.getInstance().showSettingsDialog(project, "Maven私仓上传")
        // 重新加载配置并重新初始化
        config = PrivateRepoConfigurable.getConfig()
        // 配置变更后重新初始化依赖分析
        reinitializeAfterConfigChange()
    }

    /**
     * 配置变更后的重新初始化流程
     * 统一流程：重新扫描依赖 → 重新检查私仓
     */
    private fun reinitializeAfterConfigChange() {
        // 重新加载配置
        config = PrivateRepoConfigurable.getConfig()
        
        // 统一流程：扫描完成后自动检查
        scanDependenciesOnly(autoCheckAfterScan = true)
    }

    /**
     * 上传选中的依赖
     */
    private fun uploadSelectedDependencies() {
        // 从 tableModel 读取选中状态，保证数据一致性
        val selectedDependencies = tableModel.getSelectedDependencies()

        if (selectedDependencies.isEmpty()) {
            Messages.showInfoMessage(
                project,
                "请先选择要上传的依赖",
                "提示"
            )
            return
        }

        val currentConfig = config
        if (currentConfig == null || !currentConfig.isValid()) {
            val result = Messages.showDialog(
                project,
                "私仓配置不完整，无法上传依赖。请先配置私仓设置。",
                "配置错误",
                arrayOf("去配置", "取消"),
                0,
                Messages.getWarningIcon(),
                null
            )
            if (result == 0) {
                openSettings()
            }
            return
        }

        // 确认上传
        val result = Messages.showYesNoDialog(
            project,
            "确定要上传选中的 ${selectedDependencies.size} 个依赖到私仓吗？",
            "确认上传",
            Messages.getQuestionIcon()
        )

        if (result != Messages.YES) {
            return
        }

        updateStatus("正在上传依赖（0/${selectedDependencies.size}）…")
        setButtonsEnabled(false)

        // 显示进度对话框
        val progressDialog = UploadProgressDialog(this, selectedDependencies)
        progressDialog.show()

        uploadService.uploadSelectedDependencies(
            project,
            currentConfig,
            tableModel.getDependencies(), // 从tableModel读取，不使用旧引用
            selectedDependencies,
            onProgress = { current, currentCount, totalCount ->
                SwingUtilities.invokeLater {
                    updateStatus("正在上传依赖（$currentCount/$totalCount）…")
                    progressDialog.updateProgress(current, currentCount, totalCount)
                }
            },
            onComplete = { summary ->
                SwingUtilities.invokeLater {
                    progressDialog.closeDialog(summary)
                    if (summary.hasFailures()) {
                        showUploadResult(summary, true)
                        updateStatus("上传完成，可查看结果")
                    } else {
                        showUploadResult(summary, false)
                        updateStatus("上传完成，可查看结果")
                    }
                    setButtonsEnabled(true)
                    
                    // 上传完成后必须重新扫描 + 重新检查
                    scanDependenciesOnly(autoCheckAfterScan = true)
                }
            }
        )
    }

    /**
     * 显示上传结果
     */
    private fun showUploadResult(summary: com.maven.privateuploader.service.UploadSummary, hasFailures: Boolean) {
        val title = if (hasFailures) "上传完成（有失败）" else "上传完成"

        val message = StringBuilder()
        message.appendLine("上传完成！")
        message.appendLine("总数: ${summary.totalCount}")
        message.appendLine("成功: ${summary.successCount}")
        message.appendLine("失败: ${summary.failureCount}")

        if (summary.hasFailures()) {
            message.appendLine()
            message.appendLine("失败的依赖:")
            summary.failedUploads.forEach { dep ->
                message.appendLine("- ${dep.getGAV()}: ${dep.errorMessage}")
            }
        }

        if (hasFailures) {
            Messages.showWarningDialog(
                project,
                message.toString(),
                title
            )
        } else {
            Messages.showInfoMessage(
                project,
                message.toString(),
                title
            )
        }
    }

    /**
     * 更新状态栏（标准化文案）
     */
    private fun updateStatus(message: String) {
        statusLabel.text = message
    }
}