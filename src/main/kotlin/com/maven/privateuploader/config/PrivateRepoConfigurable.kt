package com.maven.privateuploader.config

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.options.Configurable
import com.intellij.openapi.options.ConfigurationException
import com.intellij.openapi.util.NlsContexts
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBPasswordField
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.FormBuilder
import com.intellij.util.ui.JBUI
import com.maven.privateuploader.model.RepositoryConfig
import com.maven.privateuploader.state.PrivateRepoSettings
import java.awt.BorderLayout
import javax.swing.*

/**
 * 私仓配置页面
 * 在IDEA的Settings中显示配置界面
 */
class PrivateRepoConfigurable : Configurable {

    private var repositoryUrlField = JBTextField()
    private var usernameField = JBTextField()
    private var passwordField = JBPasswordField()
    private var repositoryIdField = JBTextField()
    private var enabledCheckbox = JBCheckBox("启用私仓上传功能")

    private var mainPanel: JPanel? = null

    override fun getDisplayName(): @NlsContexts.ConfigurableName String {
        return "Maven私仓上传"
    }

    override fun createComponent(): JComponent? {
        if (mainPanel == null) {
            mainPanel = createMainPanel()
        }
        return mainPanel
    }

    private fun createMainPanel(): JPanel {
        val formBuilder = FormBuilder.createFormBuilder()
            .addLabeledComponent(JBLabel("私仓URL:"), repositoryUrlField, 1, false)
            .addLabeledComponent(JBLabel("用户名:"), usernameField, 1, false)
            .addLabeledComponent(JBLabel("密码:"), passwordField, 1, false)
            .addLabeledComponent(JBLabel("仓库ID:"), repositoryIdField, 1, false)
            .addComponentFillVertically(enabledCheckbox, 0)

        val configPanel = formBuilder.panel
        configPanel.border = JBUI.Borders.empty(8)

        // 添加说明面板
        val infoPanel = createInfoPanel()

        val main = JPanel(BorderLayout())
        main.add(infoPanel, BorderLayout.NORTH)
        main.add(configPanel, BorderLayout.CENTER)

        return main
    }

    private fun createInfoPanel(): JPanel {
        val infoText = """
            <html>
            <body>
                <p><b>Maven私仓上传配置</b></p>
                <p>请配置Maven私仓的连接信息：</p>
                <ul>
                    <li><b>私仓URL</b>: Maven私仓的完整URL地址（如：http://nexus.company.com/repository/maven-releases/）</li>
                    <li><b>用户名/密码</b>: 用于认证的凭证</li>
                    <li><b>仓库ID</b>: 可选，用于某些私仓系统（如Nexus的特定仓库ID）</li>
                </ul>
            </body>
            </html>
        """.trimIndent()

        val infoLabel = JBLabel(infoText)
        infoLabel.border = JBUI.Borders.empty(5, 5, 15, 5)

        val panel = JPanel(BorderLayout())
        panel.add(infoLabel, BorderLayout.CENTER)
        panel.border = JBUI.Borders.compound(
            JBUI.Borders.line(JBUI.CurrentTheme.ToolWindow.borderColor()),
            JBUI.Borders.empty(10)
        )

        return panel
    }

    override fun isModified(): Boolean {
        val settings = PrivateRepoSettings.getInstance()
        val currentConfig = settings.config

        return repositoryUrlField.text != currentConfig.repositoryUrl ||
                usernameField.text != currentConfig.username ||
                String(passwordField.password) != currentConfig.password ||
                repositoryIdField.text != currentConfig.repositoryId ||
                enabledCheckbox.isSelected != currentConfig.enabled
    }

    @Throws(ConfigurationException::class)
    override fun apply() {
        val settings = PrivateRepoSettings.getInstance()

        val newConfig = RepositoryConfig(
            repositoryUrl = repositoryUrlField.text.trim(),
            username = usernameField.text.trim(),
            password = String(passwordField.password),
            repositoryId = repositoryIdField.text.trim(),
            enabled = enabledCheckbox.isSelected
        )

        // 验证配置
        if (newConfig.enabled && !newConfig.isValid()) {
            throw ConfigurationException("启用功能时，私仓URL、用户名和密码不能为空")
        }

        // 验证URL格式
        if (newConfig.repositoryUrl.isNotBlank() && !isValidUrl(newConfig.repositoryUrl)) {
            throw ConfigurationException("私仓URL格式无效，请输入有效的HTTP/HTTPS URL")
        }

        settings.config = newConfig
        settings.loadState(settings.state) // 保存状态
    }

    override fun reset() {
        val settings = PrivateRepoSettings.getInstance()
        val config = settings.config

        repositoryUrlField.text = config.repositoryUrl
        usernameField.text = config.username
        passwordField.text = config.password
        repositoryIdField.text = config.repositoryId
        enabledCheckbox.isSelected = config.enabled
    }

    override fun disposeUIResources() {
        mainPanel = null
    }

    /**
     * 验证URL格式
     */
    private fun isValidUrl(url: String): Boolean {
        return try {
            val trimmedUrl = url.trim()
            trimmedUrl.startsWith("http://") || trimmedUrl.startsWith("https://")
        } catch (e: Exception) {
            false
        }
    }

    companion object {
        /**
         * 获取配置实例
         */
        fun getConfig(): RepositoryConfig {
            return ApplicationManager.getApplication().getService(PrivateRepoSettings::class.java).config
        }

        /**
         * 检查配置是否有效
         */
        fun isConfigValid(): Boolean {
            return getConfig().isValid()
        }
    }
}