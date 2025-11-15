package com.maven.privateuploader.analyzer

import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.project.Project
import com.intellij.psi.search.FilenameIndex
import com.intellij.psi.search.GlobalSearchScope
import org.jetbrains.idea.maven.model.MavenArtifact
import org.jetbrains.idea.maven.model.MavenArtifactNode
import org.jetbrains.idea.maven.project.MavenProject
import org.jetbrains.idea.maven.project.MavenProjectsManager
import com.maven.privateuploader.model.DependencyInfo
import com.maven.privateuploader.model.CheckStatus
import java.io.File

/**
 * Maven依赖分析器
 * 负责分析当前项目中所有的Maven依赖（包括传递依赖）
 */
class MavenDependencyAnalyzer(private val project: Project) {

    private val logger = thisLogger()
    private val mavenProjectsManager = MavenProjectsManager.getInstance(project)

    /**
     * 分析项目中所有的Maven依赖
     *
     * @param progressIndicator 进度指示器
     * @return 依赖信息列表
     */
    fun analyzeDependencies(progressIndicator: ProgressIndicator? = null): List<DependencyInfo> {
        logger.info("开始分析Maven依赖...")

        val dependencies = mutableSetOf<DependencyInfo>()

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
        }

        val rootProjects = mavenProjectsManager.rootProjects

        logger.info("Maven项目信息:")
        logger.info("- 总项目数: ${allMavenProjects.size}")
        logger.info("- 根项目数: ${rootProjects.size}")
        logger.info("- Maven项目管理器状态: ${mavenProjectsManager.state}")

        // 记录所有项目的详细信息
        allMavenProjects.forEachIndexed { index, project ->
            logger.info("项目 $index: ${project.displayName} (${project.mavenId.groupId}:${project.mavenId.artifactId}:${project.mavenId.version})")
            logger.info("  路径: ${project.directory}")
            logger.info("  打包: ${project.packaging}")
            logger.info("  直接依赖数: ${project.dependencies.size}")
        }

        progressIndicator?.text = "分析Maven项目依赖..."
        progressIndicator?.isIndeterminate = false

        val totalProjects = allMavenProjects.size

        allMavenProjects.forEachIndexed { index, mavenProject ->
            progressIndicator?.fraction = index.toDouble() / totalProjects.toDouble()
            progressIndicator?.text2 = "正在处理模块: ${mavenProject.displayName}"

            logger.info("处理Maven项目 (${index + 1}/$totalProjects): ${mavenProject.displayName}")

            // 分析项目依赖
            analyzeProjectDependencies(mavenProject, dependencies)
        }

        progressIndicator?.fraction = 1.0
        progressIndicator?.text2 = "依赖分析完成"

        logger.info("依赖分析完成，共发现 ${dependencies.size} 个依赖")

        // 记录最终找到的依赖列表（前20个）
        dependencies.take(20).forEachIndexed { index, dep ->
            logger.info("依赖 $index: ${dep.getGAV()} -> ${dep.localPath}")
        }
        if (dependencies.size > 20) {
            logger.info("... 还有 ${dependencies.size - 20} 个依赖")
        }

