package com.maven.privateuploader.analyzer

import com.intellij.openapi.diagnostic.thisLogger
import com.maven.privateuploader.model.DependencyInfo
import org.apache.maven.model.Model
import org.apache.maven.model.Parent
import org.apache.maven.model.Dependency
import org.apache.maven.model.Plugin
import java.io.File

/**
 * POM 解析器
 * 用于解析 POM 文件并提取父 POM 和 BOM 依赖信息
 * 
 * 已改造为使用 Maven 官方的 maven-model-builder 来构建有效 POM，
 * 保证和 Maven CLI 构建行为一致。
 */
class PomParser {
    
    private val logger = thisLogger()
    private val effectivePomResolver = EffectivePomResolver()
    
    /**
     * POM 信息数据类
     */
    data class PomInfo(
        val groupId: String?,
        val artifactId: String?,
        val version: String?,
        val parent: ParentInfo?,
        val bomDependencies: List<BomDependency>,
        val dependencies: List<TransitiveDependency>,
        val plugins: List<PluginDependency>,
        val properties: Map<String, String>
    )
    
    /**
     * 父 POM 信息
     */
    data class ParentInfo(
        val groupId: String,
        val artifactId: String,
        val version: String,
        val relativePath: String?
    )
    
    /**
     * BOM 依赖信息（dependencyManagement 中 scope=import 的依赖）
     */
    data class BomDependency(
        val groupId: String,
        val artifactId: String,
        val version: String
    )
    
    /**
     * 传递依赖信息（dependencies 中的普通依赖）
     */
    data class TransitiveDependency(
        val groupId: String,
        val artifactId: String,
        val version: String?,
        val scope: String?,
        val type: String?
    )
    
    /**
     * Maven 插件信息（最终解析后的版本）
     */
    data class PluginDependency(
        val groupId: String,
        val artifactId: String,
        val version: String
    )
    
    /**
     * 解析 POM 文件
     * 
     * 使用 Maven 官方的 maven-model-builder 来构建有效 POM，
     * 保证和 Maven CLI 构建行为一致。
     *
     * @param pomFile POM 文件路径
     * @param parsePlugins 是否解析插件（默认 true）。当为 false 时，只解析 properties 等基本信息，不解析插件
     * @return POM 信息，如果解析失败返回 null
     */
    fun parsePom(pomFile: File, parsePlugins: Boolean = true): PomInfo? {
        if (!pomFile.exists() || !pomFile.isFile) {
            logger.warn("POM 文件不存在: ${pomFile.absolutePath}")
            return null
        }
        
        return try {
            // 使用 Maven 官方的 ModelBuilder 构建有效 POM
            val effectiveModel = effectivePomResolver.buildEffectiveModel(pomFile, processPlugins = parsePlugins)
                ?: return null
            
            // 将有效 Model 转换为 PomInfo
            convertModelToPomInfo(effectiveModel, pomFile, parsePlugins)
        } catch (e: Exception) {
            logger.error("解析 POM 文件失败: ${pomFile.absolutePath}", e)
            null
        }
    }
    
    /**
     * 将 Maven Model 转换为 PomInfo
     */
    private fun convertModelToPomInfo(model: Model, pomFile: File, parsePlugins: Boolean): PomInfo {
        // 基本信息（effective model 已经处理完所有继承和属性替换）
        val groupId = model.groupId
        val artifactId = model.artifactId
        val version = model.version
        
        // 父 POM 信息
        val parent = model.parent?.let { convertParent(it) }
        
        // Properties（effective model 已经合并了所有父 POM 的属性）
        val properties = model.properties?.let { props ->
            props.stringPropertyNames().associateWith { props.getProperty(it) }
        } ?: emptyMap()
        
        // BOM 依赖（dependencyManagement 中 scope=import 的依赖）
        val bomDependencies = parseBomDependenciesFromModel(model)
        
        // 普通依赖（dependencies 中的依赖，effective model 已经应用了 dependencyManagement）
        val dependencies = model.dependencies?.mapNotNull { dep ->
            convertDependency(dep)
        } ?: emptyList()
        
        // 插件（effective model 已经应用了 pluginManagement 和继承）
        val plugins = if (parsePlugins) {
            parsePluginsFromModel(model)
        } else {
            emptyList()
        }
        
        logger.info("【POM解析】解析完成: $groupId:$artifactId:$version, " +
                "父POM=${parent?.let { "${it.groupId}:${it.artifactId}:${it.version}" } ?: "无"}, " +
                "BOM依赖=${bomDependencies.size}个, " +
                "普通依赖=${dependencies.size}个, " +
                "插件=${plugins.size}个")
        
        return PomInfo(
            groupId = groupId,
            artifactId = artifactId,
            version = version,
            parent = parent,
            bomDependencies = bomDependencies,
            dependencies = dependencies,
            plugins = plugins,
            properties = properties
        )
    }
    
