package com.maven.privateuploader.ui

import com.intellij.openapi.ui.DialogWrapper
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import com.intellij.util.ui.JBUI
import com.maven.privateuploader.model.DependencyInfo
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.Toolkit
import java.awt.datatransfer.StringSelection
import javax.swing.*

/**
 * 错误详情对话框
 * 显示上传失败的详细错误信息，支持复制
 */
class ErrorDetailDialog(
    private val project: com.intellij.openapi.project.Project?,
    private val dependency: DependencyInfo
) : DialogWrapper(project) {

    private lateinit var errorTextArea: JBTextArea
    private lateinit var copyButton: JButton

    init {
        title = "上传错误详情 - ${dependency.getGAV()}"
        isModal = true
        setOKButtonText("关闭")
        init()
    }

    override fun createCenterPanel(): JComponent {
        val panel = JPanel(BorderLayout())
        panel.border = JBUI.Borders.empty(10)
        panel.preferredSize = Dimension(800, 600)

        // 依赖信息标签
        val dependencyLabel = JBLabel("<html><b>依赖:</b> ${dependency.getGAV()}</html>")
        dependencyLabel.border = JBUI.Borders.emptyBottom(10)

        // 错误信息文本区域
        errorTextArea = JBTextArea()
        errorTextArea.isEditable = false
        errorTextArea.font = java.awt.Font(java.awt.Font.MONOSPACED, java.awt.Font.PLAIN, 12)
        errorTextArea.background = java.awt.Color.WHITE
        errorTextArea.lineWrap = true
        errorTextArea.wrapStyleWord = true

        // 构建错误信息内容
        val errorContent = buildErrorContent()
        errorTextArea.text = errorContent
        errorTextArea.caretPosition = 0

        val scrollPane = JBScrollPane(errorTextArea)
        scrollPane.preferredSize = Dimension(780, 500)

        // 按钮面板
        val buttonPanel = JPanel()
        buttonPanel.layout = BoxLayout(buttonPanel, BoxLayout.X_AXIS)
        
        copyButton = JButton("复制错误信息")
        copyButton.addActionListener { copyErrorInfo() }
        
        buttonPanel.add(copyButton)
        buttonPanel.add(Box.createHorizontalGlue())

        // 布局
        val topPanel = JPanel(BorderLayout())
        topPanel.add(dependencyLabel, BorderLayout.NORTH)
        topPanel.add(scrollPane, BorderLayout.CENTER)
        topPanel.add(buttonPanel, BorderLayout.SOUTH)

        panel.add(topPanel, BorderLayout.CENTER)

        return panel
    }

    /**
     * 构建错误信息内容
     */
    private fun buildErrorContent(): String {
        val content = StringBuilder()
        
        content.append("依赖信息:\n")
        content.append("  GroupId: ${dependency.groupId}\n")
        content.append("  ArtifactId: ${dependency.artifactId}\n")
        content.append("  Version: ${dependency.version}\n")
        content.append("  Packaging: ${dependency.packaging}\n")
        content.append("\n")
        
        content.append("错误信息:\n")
        content.append("  ${dependency.errorMessage.ifEmpty { "未知错误" }}\n")
        content.append("\n")
        
        if (dependency.uploadUrl.isNotEmpty()) {
            content.append("调用地址:\n")
            content.append("  ${dependency.uploadUrl}\n")
            content.append("\n")
        }
        
        if (dependency.stackTrace.isNotEmpty()) {
            content.append("堆栈信息:\n")
            content.append(dependency.stackTrace)
            content.append("\n")
        }
        
        return content.toString()
    }

    /**
     * 复制错误信息到剪贴板
     */
    private fun copyErrorInfo() {
        val errorContent = buildErrorContent()
        val selection = StringSelection(errorContent)
        val clipboard = Toolkit.getDefaultToolkit().systemClipboard
        clipboard.setContents(selection, null)
        
        // 显示复制成功提示
        copyButton.text = "已复制！"
        Timer(2000) {
            copyButton.text = "复制错误信息"
        }.start()
    }
}

