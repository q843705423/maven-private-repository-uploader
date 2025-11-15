package com.maven.privateuploader.ui

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTable
import com.intellij.util.ui.FormBuilder
import com.intellij.util.ui.JBUI
import com.maven.privateuploader.client.PrivateRepositoryClient
import com.maven.privateuploader.config.PrivateRepoConfigurable
import com.maven.privateuploader.model.CheckStatus
import com.maven.privateuploader.model.DependencyInfo
import com.maven.privateuploader.model.RepositoryConfig
import com.maven.privateuploader.service.DependencyUploadService
import com.maven.privateuploader.ui.table.DependencyTableModel
import java.awt.*
import java.awt.event.ActionEvent
import javax.swing.*

/**
 * 依赖上传主对话框
 * 显示依赖列表，提供预检查和上传功能
 */
class DependencyUploadDialog(private val project: Project) : DialogWrapper(project) {

    private val logger = thisLogger()
    private val uploadService = ApplicationManager.getApplication().getService(DependencyUploadService::class.java)

    private var dependencyTable: JBTable? = null
    private var tableModel: DependencyTableModel? = null
    private var statusLabel: JBLabel? = null
    private var projectInfoLabel: JBLabel? = null
    private var checkAllButton: JButton? = null
    private var uncheckAllButton: JButton? = null
    private var refreshButton: JButton? = null
    private var configButton: JButton? = null
    private var uploadButton: JButton? = null

    private var dependencies: List<DependencyInfo> = emptyList()
    private var config: RepositoryConfig? = null

    init {
        title = "Maven依赖上传到私仓"
        isModal = true
        setOKButtonText("关闭")
        init()
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
        val tableScrollPane = JBScrollPane(dependencyTable)

        // 状态栏
        createStatusBar()

        // 主面板布局
        val mainPanel = JPanel(BorderLayout(0, JBUI.scale(5)))
        mainPanel.border = JBUI.Borders.empty(10)

        mainPanel.add(projectInfoPanel, BorderLayout.NORTH)
        mainPanel.add(toolbarPanel, BorderLayout.NORTH)

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
        projectInfoLabel!!.border = JBUI.Borders.emptyBottom(10)

        val panel = JPanel(BorderLayout())
        panel.add(projectInfoLabel!!, BorderLayout.CENTER)
        panel.border = JBUI.Borders.compound(
            JBUI.Borders.line(JBUI.CurrentTheme.ToolWindow.borderColor()),
            JBUI.Borders.empty(10)
        )

        return panel
    }

    private fun createToolbarPanel(): JPanel {
        checkAllButton = JButton("全选")
        checkAllButton!!.addActionListener { selectAllDependencies(true) }

        uncheckAllButton = JButton("全不选")
        uncheckAllButton!!.addActionListener { selectAllDependencies(false) }

        refreshButton = JButton("重新检查")
        refreshButton!!.addActionListener { refreshDependencies() }

        configButton = JButton("配置")
        configButton!!.addActionListener { openSettings() }

        uploadButton = JButton("上传选中依赖")
        uploadButton!!.addActionListener { uploadSelectedDependencies() }

        val toolbar = JToolBar(SwingConstants.HORIZONTAL)
        toolbar.isFloatable = false
        toolbar.border = JBUI.Borders.empty(5, 0, 5, 0)

        toolbar.add(checkAllButton)
        toolbar.add(uncheckAllButton)
        toolbar.addSeparator()
        toolbar.add(refreshButton)
        toolbar.addSeparator()
        toolbar.add(configButton)
        toolbar.add(Box.createHorizontalGlue())
        toolbar.add(uploadButton)

        return toolbar
    }

    private fun createDependencyTable() {
        tableModel = DependencyTableModel()
        dependencyTable = JBTable(tableModel)

        // 设置表格属性
        dependencyTable!!.autoResizeMode = JTable.AUTO_RESIZE_ALL_COLUMNS
        dependencyTable!!.selectionMode = ListSelectionModel.MULTIPLE_INTERVAL_SELECTION
        dependencyTable!!.rowHeight = JBUI.scale(24)

        // 设置列宽
        val columnModel = dependencyTable!!.columnModel
        columnModel.getColumn(0).preferredWidth = 200 // GroupId
        columnModel.getColumn(1).preferredWidth = 150 // ArtifactId
        columnModel.getColumn(2).preferredWidth = 100 // Version
        columnModel.getColumn(3).preferredWidth = 80  // Packaging
        columnModel.getColumn(4).preferredWidth = 120 // 状态
        columnModel.getColumn(5).preferredWidth = 300 // 路径
    }

    private fun createStatusBar() {
        statusLabel = JBLabel("就绪")
        statusLabel!!.border = JBUI.Borders.empty(5, 0, 0, 0)
    }

