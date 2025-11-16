package com.maven.privateuploader.analyzer

import com.intellij.openapi.diagnostic.thisLogger
import com.maven.privateuploader.model.DependencyInfo
import org.apache.maven.model.Dependency
import org.apache.maven.model.Parent
import org.apache.maven.model.Plugin
import java.io.File

/**
 * POM 解析辅助类
 * 提供与旧 PomParser 兼容的接口，但内部使用新的 MavenProjectArtifactCollector
 * 
 * 注意：这个类是为了兼容现有代码而创建的，新代码应该直接使用 MavenProjectArtifactCollector
 */
class PomParserHelper {

    private val logger = thisLogger()
    private val modelUtils = MavenModelUtils()

    /**
     * POM 信息数据类（兼容旧接口）
     */
    data class PomInfo(
        val groupId: String?,
        val artifactId: String?,
        val version: String?,
        val parent: ParentInfo?,
        val bomDependencies: List<Dependency>,
        val dependencies: List<Dependency>,
        val plugins: List<Plugin>,
        val properties: Map<String, String>
    )

    /**
     * 父 POM 信息（兼容旧接口）
     */
    data class ParentInfo(
        val groupId: String,
        val artifactId: String,
        val version: String,
        val relativePath: String?
    )

    /**
     * 解析 POM 文件（兼容旧接口）
     */
    fun parsePom(pomFile: File, parsePlugins: Boolean = true): PomInfo? {
        if (!pomFile.exists() || !pomFile.isFile) {
            logger.warn("POM 文件不存在: ${pomFile.absolutePath}")
            return null
        }

        return try {
            val model = modelUtils.buildEffectiveModel(pomFile, processPlugins = parsePlugins)
            if (model == null) {
                logger.warn("无法构建有效模型: ${pomFile.absolutePath}")
                return null
            }

            // 转换 Model 为 PomInfo
            val groupId = model.groupId
            val artifactId = model.artifactId
            val version = model.version

            val parent = model.parent?.let {
                ParentInfo(
                    groupId = it.groupId ?: "",
                    artifactId = it.artifactId ?: "",
                    version = it.version ?: "",
                    relativePath = it.relativePath
                )
            }

            val bomDependencies = model.dependencyManagement?.dependencies?.filter { dep ->
                dep.scope == "import" && (dep.type ?: "pom") == "pom" &&
                !dep.groupId.isNullOrBlank() && !dep.artifactId.isNullOrBlank() && !dep.version.isNullOrBlank() &&
                !containsUnresolvedProperty(dep.version)
            } ?: emptyList()

            val dependencies = model.dependencies?.filter { dep ->
                !dep.groupId.isNullOrBlank() && !dep.artifactId.isNullOrBlank() &&
                !containsUnresolvedProperty(dep.version)
            } ?: emptyList()

            val plugins = if (parsePlugins) {
                model.build?.plugins?.filter { plugin ->
                    val artifactId = plugin.artifactId
                    val version = plugin.version
                    !artifactId.isNullOrBlank() && !version.isNullOrBlank() && !containsUnresolvedProperty(version)
                } ?: emptyList()
            } else {
                emptyList()
            }

            val properties = model.properties?.let { props ->
                props.stringPropertyNames().associateWith { props.getProperty(it) }
            } ?: emptyMap()

            PomInfo(
                groupId = groupId,
                artifactId = artifactId,
                version = version,
                parent = parent,
                bomDependencies = bomDependencies,
                dependencies = dependencies,
                plugins = plugins,
                properties = properties
            )
        } catch (e: Exception) {
            logger.error("解析 POM 文件失败: ${pomFile.absolutePath}", e)
            null
        }
    }

    /**
     * 检查字符串是否包含未解析的 Maven 属性占位符
     */
    private fun containsUnresolvedProperty(value: String?): Boolean {
        if (value.isNullOrBlank()) return false
        return value.contains(Regex("\\$\\{[^}]+\\}"))
    }

    /**
     * 构建本地 Maven 仓库中的 POM 文件路径
     */
    fun buildLocalPomPath(groupId: String, artifactId: String, version: String): File {
        val localRepoPath = getLocalMavenRepositoryPath()
        val pomPath = File(
            localRepoPath,
            "${groupId.replace('.', '/')}/$artifactId/$version/$artifactId-$version.pom"
        )
        return pomPath
    }

    /**
     * 获取本地 Maven 仓库路径
     */
    private fun getLocalMavenRepositoryPath(): String {
        val mavenHome = System.getProperty("user.home")
        val mavenRepo = System.getProperty("maven.repo.local", "$mavenHome/.m2/repository")
        return File(mavenRepo).absolutePath
    }

    /**
     * 将 ParentInfo 转换为 DependencyInfo
     */
    fun parentToDependencyInfo(parent: ParentInfo): DependencyInfo? {
        if (containsUnresolvedProperty(parent.version)) {
            logger.debug("跳过包含未解析属性占位符的父 POM: ${parent.groupId}:${parent.artifactId}:${parent.version}")
            return null
        }

        val pomFile = buildLocalPomPath(parent.groupId, parent.artifactId, parent.version)
        val localPath = if (pomFile.exists()) {
            pomFile.absolutePath
        } else {
            logger.warn("父 POM 文件不存在: ${pomFile.absolutePath}，但仍会添加到依赖列表中")
            ""
        }

        return DependencyInfo(
            groupId = parent.groupId,
            artifactId = parent.artifactId,
            version = parent.version,
            packaging = "pom",
            localPath = localPath,
            checkStatus = com.maven.privateuploader.model.CheckStatus.UNKNOWN,
            selected = false,
            errorMessage = if (localPath.isEmpty()) "本地文件不存在" else ""
        )
    }

