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
import com.maven.privateuploader.model.CheckStatus
import com.maven.privateuploader.model.DependencyInfo
import com.maven.privateuploader.model.RepositoryConfig
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * 依赖上传服务
 * 负责协调依赖分析、预检查和上传的整个流程
 */
@Service(Service.Level.PROJECT)
class DependencyUploadService {

    private val logger = thisLogger()
    
    // 独立的线程池：HEAD检查使用10个线程
    private val checkExecutor: ExecutorService = Executors.newFixedThreadPool(10)
    
    // 独立的线程池：上传使用3个线程
    private val uploadExecutor: ExecutorService = Executors.newFixedThreadPool(3)

    /**
     * 执行完整的依赖分析、预检查和上传流程
     *
     * @param project 当前项目
     * @param config 私仓配置
     * @param onAnalysisComplete 分析完成回调
     * @param onCheckComplete 检查完成回调
     * @param onUploadComplete 上传完成回调
     */
    fun executeUploadFlow(
        project: Project,
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
     * 上传选中的依赖（使用独立线程池）
     *
     * @param project 当前项目
     * @param config 私仓配置
     * @param dependencies 依赖列表（从tableModel读取）
     * @param selectedDependencies 要上传的依赖
     * @param onProgress 进度回调
     * @param onComplete 完成回调
     */
    fun uploadSelectedDependencies(
        project: Project,
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
                val failedUploads = java.util.Collections.synchronizedList(mutableListOf<DependencyInfo>())
                val completedCount = java.util.concurrent.atomic.AtomicInteger(0)
                val successCount = java.util.concurrent.atomic.AtomicInteger(0)
                val failureCount = java.util.concurrent.atomic.AtomicInteger(0)

                // 使用上传线程池并行上传
                val futures = selectedDependencies.mapIndexed { index, dependency ->
                    uploadExecutor.submit(java.lang.Runnable {
                        try {
                            // 注意：ProgressIndicator在多线程环境下需要同步访问
                            synchronized(indicator) {
                                indicator.text2 = "正在上传: ${dependency.getGAV()}"
                            }
                            
                            val result = client.uploadDependency(dependency, null) // 不传递indicator，避免线程安全问题
                            
                            val current = completedCount.incrementAndGet()
                            synchronized(indicator) {
                                indicator.fraction = current.toDouble() / selectedDependencies.size.toDouble()
                                indicator.text2 = "正在上传: ${dependency.getGAV()} ($current/${selectedDependencies.size})"
                            }
                            
                            onProgress(dependency.getGAV(), current, selectedDependencies.size)

                            if (result.success) {
                                successCount.incrementAndGet()
                                logger.info("依赖上传成功: ${dependency.getGAV()}")
                                // 清除错误信息
                                dependency.errorMessage = ""
                            } else {
                                failureCount.incrementAndGet()
                                failedUploads.add(dependency)
                                // 写回错误信息
                                dependency.errorMessage = result.message
                                dependency.checkStatus = CheckStatus.ERROR
                                logger.error("依赖上传失败: ${dependency.getGAV()} - ${result.message}")
                            }
                        } catch (e: Exception) {
                            val current = completedCount.incrementAndGet()
                            failureCount.incrementAndGet()
                            failedUploads.add(dependency)
                            // 写回错误信息
                            dependency.errorMessage = e.message ?: "上传异常: ${e.javaClass.simpleName}"
                            dependency.checkStatus = CheckStatus.ERROR
                            logger.error("上传依赖时发生异常: ${dependency.getGAV()}", e)
                        }
                    })
                }

                // 等待所有任务完成
                futures.forEach { it.get() }

                indicator.fraction = 1.0
                indicator.text2 = "上传完成"

                // 更新汇总信息
                uploadSummary.successCount = successCount.get()
                uploadSummary.failureCount = failureCount.get()
                uploadSummary.failedUploads = failedUploads.toList()

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
    fun isMavenProject(project: Project): Boolean {
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
    fun getMavenProjectInfo(project: Project): String {
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