    /**
     * 转换 Maven Parent 为 ParentInfo
     */
    private fun convertParent(parent: Parent): ParentInfo {
        return ParentInfo(
            groupId = parent.groupId ?: "",
            artifactId = parent.artifactId ?: "",
            version = parent.version ?: "",
            relativePath = parent.relativePath
        )
    }
    
    /**
     * 从有效 Model 中解析 BOM 依赖（dependencyManagement 中 scope=import 的依赖）
     */
    private fun parseBomDependenciesFromModel(model: Model): List<BomDependency> {
        val bomDependencies = mutableListOf<BomDependency>()
        
        model.dependencyManagement?.dependencies?.forEach { dep ->
            // 检查 scope 是否为 import
            if (dep.scope == "import") {
                // 检查 type 是否为 pom（BOM 必须是 pom 类型）
                val type = dep.type ?: "pom"
                if (type == "pom") {
                    val groupId = dep.groupId
                    val artifactId = dep.artifactId
                    val version = dep.version
                    
                    if (!groupId.isNullOrBlank() && !artifactId.isNullOrBlank() && !version.isNullOrBlank()) {
                        bomDependencies.add(
                            BomDependency(
                                groupId = groupId,
                                artifactId = artifactId,
                                version = version
                            )
                        )
                        logger.debug("发现 BOM 依赖: $groupId:$artifactId:$version")
                    }
                }
            }
        }
        
        return bomDependencies
    }
    
    /**
     * 转换 Maven Dependency 为 TransitiveDependency
     */
    private fun convertDependency(dep: Dependency): TransitiveDependency? {
        val groupId = dep.groupId
        val artifactId = dep.artifactId
        
        if (groupId.isNullOrBlank() || artifactId.isNullOrBlank()) {
            return null
        }
        
        return TransitiveDependency(
            groupId = groupId,
            artifactId = artifactId,
            version = dep.version,
            scope = dep.scope,
            type = dep.type
        )
    }
    
    /**
     * 从有效 Model 中解析插件
     */
    private fun parsePluginsFromModel(model: Model): List<PluginDependency> {
        val plugins = mutableSetOf<PluginDependency>()
        
        // 解析 build/plugins 中的插件（effective model 已经应用了 pluginManagement 和继承）
        model.build?.plugins?.forEach { plugin ->
            convertPlugin(plugin)?.let { plugins.add(it) }
        }
        
        // 解析 build/pluginManagement/plugins 中的插件（如果它们被引用但没有在 plugins 中显式声明）
        // 注意：effective model 通常已经将 pluginManagement 中的插件合并到 plugins 中，
        // 但为了完整性，我们也检查一下 pluginManagement
        model.build?.pluginManagement?.plugins?.forEach { plugin ->
            convertPlugin(plugin)?.let { plugins.add(it) }
        }
        
        return plugins.toList()
    }
    
    /**
     * 转换 Maven Plugin 为 PluginDependency
     */
    private fun convertPlugin(plugin: Plugin): PluginDependency? {
        val groupId = plugin.groupId ?: "org.apache.maven.plugins"
        val artifactId = plugin.artifactId
        val version = plugin.version
        
        if (artifactId.isNullOrBlank() || version.isNullOrBlank()) {
            logger.debug("插件信息不完整，跳过: groupId=$groupId, artifactId=$artifactId, version=$version")
            return null
        }
        
        return PluginDependency(
            groupId = groupId,
            artifactId = artifactId,
            version = version
        )
    }
    