    /**
     * 将 Maven Dependency（BOM）转换为 DependencyInfo
     */
    fun bomToDependencyInfo(bom: Dependency): DependencyInfo? {
        val groupId = bom.groupId
        val artifactId = bom.artifactId
        val version = bom.version

        if (groupId.isNullOrBlank() || artifactId.isNullOrBlank() || version.isNullOrBlank()) {
            return null
        }

        if (containsUnresolvedProperty(version)) {
            logger.debug("跳过包含未解析属性占位符的 BOM: $groupId:$artifactId:$version")
            return null
        }

        val pomFile = buildLocalPomPath(groupId, artifactId, version)
        val localPath = if (pomFile.exists()) {
            pomFile.absolutePath
        } else {
            logger.warn("BOM POM 文件不存在: ${pomFile.absolutePath}，但仍会添加到依赖列表中")
            ""
        }

        return DependencyInfo(
            groupId = groupId,
            artifactId = artifactId,
            version = version,
            packaging = "pom",
            localPath = localPath,
            checkStatus = com.maven.privateuploader.model.CheckStatus.UNKNOWN,
            selected = false,
            errorMessage = if (localPath.isEmpty()) "本地文件不存在" else ""
        )
    }

    /**
     * 将 Maven Dependency（传递依赖）转换为 DependencyInfo
     */
    fun dependencyToDependencyInfo(dep: Dependency): DependencyInfo? {
        val groupId = dep.groupId
        val artifactId = dep.artifactId
        val version = dep.version

        if (groupId.isNullOrBlank() || artifactId.isNullOrBlank()) {
            return null
        }

        if (version.isNullOrBlank()) {
            logger.debug("传递依赖 $groupId:$artifactId 没有版本信息，跳过")
            return null
        }

        if (containsUnresolvedProperty(version)) {
            logger.debug("跳过包含未解析属性占位符的传递依赖: $groupId:$artifactId:$version")
            return null
        }

        val packaging = dep.type ?: "jar"
        val localRepoPath = getLocalMavenRepositoryPath()

        val localPath = when (packaging) {
            "pom" -> {
                val pomFile = File(
                    localRepoPath,
                    "${groupId.replace('.', '/')}/$artifactId/$version/$artifactId-$version.pom"
                )
                if (pomFile.exists()) pomFile.absolutePath else ""
            }
            else -> {
                val jarFile = File(
                    localRepoPath,
                    "${groupId.replace('.', '/')}/$artifactId/$version/$artifactId-$version.jar"
                )
                val pomFile = File(
                    localRepoPath,
                    "${groupId.replace('.', '/')}/$artifactId/$version/$artifactId-$version.pom"
                )
                when {
                    jarFile.exists() -> jarFile.absolutePath
                    pomFile.exists() -> pomFile.absolutePath
                    else -> ""
                }
            }
        }

        if (localPath.isEmpty()) {
            logger.debug("传递依赖文件不存在: $groupId:$artifactId:$version")
        }

        return DependencyInfo(
            groupId = groupId,
            artifactId = artifactId,
            version = version,
            packaging = packaging,
            localPath = localPath,
            checkStatus = com.maven.privateuploader.model.CheckStatus.UNKNOWN,
            selected = false,
            errorMessage = if (localPath.isEmpty()) "本地文件不存在" else ""
        )
    }

    /**
     * 将 Maven Plugin 转换为 DependencyInfo
     */
    fun pluginToDependencyInfo(plugin: Plugin): DependencyInfo? {
        val groupId = plugin.groupId ?: "org.apache.maven.plugins"
        val artifactId = plugin.artifactId
        val version = plugin.version

        if (artifactId.isNullOrBlank() || version.isNullOrBlank()) {
            return null
        }

        if (containsUnresolvedProperty(version)) {
            logger.debug("跳过包含未解析属性占位符的插件: $groupId:$artifactId:$version")
            return null
        }

        val localRepoPath = getLocalMavenRepositoryPath()
        val jarFile = File(
            localRepoPath,
            "${groupId.replace('.', '/')}/$artifactId/$version/$artifactId-$version.jar"
        )
        val pomFile = File(
            localRepoPath,
            "${groupId.replace('.', '/')}/$artifactId/$version/$artifactId-$version.pom"
        )

        val localPath = when {
            jarFile.exists() -> jarFile.absolutePath
            pomFile.exists() -> pomFile.absolutePath
            else -> {
                logger.warn("插件文件不存在: ${jarFile.absolutePath}，但仍会添加到依赖列表中")
                ""
            }
        }

        return DependencyInfo(
            groupId = groupId,
            artifactId = artifactId,
            version = version,
            packaging = "maven-plugin",
            localPath = localPath,
            checkStatus = com.maven.privateuploader.model.CheckStatus.UNKNOWN,
            selected = false,
            errorMessage = if (localPath.isEmpty()) "本地文件不存在" else ""
        )
    }
}