    override fun getPreferredFocusedComponent(): JComponent {
        return dependencyTable!!
    }

    override fun show() {
        super.show()
        // 对话框显示后立即开始分析
        SwingUtilities.invokeLater {
            initializeDependencies()
        }
    }

    /**
     * 初始化依赖分析
     */
    private fun initializeDependencies() {
        if (!uploadService.isMavenProject()) {
            JOptionPane.showMessageDialog(
                this.contentPane,
                "当前项目不是Maven项目，无法使用此功能",
                "错误",
                JOptionPane.ERROR_MESSAGE
            )
            return
        }

        // 更新项目信息
        val projectInfo = uploadService.getMavenProjectInfo()
        projectInfoLabel!!.text = projectInfo

        // 检查配置
        config = PrivateRepoConfigurable.getConfig()
        if (!config!!.enabled) {
            JOptionPane.showMessageDialog(
                this.contentPane,
                "私仓上传功能未启用，请先在设置中配置私仓信息",
                "提示",
                JOptionPane.INFORMATION_MESSAGE
            )
            openSettings()
            return
        }

        if (!config!!.isValid()) {
            JOptionPane.showMessageDialog(
                this.contentPane,
                "私仓配置不完整，请检查设置中的配置信息",
                "错误",
                JOptionPane.ERROR_MESSAGE
            )
            openSettings()
            return
        }

        // 开始依赖分析流程
        uploadService.executeUploadFlow(
            config!!,
            onAnalysisComplete = { deps ->
                ApplicationManager.getApplication().invokeLater {
                    dependencies = deps
                    tableModel!!.setDependencies(deps)
                    updateStatus("分析完成，共发现 ${deps.size} 个依赖")
                }
            },
            onCheckComplete = { deps ->
                ApplicationManager.getApplication().invokeLater {
                    dependencies = deps
                    tableModel!!.setDependencies(deps)
                    val missingCount = deps.count { it.checkStatus == CheckStatus.MISSING }
                    val existsCount = deps.count { it.checkStatus == CheckStatus.EXISTS }
                    val errorCount = deps.count { it.checkStatus == CheckStatus.ERROR }
                    updateStatus("检查完成: 缺失=$missingCount, 已存在=$existsCount, 错误=$errorCount")
                }
            }
        )
    }

    /**
     * 选择/取消选择所有依赖
     */
    private fun selectAllDependencies(selected: Boolean) {
        tableModel?.setAllSelected(selected)
    }

    /**
     * 重新检查依赖状态
     */
    private fun refreshDependencies() {
        if (dependencies.isEmpty()) {
            JOptionPane.showMessageDialog(
                this.contentPane,
                "没有发现依赖，无法重新检查",
                "提示",
                JOptionPane.INFORMATION_MESSAGE
            )
            return
        }

        updateStatus("正在重新检查依赖状态...")

        val client = PrivateRepositoryClient(config!!)
        Thread {
            client.checkDependenciesExist(dependencies.toMutableList(), null)
            SwingUtilities.invokeLater {
                tableModel!!.setDependencies(dependencies)
                updateStatus("重新检查完成")
            }
        }.start()
    }

    /**
     * 打开设置页面
     */
    private fun openSettings() {
        com.intellij.openapi.options.ShowSettingsUtil.getInstance().showSettingsDialog(project, "Maven私仓上传")
        // 重新加载配置
        config = PrivateRepoConfigurable.getConfig()
    }

    /**
     * 上传选中的依赖
     */
    private fun uploadSelectedDependencies() {
        val selectedDependencies = dependencies.filter { it.selected }

        if (selectedDependencies.isEmpty()) {
            JOptionPane.showMessageDialog(
                this.contentPane,
                "请先选择要上传的依赖",
                "提示",
                JOptionPane.INFORMATION_MESSAGE
            )
            return
        }

        // 确认上传
        val result = JOptionPane.showConfirmDialog(
            this.contentPane,
            "确定要上传选中的 ${selectedDependencies.size} 个依赖到私仓吗？",
            "确认上传",
            JOptionPane.YES_NO_OPTION,
            JOptionPane.QUESTION_MESSAGE
        )

        if (result != JOptionPane.YES_OPTION) {
            return
        }

        // 显示进度对话框
        val progressDialog = UploadProgressDialog(this, selectedDependencies)
        progressDialog.show()

        uploadService.uploadSelectedDependencies(
            config!!,
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
        val messageType = if (hasFailures) JOptionPane.WARNING_MESSAGE else JOptionPane.INFORMATION_MESSAGE
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

        JOptionPane.showMessageDialog(
            this.contentPane,
            message.toString(),
            title,
            messageType
        )
    }

    /**
     * 更新状态栏
     */
    private fun updateStatus(message: String) {
        statusLabel!!.text = message
    }
}