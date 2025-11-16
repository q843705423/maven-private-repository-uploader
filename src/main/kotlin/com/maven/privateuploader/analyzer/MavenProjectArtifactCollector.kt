package com.maven.privateuploader.analyzer

import com.intellij.openapi.diagnostic.thisLogger
import com.maven.privateuploader.model.DependencyInfo
import org.apache.maven.model.Dependency
import org.apache.maven.model.Model
import org.apache.maven.model.Plugin
import java.io.File
import java.util.*

/**
 * Maven 项目构件收集器
 * 从多模块项目中收集所有构建所需的构件坐标
 */
class MavenProjectArtifactCollector {

    private val logger = thisLogger()
    private val modelUtils = MavenModelUtils()
    private val moduleScanner = MavenModuleScanner(modelUtils)

    /**
     * 收集项目构建所需的所有构件坐标
     * 
     * @param rootPom 根 POM 文件
     * @param expandPomArtifacts 是否递归展开 POM 类型的构件（parent POM、BOM 等），默认 true
     * @return 所有构件坐标集合
     */
    fun collect(
        rootPom: File,
        expandPomArtifacts: Boolean = true
    ): Set<ArtifactCoordinate> {
        val all = LinkedHashSet<ArtifactCoordinate>()

        // 1. 找出所有模块的 pom.xml
        val pomFiles = moduleScanner.collectAllModulePoms(rootPom)

        // 2. 遍历每个 POM，解析 effective model 并收集坐标
        for (pom in pomFiles) {
            try {
                val model = modelUtils.buildEffectiveModel(pom, processPlugins = true)
                if (model != null) {
                    collectFromModel(model, all)
                } else {
                    logger.warn("无法构建有效模型: ${pom.absolutePath}")
                }
            } catch (e: Exception) {
                logger.error("处理 POM 文件时发生错误: ${pom.absolutePath}", e)
            }
        }

        // 3. 可选：递归展开 POM 类型的构件（parent POM、BOM 等）
        if (expandPomArtifacts) {
            expandPomArtifacts(all)
        }

        logger.info("共收集到 ${all.size} 个构件坐标")
        return all
    }

    /**
     * 从 Model 中收集构件坐标
     */
    private fun collectFromModel(model: Model, all: MutableSet<ArtifactCoordinate>) {
        // a) 当前项目自身的坐标
        val groupId = model.groupId ?: model.parent?.groupId
        val version = model.version ?: model.parent?.version
        
        if (groupId != null && model.artifactId != null && version != null) {
            all.add(
                ArtifactCoordinate(
                    groupId = groupId,
                    artifactId = model.artifactId,
                    version = version,
                    packaging = model.packaging ?: "jar",
                    sourceType = "PROJECT"
                )
            )
        }

        // b) parent POM
        if (model.parent != null) {
            val parent = model.parent
            if (parent.groupId != null && parent.artifactId != null && parent.version != null) {
                all.add(
                    ArtifactCoordinate(
                        groupId = parent.groupId,
                        artifactId = parent.artifactId,
                        version = parent.version,
                        packaging = "pom",
                        sourceType = "PARENT"
                    )
                )
            }
        }

        // c) dependencies
        model.dependencies?.forEach { dep ->
            if (isValidDependency(dep)) {
                all.add(
                    ArtifactCoordinate(
                        groupId = dep.groupId ?: "",
                        artifactId = dep.artifactId ?: "",
                        version = dep.version ?: "",
                        packaging = dep.type ?: "jar",
                        classifier = dep.classifier,
                        scope = dep.scope,
                        sourceType = "DEPENDENCY"
                    )
                )
            }
        }

        // d) dependencyManagement（包括 BOM）
        model.dependencyManagement?.dependencies?.forEach { dep ->
            if (isValidDependency(dep)) {
                val sourceType = if (dep.scope == "import" && (dep.type ?: "pom") == "pom") {
                    "BOM"
                } else {
                    "DEP_MANAGED"
                }
                all.add(
                    ArtifactCoordinate(
                        groupId = dep.groupId ?: "",
                        artifactId = dep.artifactId ?: "",
                        version = dep.version ?: "",
                        packaging = dep.type ?: "jar",
                        classifier = dep.classifier,
                        scope = dep.scope,
                        sourceType = sourceType
                    )
                )
            }
        }

        // e) build.plugins 和 pluginManagement.plugins
        model.build?.plugins?.forEach { plugin ->
            collectPlugin(plugin, all, "PLUGIN")
        }
        model.build?.pluginManagement?.plugins?.forEach { plugin ->
            collectPlugin(plugin, all, "PLUGIN_MANAGED")
        }
    }

