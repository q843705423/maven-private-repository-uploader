package com.maven.privateuploader.analyzer

import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.progress.ProgressIndicator
import com.maven.privateuploader.model.DependencyInfo
import org.apache.maven.model.building.ModelBuildingException
import org.jetbrains.idea.maven.project.MavenProject
import org.jetbrains.idea.maven.project.MavenProjectsManager
import java.io.File

/**
 * GAV 解析器组
 * 对应原 Java 项目中的 GavParserGroup 类
 * 适配到 IntelliJ Platform，用于替换 MavenDependencyAnalyzer
 */
class GavParserGroup(private val localRepositoryPath: String) {
    
    private val logger = thisLogger()
    
    /**
     * 获取所有依赖
     * 
     * @param pomPath 主 POM 文件路径
     * @param folderPaths 文件夹路径列表（用于批量解析）
     * @return 依赖信息列表
     */
    fun getAll(pomPath: String, folderPaths: List<String>): List<DependencyInfo> {
        val env = Env(localRepositoryPath)
        val gavCollector = GavCollector()

        // 注意：原 Java 代码中有个 bug，这里修复了
        // 原代码是 if (pomFile.exists()) throw ...，应该是 if (!pomFile.exists())
        val pomFile = File(pomPath)
        if (!pomFile.exists()) {
            logger.warn("POM 文件不存在: $pomPath")
            // 即使主 POM 不存在，也继续处理文件夹路径
        }

        try {
            GavBatchParser(env).parseBatch(folderPaths, gavCollector)
            return gavCollector.toDependencyInfoList()
        } catch (e: ModelBuildingException) {
            logger.error("解析 POM 文件失败: $pomPath", e)
            return emptyList()
        }
    }
    
    /**
     * 从 Maven 项目获取所有依赖
     * 
     * @param mavenProjects Maven 项目列表
     * @param progressIndicator 进度指示器（可选）
     * @return 依赖信息列表
     */
    fun getAllFromMavenProjects(
        mavenProjects: List<MavenProject>,
        progressIndicator: ProgressIndicator? = null
    ): List<DependencyInfo> {
        val env = Env(localRepositoryPath)
        val gavCollector = GavCollector()
        
        val folderPaths = mutableListOf<String>()
        
        // 收集所有 Maven 项目的 POM 文件路径
        mavenProjects.forEach { mavenProject ->
            val pomFile = mavenProject.file
            if (pomFile != null) {
                // 对于 GavBatchParser，我们需要传入项目目录路径，而不是 POM 文件路径
                // 但 GavBatchParser 会递归查找所有 pom 和 jar 文件
                // 使用 POM 文件的父目录作为项目目录
                val pomFileObj = File(pomFile.path)
                val parentDir = pomFileObj.parent
                if (parentDir != null) {
                    folderPaths.add(parentDir)
                }
            }
        }
        
        if (folderPaths.isEmpty()) {
            logger.warn("没有找到有效的 Maven 项目路径")
            return emptyList()
        }
        
        logger.info("准备分析的文件夹路径: $folderPaths")
        
        progressIndicator?.text = "分析 Maven 依赖..."
        progressIndicator?.isIndeterminate = false
        
        try {
            val batchParser = GavBatchParser(env)
            batchParser.parseBatch(folderPaths, gavCollector)
            
            progressIndicator?.fraction = 1.0
            progressIndicator?.text2 = "依赖分析完成"
            
            val dependencies = gavCollector.toDependencyInfoList()
            logger.info("依赖分析完成，共发现 ${dependencies.size} 个依赖")
            
            return dependencies.sortedBy { it.getGAV() }
        } catch (e: ModelBuildingException) {
            logger.error("批量解析 Maven 依赖失败", e)
            return emptyList()
        }
    }
}

