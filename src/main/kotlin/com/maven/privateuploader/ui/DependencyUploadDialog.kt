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
import javax.swing.JTable
import com.intellij.util.ui.JBUI
import com.maven.privateuploader.client.PrivateRepositoryClient
import com.maven.privateuploader.config.PrivateRepoConfigurable
import com.maven.privateuploader.model.CheckStatus
import com.maven.privateuploader.model.DependencyInfo
import com.maven.privateuploader.model.RepositoryConfig
import com.maven.privateuploader.service.DependencyUploadService
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
    private lateinit var refreshButton: JButton
    private lateinit var configButton: JButton
    private lateinit var uploadButton: JButton

    private var dependencies: List<DependencyInfo> = emptyList()
    private var config: RepositoryConfig? = null

    init {
        title = "Maven依赖上传到私仓"
        isModal = true
        setOKButtonText("关闭")
        init()
        
        // 提前开始初始化依赖分析
        SwingUtilities.invokeLater {
            initializeDependencies()
        }
    }

    override fun createCenterPanel(): JComponent {
        return createMainPanel()
    }

    private fun createMainPanel(): JPanel {
        // 项目信息面板
        val projectInfoPanel = createProjectInfoPanel()

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

        // 将项目信息和工具栏放在一个垂直面板中
        val topPanel = JPanel(BorderLayout())
        topPanel.add(projectInfoPanel, BorderLayout.NORTH)
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

    private fun createToolbarPanel(): JPanel {
        checkAllButton = JButton("全选")
        checkAllButton.addActionListener { selectAllDependencies(true) }

        uncheckAllButton = JButton("全不选")
        uncheckAllButton.addActionListener { selectAllDependencies(false) }

        refreshButton = JButton("重新检查")
        refreshButton.addActionListener { refreshDependencies() }

        configButton = JButton("配置")
        configButton.addActionListener { openSettings() }

        uploadButton = JButton("上传选中依赖")
        uploadButton.addActionListener { uploadSelectedDependencies() }

        // 使用 BoxLayout 替代 FlowLayout + Glue 的组合
        val toolbarPanel = JPanel()
        toolbarPanel.layout = BoxLayout(toolbarPanel, BoxLayout.X_AXIS)
        toolbarPanel.border = javax.swing.BorderFactory.createEmptyBorder(5, 0, 5, 0)

        toolbarPanel.add(checkAllButton)
        toolbarPanel.add(Box.createHorizontalStrut(10))
        toolbarPanel.add(uncheckAllButton)
        toolbarPanel.add(Box.createHorizontalStrut(10))
        toolbarPanel.add(refreshButton)
        toolbarPanel.add(Box.createHorizontalStrut(10))
        toolbarPanel.add(configButton)
        toolbarPanel.add(Box.createHorizontalGlue())
        toolbarPanel.add(uploadButton)

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

        // 设置列宽
        val columnModel = dependencyTable.columnModel
        columnModel.getColumn(0).preferredWidth = 50  // 选择
        columnModel.getColumn(1).preferredWidth = 200 // GroupId
        columnModel.getColumn(2).preferredWidth = 150 // ArtifactId
        columnModel.getColumn(3).preferredWidth = 100 // Version
        columnModel.getColumn(4).preferredWidth = 80  // Packaging
        columnModel.getColumn(5).preferredWidth = 120 // 状态
        columnModel.getColumn(6).preferredWidth = 300 // 路径
    }

    private fun createStatusBar() {
        statusLabel = JBLabel("就绪")
        statusLabel.border = JBUI.Borders.empty(5, 0, 0, 0)
    }

    override fun getPreferredFocusedComponent(): JComponent {
        return dependencyTable
    }

    /**
     * 初始化依赖分析
     */
    private fun initializeDependencies() {
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

        // 检查配置
        config = PrivateRepoConfigurable.getConfig()
        var configComplete = true

        val currentConfig = config
        if (currentConfig == null || !currentConfig.enabled) {
            updateStatus("私仓上传功能未启用，仅显示依赖列表")
            configComplete = false
        } else if (!currentConfig.isValid()) {
            updateStatus("私仓配置不完整，仅显示依赖列表")
            configComplete = false
        }

        // 如果配置完整，进行完整的分析流程（包括私仓检查）
        if (configComplete && currentConfig != null) {
            // 开始完整的上传流程（包括私仓检查）
            uploadService.executeUploadFlow(
                project,
                currentConfig,
                onAnalysisComplete = { deps ->
                    ApplicationManager.getApplication().invokeLater {
                        updateTableData(deps, "分析完成，共发现 ${deps.size} 个依赖")
                    }
                },
                onCheckComplete = { deps ->
                    ApplicationManager.getApplication().invokeLater {
                        val missingCount = deps.count { it.checkStatus == CheckStatus.MISSING }
                        val existsCount = deps.count { it.checkStatus == CheckStatus.EXISTS }
                        val errorCount = deps.count { it.checkStatus == CheckStatus.ERROR }
                        updateTableData(deps, "检查完成: 缺失=$missingCount, 已存在=$existsCount, 错误=$errorCount")
                    }
                }
            )
        } else {
            // 配置不完整时，仅进行依赖分析（不依赖私仓配置）
            analyzeDependenciesOnly()
        }
    }

    /**
     * 更新表格数据（统一方法）
     */
    private fun updateTableData(deps: List<DependencyInfo>, statusMessage: String) {
        logger.info("更新表格数据，依赖数量: ${deps.size}")
        
        dependencies = deps
        
        // 更新模型数据（这会触发fireTableDataChanged）
        tableModel.setDependencies(deps)
        
        logger.info("表格数据已更新，行数: ${tableModel.rowCount}")
        
        // 更新状态
        updateStatus(statusMessage)
    }

    /**
     * 仅进行依赖分析（不依赖私仓配置）
     */
    private fun analyzeDependenciesOnly() {
        updateStatus("正在分析依赖...")

        ProgressManager.getInstance().run(object : Task.Backgroundable(project, "分析Maven依赖", true) {
            override fun run(indicator: ProgressIndicator) {
                try {
                    // 创建分析器并分析依赖
                    val analyzer = com.maven.privateuploader.analyzer.MavenDependencyAnalyzer(project)
                    val deps = analyzer.analyzeDependencies(indicator)

                    // 在EDT线程中更新UI
                    logger.info("准备更新UI，依赖数量: ${deps.size}")
                    ApplicationManager.getApplication().invokeLater {
                        updateTableData(deps, "分析完成，共发现 ${deps.size} 个依赖（私仓检查已跳过）")
                    }

                } catch (e: Exception) {
                    logger.error("依赖分析时发生错误", e)
                    ApplicationManager.getApplication().invokeLater {
                        updateStatus("依赖分析失败: ${e.message}")
                    }
                }
            }
        })
    }

    /**
     * 选择/取消选择所有依赖
     */
    private fun selectAllDependencies(selected: Boolean) {
        tableModel.setAllSelected(selected)
    }

    /**
     * 重新检查依赖状态
     */
    private fun refreshDependencies() {
        if (dependencies.isEmpty()) {
            Messages.showInfoMessage(
                project,
                "没有发现依赖，无法重新检查",
                "提示"
            )
            return
        }

        val currentConfig = config
        if (currentConfig == null || !currentConfig.isValid()) {
            Messages.showWarningDialog(
                project,
                "私仓配置不完整，无法重新检查依赖状态",
                "配置错误"
            )
            return
        }

        updateStatus("正在重新检查依赖状态...")

        // 使用 Task.Backgroundable 替代裸线程
        ProgressManager.getInstance().run(object : Task.Backgroundable(project, "重新检查依赖状态", true) {
            override fun run(indicator: ProgressIndicator) {
                try {
                    indicator.isIndeterminate = true
                    indicator.text = "正在检查依赖状态..."
                    
                    val client = PrivateRepositoryClient(currentConfig)
                    client.checkDependenciesExist(dependencies.toMutableList(), indicator)
                    
                    ApplicationManager.getApplication().invokeLater {
                        updateTableData(dependencies, "重新检查完成")
                    }
                } catch (e: Exception) {
                    logger.error("重新检查依赖状态时发生错误", e)
                    ApplicationManager.getApplication().invokeLater {
                        updateStatus("重新检查失败: ${e.message}")
                        Messages.showErrorDialog(
                            project,
                            "重新检查依赖状态时发生错误: ${e.message}",
                            "检查失败"
                        )
                    }
                }
            }
        })
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
     */
    private fun reinitializeAfterConfigChange() {
        val currentConfig = config
        if (currentConfig == null || !currentConfig.enabled || !currentConfig.isValid()) {
            // 配置无效时，如果已有依赖数据，仅重新检查状态
            if (dependencies.isNotEmpty()) {
                // 如果之前有依赖数据，重新分析但不检查私仓
                analyzeDependenciesOnly()
            } else {
                // 如果没有依赖数据，重新初始化
                initializeDependencies()
            }
        } else {
            // 配置有效时，重新执行完整的分析流程（包括私仓检查）
            if (dependencies.isEmpty()) {
                // 如果没有依赖数据，重新初始化
                initializeDependencies()
            } else {
                // 如果有依赖数据，重新检查状态
                refreshDependencies()
            }
        }
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
            Messages.showWarningDialog(
                project,
                "私仓配置不完整，无法上传依赖",
                "配置错误"
            )
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

        // 显示进度对话框
        val progressDialog = UploadProgressDialog(this, selectedDependencies)
        progressDialog.show()

        uploadService.uploadSelectedDependencies(
            project,
            currentConfig,
            dependencies,
            selectedDependencies,
            onProgress = { current, currentCount, totalCount ->
                progressDialog.updateProgress(current, currentCount, totalCount)
            },
            onComplete = { summary ->
                SwingUtilities.invokeLater {
                    progressDialog.closeDialog(summary)
                    if (summary.hasFailures()) {
                        showUploadResult(summary, true)
                    } else {
                        showUploadResult(summary, false)
                        // 重新检查依赖状态
                        refreshDependencies()
                    }
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
     * 更新状态栏
     */
    private fun updateStatus(message: String) {
        statusLabel.text = message
    }
}