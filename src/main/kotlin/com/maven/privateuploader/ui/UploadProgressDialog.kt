package com.maven.privateuploader.ui

import com.intellij.openapi.ui.DialogWrapper
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBProgressBar
import com.intellij.util.ui.FormBuilder
import com.intellij.util.ui.JBUI
import com.maven.privateuploader.model.DependencyInfo
import com.maven.privateuploader.service.UploadSummary
import java.awt.BorderLayout
import javax.swing.*

/**
 * 上传进度对话框
 * 显示上传进度和日志信息
 */
class UploadProgressDialog(
    parent: DialogWrapper,
    private val dependencies: List<DependencyInfo>
) : JDialog(parent.contentPane.window) {

    private var progressBar: JBProgressBar? = null
    private var progressLabel: JBLabel? = null
    private var statusLabel: JBLabel? = null
    private var logTextArea: JTextArea? = null
    private var logScrollPane: JScrollPane? = null

    init {
        title = "上传Maven依赖到私仓"
        isModal = false
        isResizable = true
        size = java.awt.Dimension(600, 400)
        setLocationRelativeTo(parent.contentPane)

        createComponents()
        layoutComponents()
    }

    private fun createComponents() {
        // 进度条
        progressBar = JBProgressBar(0, dependencies.size)
        progressBar!!.stringPainted = true

        // 标签
        progressLabel = JBLabel("准备上传...")
        statusLabel = JBLabel("总数: ${dependencies.size}")

        // 日志区域
        logTextArea = JTextArea()
        logTextArea!!.isEditable = false
        logTextArea!!.background = JBUI.CurrentTheme.Panel.background()
        logTextArea!!.font = JBUI.Fonts.label()
        logScrollPane = JScrollPane(logTextArea)
        logScrollPane!!.preferredSize = java.awt.Dimension(550, 200)
    }

    private fun layoutComponents() {
        // 顶部信息面板
        val topPanel = JPanel(BorderLayout())
        topPanel.border = JBUI.Borders.empty(10)

        val infoPanel = JPanel()
        infoPanel.add(progressLabel)
        infoPanel.add(Box.createHorizontalStrut(20))
        infoPanel.add(statusLabel)

        topPanel.add(infoPanel, BorderLayout.NORTH)
        topPanel.add(progressBar, BorderLayout.CENTER)

        // 主面板
        val mainPanel = JPanel(BorderLayout(0, JBUI.scale(10)))
        mainPanel.border = JBUI.Borders.empty(10)
        mainPanel.add(topPanel, BorderLayout.NORTH)
        mainPanel.add(JBLabel("上传日志:"), BorderLayout.CENTER)
        mainPanel.add(logScrollPane, BorderLayout.SOUTH)

        contentPane.add(mainPanel)
    }

    /**
     * 更新进度
     */
    fun updateProgress(currentDependency: String, currentCount: Int, totalCount: Int) {
        SwingUtilities.invokeLater {
            progressBar!!.value = currentCount
            progressBar!!.string = "$currentCount / $totalCount"
            progressLabel!!.text = "正在上传: $currentDependency"
            statusLabel!!.text = "总数: $totalCount, 当前: $currentCount, 成功: ${currentCount - 1}, 失败: 0"

            // 添加日志
            appendLog("开始上传: $currentDependency")
        }
    }

    /**
     * 添加日志信息
     */
    private fun appendLog(message: String) {
        SwingUtilities.invokeLater {
            val timestamp = java.time.LocalDateTime.now().format(java.time.format.DateTimeFormatter.ofPattern("HH:mm:ss"))
            logTextArea!!.append("[$timestamp] $message\n")
            // 自动滚动到底部
            logTextArea!!.caretPosition = logTextArea!!.document.length
        }
    }

    /**
     * 关闭对话框并显示结果
     */
    fun closeDialog(summary: UploadSummary) {
        SwingUtilities.invokeLater {
            progressBar!!.value = progressBar!!.maximum
            progressBar!!.string = "完成"
            progressLabel!!.text = "上传完成"

            val statusMessage = "总数: ${summary.totalCount}, 成功: ${summary.successCount}, 失败: ${summary.failureCount}"
            statusLabel!!.text = statusMessage

            // 添加完成日志
            appendLog("=" * 50)
            appendLog("上传完成! $statusMessage")

            if (summary.hasFailures()) {
                appendLog("失败的依赖:")
                summary.failedUploads.forEach { dep ->
                    appendLog("  - ${dep.getGAV()}: ${dep.errorMessage}")
                }
            }

            appendLog("=" * 50)

            // 3秒后自动关闭
            Timer(3000) { isVisible = false }.start()
        }
    }

    /**
     * 添加错误日志
     */
    fun addErrorLog(message: String) {
        appendLog("错误: $message")
    }

    /**
     * 添加成功日志
     */
    fun addSuccessLog(message: String) {
        appendLog("成功: $message")
    }

    /**
     * 获取日志内容
     */
    fun getLogContent(): String {
        return logTextArea!!.text
    }

    /**
     * 清空日志
     */
    fun clearLog() {
        SwingUtilities.invokeLater {
            logTextArea!!.text = ""
        }
    }

    private operator fun String.times(i: Int): String {
        return this.repeat(i)
    }
}