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
        val mavenProjects = mavenProjectsManager.rootProjects

        progressIndicator?.text = "分析Maven项目依赖..."
        progressIndicator?.isIndeterminate = false

        val totalProjects = mavenProjects.size

        mavenProjects.forEachIndexed { index, mavenProject ->
            progressIndicator?.fraction = index.toDouble() / totalProjects.toDouble()
            progressIndicator?.text2 = "正在处理模块: ${mavenProject.displayName}"

            logger.info("处理Maven项目: ${mavenProject.displayName}")

            // 分析项目依赖
            analyzeProjectDependencies(mavenProject, dependencies)
        }

        progressIndicator?.fraction = 1.0
        progressIndicator?.text2 = "依赖分析完成"

        logger.info("依赖分析完成，共发现 ${dependencies.size} 个依赖")

        return dependencies.sortedBy { it.getGAV() }
    }

    /**
     * 分析单个Maven项目的依赖
     */
    private fun analyzeProjectDependencies(mavenProject: MavenProject, dependencies: MutableSet<DependencyInfo>) {
        try {
            // 获取所有依赖树
            val dependencyTree = mavenProject.dependencyTree
            if (dependencyTree != null) {
                collectDependenciesFromNode(dependencyTree, dependencies)
            }

            // 获取测试依赖
            val testDependencyTree = mavenProject.testDependencyTree
            if (testDependencyTree != null) {
                collectDependenciesFromNode(testDependencyTree, dependencies)
            }

        } catch (e: Exception) {
            logger.error("分析项目 ${mavenProject.displayName} 依赖时发生错误", e)
        }
    }

    /**
     * 从依赖节点递归收集依赖
     */
    private fun collectDependenciesFromNode(node: MavenArtifactNode, dependencies: MutableSet<DependencyInfo>) {
        val artifact = node.artifact

        // 跳过项目自身的模块
        if (isProjectModule(artifact)) {
            logger.debug("跳过项目模块: ${artifact.groupId}:${artifact.artifactId}")
            node.dependants.forEach { childNode ->
                collectDependenciesFromNode(childNode, dependencies)
            }
            return
        }

        // 只处理jar类型的依赖（主要关注打包需要的依赖）
        if (artifact.file != null && (artifact.packaging == "jar" || artifact.packaging == "aar")) {
            val dependencyInfo = createDependencyInfo(artifact)
            dependencies.add(dependencyInfo)
        }

        // 递归处理子依赖（传递依赖）
        node.dependants.forEach { childNode ->
            collectDependenciesFromNode(childNode, dependencies)
        }
    }

    /**
     * 判断是否为项目的模块
     */
    private fun isProjectModule(artifact: MavenArtifact): Boolean {
        val mavenProjects = mavenProjectsManager.projects
        return mavenProjects.any { mavenProject ->
            mavenProject.mavenId.groupId == artifact.groupId &&
            mavenProject.mavenId.artifactId == artifact.artifactId
        }
    }

    /**
     * 创建依赖信息对象
     */
    private fun createDependencyInfo(artifact: MavenArtifact): DependencyInfo {
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
        return mavenProjectsManager.hasProjects()
    }

    /**
     * 获取所有Maven模块
     */
    fun getMavenModules(): List<MavenProject> {
        return mavenProjectsManager.projects
    }
}