    /**
     * 收集插件及其依赖
     */
    private fun collectPlugin(plugin: Plugin, all: MutableSet<ArtifactCoordinate>, sourceType: String) {
        val groupId = plugin.groupId ?: "org.apache.maven.plugins"
        val artifactId = plugin.artifactId
        val version = plugin.version

        if (artifactId != null && version != null && !containsUnresolvedProperty(version)) {
            all.add(
                ArtifactCoordinate(
                    groupId = groupId,
                    artifactId = artifactId,
                    version = version,
                    packaging = "maven-plugin",
                    sourceType = sourceType
                )
            )

            // 插件自己的 dependencies
            plugin.dependencies?.forEach { dep ->
                if (isValidDependency(dep)) {
                    all.add(
                        ArtifactCoordinate(
                            groupId = dep.groupId ?: "",
                            artifactId = dep.artifactId ?: "",
                            version = dep.version ?: "",
                            packaging = dep.type ?: "jar",
                            classifier = dep.classifier,
                            scope = dep.scope,
                            sourceType = "PLUGIN_DEP"
                        )
                    )
                }
            }
        }
    }

    /**
     * 检查依赖是否有效
     */
    private fun isValidDependency(dep: Dependency): Boolean {
        val groupId = dep.groupId
        val artifactId = dep.artifactId
        val version = dep.version

        if (groupId.isNullOrBlank() || artifactId.isNullOrBlank()) {
            return false
        }

        // 如果版本为空或包含未解析的属性占位符，跳过
        if (version.isNullOrBlank() || containsUnresolvedProperty(version)) {
            return false
        }

        return true
    }

    /**
     * 检查字符串是否包含未解析的 Maven 属性占位符
     */
    private fun containsUnresolvedProperty(value: String): Boolean {
        return value.contains(Regex("\\$\\{[^}]+\\}"))
    }

    /**
     * 递归展开 POM 类型的构件（parent POM、BOM 等）
     */
    private fun expandPomArtifacts(all: MutableSet<ArtifactCoordinate>) {
        val visited = HashSet<String>()
        val stack = ArrayDeque<ArtifactCoordinate>()

        // 只挑出 packaging = pom 的坐标做递归
        all.filter { it.packaging == "pom" }.forEach { stack.push(it) }

        while (stack.isNotEmpty()) {
            val coord = stack.pop()
            val key = "${coord.groupId}:${coord.artifactId}:${coord.version}"

            if (!visited.add(key)) {
                continue
            }

            // 在本地仓库中查找 POM 文件
            val pomFile = resolvePomInLocalRepo(coord)
            if (pomFile == null || !pomFile.exists() || !pomFile.isFile) {
                logger.debug("本地仓库中不存在 POM: $key")
                continue
            }

            try {
                val model = modelUtils.buildEffectiveModel(pomFile, processPlugins = true)
                if (model != null) {
                    val before = all.size
                    collectFromModel(model, all)
                    val after = all.size

                    // 新增的 pom 坐标继续入栈递归
                    if (after > before) {
                        all.filter { it.packaging == "pom" && !visited.contains("${it.groupId}:${it.artifactId}:${it.version}") }
                            .forEach { stack.push(it) }
                    }
                }
            } catch (e: Exception) {
                logger.error("展开 POM 构件时发生错误: $key", e)
            }
        }
    }

    /**
     * 在本地仓库中解析 POM 文件路径
     */
    private fun resolvePomInLocalRepo(coord: ArtifactCoordinate): File? {
        val localRepoPath = getLocalMavenRepositoryPath()
        val pomPath = File(
            localRepoPath,
            "${coord.groupId.replace('.', '/')}/${coord.artifactId}/${coord.version}/${coord.artifactId}-${coord.version}.pom"
        )
        return pomPath
    }

    /**
     * 获取本地 Maven 仓库路径
     */
    private fun getLocalMavenRepositoryPath(): String {
        val mavenHome = System.getProperty("user.home")
        return System.getProperty("maven.repo.local", "$mavenHome/.m2/repository")
    }

    /**
     * 将 ArtifactCoordinate 转换为 DependencyInfo
     */
    fun toDependencyInfo(coord: ArtifactCoordinate): DependencyInfo {
        val localRepoPath = getLocalMavenRepositoryPath()
        val fileName = if (coord.packaging == "pom") {
            "${coord.artifactId}-${coord.version}.pom"
        } else {
            "${coord.artifactId}-${coord.version}.${coord.packaging}"
        }
        val localPath = File(
            localRepoPath,
            "${coord.groupId.replace('.', '/')}/${coord.artifactId}/${coord.version}/$fileName"
        )

        return DependencyInfo(
            groupId = coord.groupId,
            artifactId = coord.artifactId,
            version = coord.version,
            packaging = coord.packaging,
            localPath = if (localPath.exists()) localPath.absolutePath else "",
            checkStatus = com.maven.privateuploader.model.CheckStatus.UNKNOWN,
            selected = false,
            errorMessage = if (!localPath.exists()) "本地文件不存在" else ""
        )
    }
}

