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
        var rootProjects = mavenProjectsManager.rootProjects
        var waitCount = 0
        val maxWaitCount = 50  // 5秒 = 50 * 100ms
        
        while (rootProjects.isEmpty() && waitCount < maxWaitCount) {
            logger.info("等待Maven项目加载... (${waitCount + 1}/$maxWaitCount)")
            Thread.sleep(100)
            rootProjects = mavenProjectsManager.rootProjects
            waitCount++
        }
        
        // 获取根项目的 pom.xml 路径
        val rootPomPath = when {
            rootProjects.isNotEmpty() -> {
                val rootProject = rootProjects.first()
                File(rootProject.file.path).absolutePath
            }
            project.basePath != null -> {
                val pomFile = File(project.basePath, "pom.xml")
                if (pomFile.exists()) {
                    pomFile.absolutePath
                } else {
                    null
                }
            }
            else -> null
        }
        
        if (rootPomPath == null) {
            logger.warn("未找到项目根目录的 pom.xml 文件")
            return emptyList()
        }

        logger.info("使用根 POM 文件: $rootPomPath")
        logger.info("- Maven项目管理器状态: ${mavenProjectsManager.state}")

        progressIndicator?.text = "分析Maven项目依赖..."
        progressIndicator?.isIndeterminate = false

        // Maven 超级 POM 中引用的必要插件路径
        // 这些是 Maven 在超级 POM 中引用的插件，一般固定值
        // 后续可能会交给用户来配置
        val folderPaths = listOf(
            "org/apache/maven/plugins",
            "org/codehaus/plexus",
            "org/apache/apache",
            "org/codehaus/mojo"
        )

        // 使用新的 GavParserGroup 进行依赖分析
        // getAll 方法只需要项目根目录的 pom.xml，就可以递归解析子模块
        val localRepoPath = getLocalMavenRepositoryPath()
        val gavParserGroup = GavParserGroup(localRepoPath)
        
        try {
            val dependencies = gavParserGroup.getAll(rootPomPath, folderPaths)

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
