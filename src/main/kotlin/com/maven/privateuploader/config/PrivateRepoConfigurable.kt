package com.maven.privateuploader.config

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.thisLogger
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
import okhttp3.*
import java.awt.BorderLayout
import java.awt.Color
import java.util.Base64
import java.util.concurrent.TimeUnit
import javax.swing.*

/**
 * 私仓配置页面
 * 在IDEA的Settings中显示配置界面
 */
class PrivateRepoConfigurable : Configurable {

    private val logger = thisLogger()

    private var repositoryUrlField = JBTextField()
    private var usernameField = JBTextField()
    private var passwordField = JBPasswordField()
    private var repositoryIdField = JBTextField()
    private var enabledCheckbox = JBCheckBox("启用私仓上传功能")
    private var testConnectionButton = JButton("测试连接")
    private var testResultLabel = JBLabel("")

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

        // 创建测试连接面板
        val testPanel = createTestConnectionPanel()

        // 添加说明面板
        val infoPanel = createInfoPanel()

        val main = JPanel(BorderLayout())
        main.add(infoPanel, BorderLayout.NORTH)
        main.add(configPanel, BorderLayout.CENTER)
        main.add(testPanel, BorderLayout.SOUTH)

        return main
    }

    /**
     * 创建测试连接面板
     */
    private fun createTestConnectionPanel(): JPanel {
        testConnectionButton.addActionListener { 
            logger.info("【测试连接按钮被点击】")
            System.out.println("【测试连接按钮被点击】")
            testConnection() 
        }
        testResultLabel.text = ""
        testResultLabel.border = JBUI.Borders.empty(5, 0, 5, 0)

        val panel = JPanel()
        panel.layout = BoxLayout(panel, BoxLayout.X_AXIS)
        panel.border = JBUI.Borders.empty(10, 0, 0, 0)
        panel.add(testConnectionButton)
        panel.add(Box.createHorizontalStrut(10))
        panel.add(testResultLabel)
        panel.add(Box.createHorizontalGlue())

        return panel
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
        panel.border = javax.swing.BorderFactory.createCompoundBorder(
            javax.swing.BorderFactory.createLineBorder(java.awt.Color.GRAY),
            javax.swing.BorderFactory.createEmptyBorder(10, 10, 10, 10)
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
        
        // 重置测试结果
        testResultLabel.text = ""
        testResultLabel.foreground = null
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

    /**
     * 测试连接
     */
    private fun testConnection() {
        logger.info("【testConnection方法开始执行】")
        System.out.println("【testConnection方法开始执行】")
        
        // 获取当前配置
        val testConfig = RepositoryConfig(
            repositoryUrl = repositoryUrlField.text.trim(),
            username = usernameField.text.trim(),
            password = String(passwordField.password),
            repositoryId = repositoryIdField.text.trim(),
            enabled = enabledCheckbox.isSelected
        )
        
        logger.info("【配置获取完成】URL: ${testConfig.repositoryUrl}, 用户名: ${testConfig.username}, 仓库ID: ${testConfig.repositoryId}")
        System.out.println("【配置获取完成】URL: ${testConfig.repositoryUrl}")

        // 验证基本配置
        logger.info("【开始验证配置】")
        System.out.println("【开始验证配置】")
        
        if (testConfig.repositoryUrl.isBlank()) {
            logger.warn("【验证失败】私仓URL为空")
            System.out.println("【验证失败】私仓URL为空")
            showTestResult("私仓URL不能为空", false)
            return
        }

        if (!isValidUrl(testConfig.repositoryUrl)) {
            logger.warn("【验证失败】私仓URL格式无效: ${testConfig.repositoryUrl}")
            System.out.println("【验证失败】私仓URL格式无效")
            showTestResult("私仓URL格式无效，请输入有效的HTTP/HTTPS URL", false)
            return
        }

        if (testConfig.username.isBlank() || testConfig.password.isBlank()) {
            logger.warn("【验证失败】用户名或密码为空")
            System.out.println("【验证失败】用户名或密码为空")
            showTestResult("用户名和密码不能为空", false)
            return
        }

        val testUrl = testConfig.getDeployUrl()
        logger.info("【验证通过】开始测试连接，URL: $testUrl")
        System.out.println("【验证通过】开始测试连接，URL: $testUrl")

        // 显示测试中状态
        logger.info("【更新UI状态】显示测试中...")
        System.out.println("【更新UI状态】显示测试中...")
        testConnectionButton.isEnabled = false
        testResultLabel.text = "正在连接中... (测试URL: $testUrl)"
        testResultLabel.foreground = Color.GRAY

        // 在后台线程执行测试
        logger.info("【提交后台任务】executeOnPooledThread")
        System.out.println("【提交后台任务】executeOnPooledThread")
        ApplicationManager.getApplication().executeOnPooledThread {
            logger.info("【后台线程开始执行】")
            System.out.println("【后台线程开始执行】")
            var result: TestResult? = null
            var exception: Throwable? = null
            
            try {
                logger.info("【后台线程】开始执行连接测试")
                System.out.println("【后台线程】开始执行连接测试")
                result = performConnectionTest(testConfig)
                logger.info("【后台线程】连接测试完成，结果: ${result.message}, 成功: ${result.success}")
                System.out.println("【后台线程】连接测试完成: ${result.message}")
            } catch (e: Throwable) {
                // 捕获所有异常，包括Error
                exception = e
                logger.error("【后台线程】连接测试时发生异常", e)
                System.out.println("【后台线程】连接测试时发生异常: ${e.javaClass.simpleName} - ${e.message}")
                e.printStackTrace()
                result = TestResult(false, "测试失败: ${e.javaClass.simpleName} - ${e.message ?: "未知错误"}")
            } finally {
                logger.info("【后台线程】进入finally块")
                System.out.println("【后台线程】进入finally块")
                // 确保UI更新，即使发生异常也要恢复按钮状态
                val finalResult = result ?: TestResult(false, "测试失败: 未返回结果")
                val finalMessage = if (exception != null) {
                    "测试失败: ${exception.javaClass.simpleName} - ${exception.message ?: "未知错误"}"
                } else {
                    finalResult.message
                }
                
                logger.info("【后台线程】准备调用invokeLater更新UI")
                System.out.println("【后台线程】准备调用invokeLater更新UI，消息: $finalMessage")
                
                // 尝试多种方式更新UI，确保能执行
                val app = ApplicationManager.getApplication()
                
                // 方法1: 使用默认的invokeLater（不指定ModalityState）
                app.invokeLater({
                    logger.info("【EDT线程】invokeLater回调执行（方法1），更新UI")
                    System.out.println("【EDT线程】invokeLater回调执行（方法1），更新UI")
                    try {
                        logger.info("【EDT线程】更新UI，消息: $finalMessage")
                        System.out.println("【EDT线程】更新UI，消息: $finalMessage")
                        showTestResult(finalMessage, finalResult.success)
                        testConnectionButton.isEnabled = true
                        logger.info("【EDT线程】UI更新完成")
                        System.out.println("【EDT线程】UI更新完成")
                    } catch (e: Throwable) {
                        logger.error("【EDT线程】更新UI时发生异常", e)
                        System.out.println("【EDT线程】更新UI时发生异常: ${e.message}")
                        e.printStackTrace()
                        // 即使UI更新失败，也要恢复按钮状态
                        try {
                            testConnectionButton.isEnabled = true
                            testResultLabel.text = "UI更新失败: ${e.message}"
                            testResultLabel.foreground = Color.RED
                        } catch (e2: Throwable) {
                            logger.error("【EDT线程】恢复按钮状态时发生异常", e2)
                            System.out.println("【EDT线程】恢复按钮状态时发生异常: ${e2.message}")
                            e2.printStackTrace()
                        }
                    }
                })
                
                // 备用方法: 如果上面的不执行，尝试使用SwingUtilities
                java.awt.EventQueue.invokeLater {
                    logger.info("【EDT线程】SwingUtilities.invokeLater回调执行（备用方法）")
                    System.out.println("【EDT线程】SwingUtilities.invokeLater回调执行（备用方法）")
                    try {
                        // 检查UI是否已经更新，如果没有则更新
                        if (!testConnectionButton.isEnabled) {
                            logger.info("【EDT线程】检测到按钮仍被禁用，使用备用方法更新UI")
                            System.out.println("【EDT线程】检测到按钮仍被禁用，使用备用方法更新UI")
                            showTestResult(finalMessage, finalResult.success)
                            testConnectionButton.isEnabled = true
                        }
                    } catch (e: Throwable) {
                        logger.error("【EDT线程】备用方法更新UI时发生异常", e)
                        System.out.println("【EDT线程】备用方法更新UI时发生异常: ${e.message}")
                        e.printStackTrace()
                    }
                }
                
                logger.info("【后台线程】invokeLater调用完成")
                System.out.println("【后台线程】invokeLater调用完成")
            }
        }
    }

    /**
     * 执行连接测试
     */
    private fun performConnectionTest(config: RepositoryConfig): TestResult {
        val testUrl = config.getDeployUrl()
        logger.info("执行连接测试，URL: $testUrl")

        // 验证URL格式
        try {
            // 尝试解析URL，如果URL格式错误会抛出异常
            val uri = java.net.URI(testUrl)
            val url = uri.toURL()
            logger.info("URL解析成功: $url")
        } catch (e: java.net.URISyntaxException) {
            logger.warn("URL格式错误", e)
            return TestResult(false, "URL格式错误：${e.message}")
        } catch (e: java.net.MalformedURLException) {
            logger.warn("URL格式错误", e)
            return TestResult(false, "URL格式错误：${e.message}")
        } catch (e: Throwable) {
            logger.error("URL解析时发生未知异常", e)
            return TestResult(false, "URL解析失败：${e.javaClass.simpleName} - ${e.message}")
        }

        val httpClient = createHttpClient(config)
        logger.info("HTTP客户端创建成功，超时设置: 连接5秒，读取5秒，写入5秒")

        return try {
            // 使用GET请求而不是HEAD，因为某些Maven仓库不支持HEAD请求
            // 但只读取响应头，不读取响应体，以提高效率
            val request = Request.Builder()
                .url(testUrl)
                .get()
                .build()

            logger.info("发送HTTP GET请求到: $testUrl")
            val startTime = System.currentTimeMillis()
            
            httpClient.newCall(request).execute().use { response ->
                val elapsedTime = System.currentTimeMillis() - startTime
                logger.info("收到HTTP响应，状态码: ${response.code}, 耗时: ${elapsedTime}ms")
                
                // 关闭响应体，避免读取大量数据
                try {
                    response.body?.close()
                } catch (e: Exception) {
                    logger.warn("关闭响应体时发生异常", e)
                }
                
                val statusCode = response.code

                when {
                    statusCode in 200..299 -> {
                        TestResult(true, "连接正常 (HTTP $statusCode, 耗时: ${elapsedTime}ms)")
                    }
                    statusCode == 401 -> {
                        TestResult(false, "认证失败：用户名或密码错误（HTTP 401）")
                    }
                    statusCode == 403 -> {
                        TestResult(false, "权限不足：当前用户没有访问权限（HTTP 403）")
                    }
                    statusCode == 404 -> {
                        TestResult(false, "地址不存在：私仓URL无法访问（HTTP 404）")
                    }
                    else -> {
                        TestResult(false, "连接失败：HTTP $statusCode - ${response.message}")
                    }
                }
            }
        } catch (e: java.net.SocketTimeoutException) {
            logger.warn("连接超时", e)
            TestResult(false, "连接超时：无法在指定时间内连接到私仓（已等待5秒）")
        } catch (e: java.io.InterruptedIOException) {
            // OkHttp超时时可能抛出InterruptedIOException
            logger.warn("连接超时（InterruptedIOException）", e)
            TestResult(false, "连接超时：无法在指定时间内连接到私仓（已等待5秒）")
        } catch (e: java.net.UnknownHostException) {
            logger.warn("无法解析主机名", e)
            TestResult(false, "地址不可达：无法解析主机名（${e.message}）")
        } catch (e: java.net.ConnectException) {
            logger.warn("连接被拒绝", e)
            TestResult(false, "连接被拒绝：无法连接到私仓服务器")
        } catch (e: javax.net.ssl.SSLException) {
            logger.warn("SSL连接失败", e)
            TestResult(false, "SSL连接失败：${e.message}")
        } catch (e: java.net.SocketException) {
            logger.warn("网络连接错误", e)
            TestResult(false, "网络连接错误：${e.message}")
        } catch (e: java.io.IOException) {
            logger.error("IO异常", e)
            TestResult(false, "网络IO错误：${e.message ?: e.javaClass.simpleName}")
        } catch (e: Throwable) {
            // 捕获所有其他异常，包括Error
            logger.error("连接测试时发生未知异常", e)
            TestResult(false, "测试失败：${e.javaClass.simpleName} - ${e.message ?: "未知错误"}")
        }
    }

    /**
     * 创建HTTP客户端
     */
    private fun createHttpClient(config: RepositoryConfig): OkHttpClient {
        val builder = OkHttpClient.Builder()
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(5, TimeUnit.SECONDS)
            .writeTimeout(5, TimeUnit.SECONDS)

        // 添加认证
        if (config.username.isNotBlank() && config.password.isNotBlank()) {
            val credential = Base64.getEncoder().encodeToString("${config.username}:${config.password}".toByteArray())
            builder.addInterceptor { chain ->
                val request = chain.request().newBuilder()
                    .addHeader("Authorization", "Basic $credential")
                    .build()
                chain.proceed(request)
            }
        }

        return builder.build()
    }

    /**
     * 显示测试结果
     */
    private fun showTestResult(message: String, success: Boolean) {
        testResultLabel.text = message
        testResultLabel.foreground = if (success) {
            Color(0, 128, 0) // 绿色
        } else {
            Color(200, 0, 0) // 红色
        }
    }

    /**
     * 测试结果数据类
     */
    private data class TestResult(
        val success: Boolean,
        val message: String
    )

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