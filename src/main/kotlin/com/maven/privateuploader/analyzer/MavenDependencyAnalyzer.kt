package com.maven.privateuploader.analyzer

import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.project.Project
import com.maven.privateuploader.model.DependencyInfo
import org.jetbrains.idea.maven.project.MavenProject
import org.jetbrains.idea.maven.project.MavenProjectsManager
import java.io.File

/**
 * Maven依赖分析器
 * 负责分析当前项目中所有的Maven依赖（包括传递依赖）
 * 
 * 使用 GavParserGroup 进行依赖分析
 */
class MavenDependencyAnalyzer(private val project: Project) {

    private val logger = thisLogger()
    private val mavenProjectsManager = MavenProjectsManager.getInstance(project)
    
    /**
     * 获取本地 Maven 仓库路径
     */
    private fun getLocalMavenRepositoryPath(): String {
        val mavenHome = System.getProperty("user.home")
        val mavenRepo = System.getProperty("maven.repo.local", "$mavenHome/.m2/repository")
        return File(mavenRepo).absolutePath
    }

    /**
     * 分析项目中所有的Maven依赖
     *
     * @param progressIndicator 进度指示器
     * @return 依赖信息列表
     */
    fun analyzeDependencies(progressIndicator: ProgressIndicator? = null): List<DependencyInfo> {
        logger.info("=========================================")
        logger.info("【Maven依赖分析】开始分析Maven依赖...")
        logger.info("=========================================")

        // 等待Maven项目完全加载（最多等待5秒，每次等待100ms）
        var allMavenProjects = mavenProjectsManager.projects
        var waitCount = 0
        val maxWaitCount = 50  // 5秒 = 50 * 100ms
        
        while (allMavenProjects.isEmpty() && waitCount < maxWaitCount) {
            logger.info("等待Maven项目加载... (${waitCount + 1}/$maxWaitCount)")
            Thread.sleep(100)
            allMavenProjects = mavenProjectsManager.projects
            waitCount++
        }
        
        if (allMavenProjects.isEmpty()) {
            logger.warn("等待超时，Maven项目列表仍为空")
            return emptyList()
        }

        val rootProjects = mavenProjectsManager.rootProjects

        logger.info("Maven项目信息:")
        logger.info("- 总项目数: ${allMavenProjects.size}")
        logger.info("- 根项目数: ${rootProjects.size}")
        logger.info("- Maven项目管理器状态: ${mavenProjectsManager.state}")

        // 记录所有项目的详细信息
        allMavenProjects.forEachIndexed { index, mavenProject ->
            logger.info("项目 $index: ${mavenProject.displayName} (${mavenProject.mavenId.groupId}:${mavenProject.mavenId.artifactId}:${mavenProject.mavenId.version})")
            logger.info("  路径: ${mavenProject.directory}")
            logger.info("  打包: ${mavenProject.packaging}")
            logger.info("  直接依赖数: ${mavenProject.dependencies.size}")
        }

        progressIndicator?.text = "分析Maven项目依赖..."
        progressIndicator?.isIndeterminate = false

        // 使用新的 GavParserGroup 进行依赖分析
        val localRepoPath = getLocalMavenRepositoryPath()
        val gavParserGroup = GavParserGroup(localRepoPath)
        
        try {
            val dependencies = gavParserGroup.getAllFromMavenProjects(allMavenProjects, progressIndicator)

        logger.info("=========================================")
            logger.info("【Maven依赖分析】依赖分析完成，共发现 ${dependencies.size} 个依赖")
        logger.info("=========================================")

        // 记录最终找到的依赖列表（前20个）
        dependencies.take(20).forEachIndexed { index, dep ->
            logger.info("依赖 $index: ${dep.getGAV()} -> ${dep.localPath}")
        }
        if (dependencies.size > 20) {
            logger.info("... 还有 ${dependencies.size - 20} 个依赖")
        }

            return dependencies
        } catch (e: Exception) {
            logger.error("依赖分析失败", e)
            return emptyList()
        }
    }

    /**
     * 检查项目是否为Maven项目
     */
    fun isMavenProject(): Boolean {
        val hasProjects = mavenProjectsManager.hasProjects()

        logger.info("Maven项目检查结果:")
        logger.info("- hasProjects: $hasProjects")
        logger.info("- projects.size: ${mavenProjectsManager.projects.size}")
        logger.info("- rootProjects.size: ${mavenProjectsManager.rootProjects.size}")
        logger.info("- mavenProjectsManager state: ${mavenProjectsManager.state}")

        // 检查项目是否正确配置
        if (hasProjects) {
            val projects = mavenProjectsManager.projects
            projects.forEach { mavenProject ->
                logger.info("发现Maven项目: ${mavenProject.displayName}")
                logger.info("  Maven文件: ${mavenProject.file}")
                logger.info("  项目目录: ${mavenProject.directory}")
                logger.info("  是否已导入: ${mavenProjectsManager.isMavenizedProject()}")
            }
        } else {
            // 如果没有检测到Maven项目，尝试查找pom.xml文件
            logger.warn("未检测到Maven项目，尝试查找pom.xml文件...")
            try {
                val projectBase = project.basePath
                if (projectBase != null) {
                    val pomFile = java.io.File(projectBase, "pom.xml")
                    logger.info("项目根目录下的pom.xml存在: ${pomFile.exists()}")
                    if (pomFile.exists()) {
                        logger.info("pom.xml路径: ${pomFile.absolutePath}")
                        logger.info("pom.xml可读: ${pomFile.canRead()}")
                    }
                }
            } catch (e: Exception) {
                logger.error("检查pom.xml文件时出错", e)
            }
        }

        return hasProjects
    }

    /**
     * 获取所有Maven模块
     */
    fun getMavenModules(): List<MavenProject> {
        return mavenProjectsManager.projects
    }
}