    /**
     * 根据 GAV 信息构建本地 Maven 仓库中的 POM 文件路径
     */
    fun buildLocalPomPath(groupId: String, artifactId: String, version: String): File {
        val localRepoPath = getLocalMavenRepositoryPath()
        val pomPath = File(localRepoPath,
            "${groupId.replace('.', '/')}/$artifactId/$version/$artifactId-$version.pom")
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
     * 即使本地文件不存在，也会创建 DependencyInfo 对象，以便在列表中显示该依赖
     */
    fun parentToDependencyInfo(parent: ParentInfo): DependencyInfo {
        val pomFile = buildLocalPomPath(parent.groupId, parent.artifactId, parent.version)
        val localPath = if (pomFile.exists()) {
            pomFile.absolutePath
        } else {
            logger.warn("父 POM 文件不存在: ${pomFile.absolutePath}，但仍会添加到依赖列表中")
            "" // 本地文件不存在时，localPath 为空
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
     * 将 BomDependency 转换为 DependencyInfo
     * 即使本地文件不存在，也会创建 DependencyInfo 对象，以便在列表中显示该依赖
     */
    fun bomToDependencyInfo(bom: BomDependency): DependencyInfo {
        val pomFile = buildLocalPomPath(bom.groupId, bom.artifactId, bom.version)
        val localPath = if (pomFile.exists()) {
            pomFile.absolutePath
        } else {
            logger.warn("BOM POM 文件不存在: ${pomFile.absolutePath}，但仍会添加到依赖列表中")
            "" // 本地文件不存在时，localPath 为空
        }
        
        return DependencyInfo(
            groupId = bom.groupId,
            artifactId = bom.artifactId,
            version = bom.version,
            packaging = "pom",
            localPath = localPath,
            checkStatus = com.maven.privateuploader.model.CheckStatus.UNKNOWN,
            selected = false,
            errorMessage = if (localPath.isEmpty()) "本地文件不存在" else ""
        )
    }
    
    /**
     * 将 TransitiveDependency 转换为 DependencyInfo
     * 即使本地文件不存在，也会创建 DependencyInfo 对象，以便在列表中显示该依赖
     */
    fun transitiveToDependencyInfo(transitive: TransitiveDependency): DependencyInfo? {
        // 如果 version 为空，无法构建路径，返回 null
        if (transitive.version.isNullOrBlank()) {
            logger.debug("传递依赖 ${transitive.groupId}:${transitive.artifactId} 没有版本信息，跳过")
            return null
        }
        
        val packaging = transitive.type ?: "jar"
        val localRepoPath = getLocalMavenRepositoryPath()
        
        // 根据 packaging 类型构建文件路径
        val localPath = when (packaging) {
            "pom" -> {
                val pomFile = File(localRepoPath,
                    "${transitive.groupId.replace('.', '/')}/${transitive.artifactId}/${transitive.version}/${transitive.artifactId}-${transitive.version}.pom")
                if (pomFile.exists()) pomFile.absolutePath else ""
            }
            else -> {
                // jar 或其他类型
                val jarFile = File(localRepoPath,
                    "${transitive.groupId.replace('.', '/')}/${transitive.artifactId}/${transitive.version}/${transitive.artifactId}-${transitive.version}.jar")
                val pomFile = File(localRepoPath,
                    "${transitive.groupId.replace('.', '/')}/${transitive.artifactId}/${transitive.version}/${transitive.artifactId}-${transitive.version}.pom")
                when {
                    jarFile.exists() -> jarFile.absolutePath
                    pomFile.exists() -> pomFile.absolutePath
                    else -> ""
                }
            }
        }
        
        if (localPath.isEmpty()) {
            logger.debug("传递依赖文件不存在: ${transitive.groupId}:${transitive.artifactId}:${transitive.version}")
        }
        
        return DependencyInfo(
            groupId = transitive.groupId,
            artifactId = transitive.artifactId,
            version = transitive.version,
            packaging = packaging,
            localPath = localPath,
            checkStatus = com.maven.privateuploader.model.CheckStatus.UNKNOWN,
            selected = false,
            errorMessage = if (localPath.isEmpty()) "本地文件不存在" else ""
        )
    }
    
    /**
     * 将 PluginDependency 转换为 DependencyInfo
     * Maven 插件的 packaging 是 maven-plugin，但实际文件是 jar
     * 即使本地文件不存在，也会创建 DependencyInfo 对象，以便在列表中显示该依赖
     */
    fun pluginToDependencyInfo(plugin: PluginDependency): DependencyInfo {
        // Maven 插件的文件路径：groupId/artifactId/version/artifactId-version.jar
        val localRepoPath = getLocalMavenRepositoryPath()
        val jarFile = File(localRepoPath,
            "${plugin.groupId.replace('.', '/')}/${plugin.artifactId}/${plugin.version}/${plugin.artifactId}-${plugin.version}.jar")
        val pomFile = File(localRepoPath,
            "${plugin.groupId.replace('.', '/')}/${plugin.artifactId}/${plugin.version}/${plugin.artifactId}-${plugin.version}.pom")
        
        // 优先使用 jar 文件路径，如果不存在则使用 pom 文件路径
        val localPath = when {
            jarFile.exists() -> jarFile.absolutePath
            pomFile.exists() -> pomFile.absolutePath
            else -> {
                logger.warn("插件文件不存在: ${jarFile.absolutePath}，但仍会添加到依赖列表中")
                "" // 本地文件不存在时，localPath 为空
            }
        }
        
        return DependencyInfo(
            groupId = plugin.groupId,
            artifactId = plugin.artifactId,
            version = plugin.version,
            packaging = "maven-plugin", // Maven 插件的 packaging 类型
            localPath = localPath,
            checkStatus = com.maven.privateuploader.model.CheckStatus.UNKNOWN,
            selected = false,
            errorMessage = if (localPath.isEmpty()) "本地文件不存在" else ""
        )
    }
}