        return dependencies.sortedBy { it.getGAV() }
    }

    /**
     * 分析单个Maven项目的依赖
     */
    private fun analyzeProjectDependencies(mavenProject: MavenProject, dependencies: MutableSet<DependencyInfo>) {
        try {
            logger.info("开始分析项目: ${mavenProject.displayName}")
            logger.info("项目路径: ${mavenProject.directory}")

            // 记录项目的基本信息
            logger.info("项目GAV: ${mavenProject.mavenId.groupId}:${mavenProject.mavenId.artifactId}:${mavenProject.mavenId.version}")

            // 分析直接依赖
            logger.info("发现 ${mavenProject.dependencies.size} 个直接依赖")
            mavenProject.dependencies.forEachIndexed { index, dependency ->
                logger.debug("直接依赖 $index: ${dependency.groupId}:${dependency.artifactId}:${dependency.version}, 文件: ${dependency.file}, 包装: ${dependency.packaging}")
                if (dependency.file != null && dependency.packaging == "jar") {
                    val dependencyInfo = createDependencyInfo(dependency)
                    dependencies.add(dependencyInfo)
                    logger.debug("添加直接依赖: ${dependencyInfo.getGAV()}")
                }
            }

            // 分析传递依赖树 - 这是关键！
            logger.info("开始分析传递依赖树...")
            analyzeDependencyTree(mavenProject, dependencies)

            logger.info("项目 ${mavenProject.displayName} 分析完成，当前总依赖数: ${dependencies.size}")

        } catch (e: Exception) {
            logger.error("分析项目 ${mavenProject.displayName} 依赖时发生错误", e)
        }
    }

    /**
     * 分析完整的依赖树（包括传递依赖）
     */
    private fun analyzeDependencyTree(mavenProject: MavenProject, dependencies: MutableSet<DependencyInfo>) {
        try {
            // 获取所有已解析的依赖（包括传递依赖）
            // 使用 getDeclaredDependencies 和 plugins 来获取依赖
            val allDependencies = mutableListOf<MavenArtifact>()

            // 添加声明的依赖
            allDependencies.addAll(mavenProject.dependencies)

            logger.info("依赖树包含 ${allDependencies.size} 个节点")

            allDependencies.forEachIndexed { index, dependency ->
                try {
                    logger.debug("依赖树节点 $index: ${dependency.groupId}:${dependency.artifactId}:${dependency.version}, 文件: ${dependency.file}, 范围: ${dependency.scope}")

                    // 过滤掉项目的自有模块和测试依赖
                    if (shouldIncludeDependency(dependency, mavenProject)) {
                        val dependencyInfo = createDependencyInfo(dependency)
                        dependencies.add(dependencyInfo)
                        logger.debug("添加依赖树依赖: ${dependencyInfo.getGAV()}")
                    }
                } catch (e: Exception) {
                    logger.warn("处理依赖树节点时出错: ${e.message}")
                }
            }

            // 尝试通过插件获取更多依赖
            try {
                mavenProject.plugins.forEach { plugin ->
                    try {
                        // 插件依赖的类型是MavenId，不是MavenArtifact，所以我们需要跳过这部分
                        // 或者我们可以记录插件信息，但不将其作为依赖上传
                        logger.debug("发现插件: ${plugin.groupId}:${plugin.artifactId}:${plugin.version}")
                    } catch (e: Exception) {
                        logger.debug("处理插件依赖时出错: ${e.message}")
                    }
                }
            } catch (e: Exception) {
                logger.warn("获取插件依赖时出错: ${e.message}")
            }

            logger.info("依赖树分析完成，总依赖数: ${dependencies.size}")

        } catch (e: Exception) {
            logger.error("分析依赖树时发生错误", e)
        }
    }

    /**
     * 判断是否应该包含此依赖
     */
    private fun shouldIncludeDependency(artifact: org.jetbrains.idea.maven.model.MavenArtifact, currentProject: MavenProject): Boolean {
        // 过滤条件
        if (artifact.file == null) {
            logger.debug("跳过无文件的依赖: ${artifact.groupId}:${artifact.artifactId}")
            return false
        }

        // 只包含JAR包
        if (artifact.packaging != "jar") {
            logger.debug("跳过非JAR依赖: ${artifact.groupId}:${artifact.artifactId} (${artifact.packaging})")
            return false
        }

        // 跳过测试依赖
        if (artifact.scope == "test" || artifact.scope == "provided") {
            logger.debug("跳过测试/提供依赖: ${artifact.groupId}:${artifact.artifactId} (${artifact.scope})")
            return false
        }

        // 跳过项目自有模块
        if (isProjectModule(artifact)) {
            logger.debug("跳过项目自有模块: ${artifact.groupId}:${artifact.artifactId}")
            return false
        }

        return true
    }

    // 注意：这个方法现在不再需要，因为我们在 analyzeDependencyTree 中已经处理了插件依赖

    /**
     * 创建依赖信息对象
     */
    private fun createDependencyInfo(artifact: org.jetbrains.idea.maven.model.MavenArtifact): DependencyInfo {
        val localPath = artifact.file?.absolutePath ?: ""
        val packaging = artifact.packaging ?: "jar"

        return DependencyInfo(
            groupId = artifact.groupId,
            artifactId = artifact.artifactId,
            version = artifact.version,
            packaging = packaging,
            localPath = localPath,
            checkStatus = CheckStatus.UNKNOWN,
            selected = false
        ).apply {
            // 默认选择缺失的依赖（这个逻辑将在预检查后更新）
            selected = false
        }
    }

    /**
     * 判断是否为项目的模块
     */
    private fun isProjectModule(artifact: org.jetbrains.idea.maven.model.MavenArtifact): Boolean {
        val mavenProjects = mavenProjectsManager.projects
        return mavenProjects.any { mavenProject ->
            mavenProject.mavenId.groupId == artifact.groupId &&
            mavenProject.mavenId.artifactId == artifact.artifactId
        }
    }

    /**
     * 获取本地Maven仓库路径
     */
    private fun getLocalMavenRepositoryPath(): String {
        val mavenHome = System.getProperty("user.home")
        val mavenRepo = System.getProperty("maven.repo.local", "$mavenHome/.m2/repository")
        return File(mavenRepo).absolutePath
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
            projects.forEach { project ->
                logger.info("发现Maven项目: ${project.displayName}")
                logger.info("  Maven文件: ${project.file}")
                logger.info("  项目目录: ${project.directory}")
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