package com.maven.privateuploader.service

import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.maven.privateuploader.analyzer.MavenDependencyAnalyzer
import com.maven.privateuploader.client.PrivateRepositoryClient
import com.maven.privateuploader.client.UploadResult
import com.maven.privateuploader.model.DependencyInfo
import com.maven.privateuploader.model.RepositoryConfig

/**
 * 依赖上传服务
 * 负责协调依赖分析、预检查和上传的整个流程
 */
@Service(Service.Level.PROJECT)
class DependencyUploadService(private val project: Project) {

    private val logger = thisLogger()

    /**
     * 执行完整的依赖分析、预检查和上传流程
     *
     * @param config 私仓配置
     * @param onAnalysisComplete 分析完成回调
     * @param onCheckComplete 检查完成回调
     * @param onUploadComplete 上传完成回调
     */
    fun executeUploadFlow(
        config: RepositoryConfig,
        onAnalysisComplete: (List<DependencyInfo>) -> Unit = {},
        onCheckComplete: (List<DependencyInfo>) -> Unit = {},
        onUploadComplete: (UploadSummary) -> Unit = {}
    ) {
        ProgressManager.getInstance().run(object : Task.Backgroundable(project, "分析Maven依赖", true) {
            override fun run(indicator: ProgressIndicator) {
                try {
                    // 1. 分析Maven依赖
                    indicator.text = "正在分析Maven依赖..."
                    val analyzer = MavenDependencyAnalyzer(project)

                    if (!analyzer.isMavenProject()) {
                        logger.error("当前项目不是Maven项目")
                        return
                    }

                    val dependencies = analyzer.analyzeDependencies(indicator)
                    logger.info("分析完成，共发现 ${dependencies.size} 个依赖")

                    // 在EDT线程中调用回调
                    com.intellij.openapi.application.ApplicationManager.getApplication().invokeLater {
                        onAnalysisComplete(dependencies)
                    }

                    // 2. 预检查依赖在私仓中的状态
                    indicator.text = "正在检查私仓中的依赖状态..."
                    val client = PrivateRepositoryClient(config)
                    client.checkDependenciesExist(dependencies, indicator)

                    // 在EDT线程中调用回调
                    com.intellij.openapi.application.ApplicationManager.getApplication().invokeLater {
                        onCheckComplete(dependencies)
                    }

                } catch (e: Exception) {
                    logger.error("执行依赖分析流程时发生错误", e)
                }
            }
        })
    }

    /**
     * 上传选中的依赖
     *
     * @param config 私仓配置
     * @param dependencies 依赖列表
     * @param selectedDependencies 要上传的依赖
     * @param onProgress 进度回调
     * @param onComplete 完成回调
     */
    fun uploadSelectedDependencies(
        config: RepositoryConfig,
        dependencies: List<DependencyInfo>,
        selectedDependencies: List<DependencyInfo>,
        onProgress: (String, Int, Int) -> Unit = { _, _, _ -> },
        onComplete: (UploadSummary) -> Unit = {}
    ) {
        if (selectedDependencies.isEmpty()) {
            logger.info("没有选中任何依赖需要上传")
            onComplete(UploadSummary(0, 0, 0))
            return
        }

        ProgressManager.getInstance().run(object : Task.Backgroundable(project, "上传Maven依赖", true) {
            override fun run(indicator: ProgressIndicator) {
                val client = PrivateRepositoryClient(config)
                val uploadSummary = UploadSummary(
                    totalCount = selectedDependencies.size,
                    successCount = 0,
                    failureCount = 0
                )

                indicator.isIndeterminate = false
                val failedUploads = mutableListOf<DependencyInfo>()

                selectedDependencies.forEachIndexed { index, dependency ->
                    indicator.fraction = index.toDouble() / selectedDependencies.size.toDouble()
                    indicator.text = "上传Maven依赖"
                    indicator.text2 = "正在上传: ${dependency.getGAV()}"

                    onProgress(dependency.getGAV(), index + 1, selectedDependencies.size)

                    try {
                        val result = client.uploadDependency(dependency, indicator)
                        if (result.success) {
                            uploadSummary.successCount++
                            logger.info("依赖上传成功: ${dependency.getGAV()}")
                        } else {
                            uploadSummary.failureCount++
                            failedUploads.add(dependency)
                            logger.error("依赖上传失败: ${dependency.getGAV()} - ${result.message}")
                        }
                    } catch (e: Exception) {
                        uploadSummary.failureCount++
                        failedUploads.add(dependency)
                        logger.error("上传依赖时发生异常: ${dependency.getGAV()}", e)
                    }
                }

                indicator.fraction = 1.0
                indicator.text2 = "上传完成"

                uploadSummary.failedUploads = failedUploads

                // 在EDT线程中调用完成回调
                com.intellij.openapi.application.ApplicationManager.getApplication().invokeLater {
                    onComplete(uploadSummary)
                }

                logger.info("依赖上传完成: 总数=${uploadSummary.totalCount}, 成功=${uploadSummary.successCount}, 失败=${uploadSummary.failureCount}")
            }
        })
    }

    /**
     * 检查项目是否为Maven项目
     */
    fun isMavenProject(): Boolean {
        return try {
            val analyzer = MavenDependencyAnalyzer(project)
            analyzer.isMavenProject()
        } catch (e: Exception) {
            logger.error("检查项目类型时发生错误", e)
            false
        }
    }

    /**
     * 获取Maven项目信息
     */
    fun getMavenProjectInfo(): String {
        return try {
            val analyzer = MavenDependencyAnalyzer(project)
            if (!analyzer.isMavenProject()) {
                return "当前项目不是Maven项目"
            }

            val modules = analyzer.getMavenModules()
            val moduleNames = modules.joinToString(", ") { it.displayName }
            "Maven项目 (${modules.size} 个模块): $moduleNames"
        } catch (e: Exception) {
            logger.error("获取Maven项目信息时发生错误", e)
            "获取项目信息失败: ${e.message}"
        }
    }
}

/**
 * 上传摘要信息
 */
data class UploadSummary(
    val totalCount: Int,
    var successCount: Int,
    var failureCount: Int,
    var failedUploads: List<DependencyInfo> = emptyList()
) {
    /**
     * 是否有失败的上传
     */
    fun hasFailures(): Boolean = failureCount > 0

    /**
     * 获取成功率
     */
    fun getSuccessRate(): Double {
        return if (totalCount > 0) successCount.toDouble() / totalCount.toDouble() else 0.0
    }
}