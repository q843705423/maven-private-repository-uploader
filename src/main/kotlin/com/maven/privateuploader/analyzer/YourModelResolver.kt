package com.maven.privateuploader.analyzer

import org.apache.maven.model.Dependency
import org.apache.maven.model.Parent
import org.apache.maven.model.Repository
import org.apache.maven.model.building.FileModelSource
import org.apache.maven.model.building.ModelSource2
import org.apache.maven.model.building.DefaultModelBuilderFactory
import org.apache.maven.model.building.DefaultModelBuildingRequest
import org.apache.maven.model.building.ModelBuildingRequest
import org.apache.maven.model.resolution.InvalidRepositoryException
import org.apache.maven.model.resolution.ModelResolver
import org.apache.maven.model.resolution.UnresolvableModelException
import java.io.File
import com.intellij.openapi.diagnostic.thisLogger

/**
 * 自定义 ModelResolver
 * 对应原 Java 项目中的 YourModelResolver 类
 */
class YourModelResolver(
    private val root: String,
    private val gavCollector: GavCollector
) : ModelResolver {

    private var count = 0
    private val logger = thisLogger()
    private val resolvedPoms = mutableSetOf<String>() // 避免重复解析

    override fun resolveModel(groupId: String, artifactId: String, version: String): ModelSource2 {
        val pom = findPomInLocalRepository(groupId, artifactId, version)

        // 🔧 关键修复：递归解析传递依赖
        resolveTransitiveDependencies(pom)

        return FileModelSource(pom)
    }

    private fun resolveTransitiveDependencies(pomFile: File) {
        val key = "${pomFile.parentFile.name}-${pomFile.nameWithoutExtension}"
        if (resolvedPoms.contains(key) || !pomFile.exists()) {
            return
        }
        resolvedPoms.add(key)

        try {
            logger.info("🔍 解析传递依赖: ${pomFile.name}")

            val builder = DefaultModelBuilderFactory().newInstance()
            val req = DefaultModelBuildingRequest()
            req.pomFile = pomFile
            req.validationLevel = ModelBuildingRequest.VALIDATION_LEVEL_MINIMAL
            req.systemProperties = System.getProperties()
            req.modelResolver = this.newCopy()

            val result = builder.build(req)
            val model = result.effectiveModel

            // 递归解析所有依赖
            model.dependencies?.forEach { dep ->
                if (dep.groupId != null && dep.artifactId != null && dep.version != null) {
                    // 使用 DependencyResolver 解析 JAR 文件
                    val dependencyResolver = DependencyResolver()
                    val resolvedGav = dependencyResolver.resolve(dep)
                    gavCollector.add(resolvedGav)

                    logger.info("  📦 发现传递依赖: ${dep.groupId}:${dep.artifactId}:${dep.version} -> ${resolvedGav.path}")

                    // 特别关注 protobuf 依赖
                    if (dep.groupId!!.contains("protobuf")) {
                        logger.info("  🔥 发现 Protobuf 传递依赖！")
                    }

                    // 递归解析这个依赖的 POM（如果存在）
                    val depPomFile = findPomInLocalRepository(dep.groupId!!, dep.artifactId!!, dep.version!!)
                    if (depPomFile.exists() && depPomFile != pomFile) {
                        resolveTransitiveDependencies(depPomFile)
                    }
                }
            }

        } catch (e: Exception) {
            logger.warn("解析传递依赖失败: ${pomFile.name} - ${e.message}")
        }
    }

    private fun findPomInLocalRepository(groupId: String, artifactId: String, version: String): File {
        count++
        // 本地仓库路径（你指定的）
        val localRepo = File(root)

        // groupId -> 变成目录结构
        val groupPath = groupId.replace('.', File.separatorChar)

        // 拼出相对路径：groupId/artifactId/version/artifactId-version.pom
        val relativePath = groupPath +
                File.separator + artifactId +
                File.separator + version +
                File.separator + artifactId + "-" + version + ".pom"
        val file = File(localRepo, relativePath)
        gavCollector.add(Gav(groupId, artifactId, version, "pom", file.absolutePath))

        // 拼出完整的 POM 路径
        return file
    }

    override fun resolveModel(parent: Parent): ModelSource2 {
        val f = findPomInLocalRepository(parent.groupId, parent.artifactId, parent.version)
        resolveTransitiveDependencies(f)
        return FileModelSource(f)
    }

    override fun resolveModel(dependency: Dependency): ModelSource2 {
        val f = findPomInLocalRepository(dependency.groupId, dependency.artifactId, dependency.version)
        resolveTransitiveDependencies(f)
        return FileModelSource(f)
    }

    override fun addRepository(repository: Repository) {
        // 空实现
    }

    override fun addRepository(repository: Repository, replace: Boolean) {
        // 空实现
    }

    fun getCount(): Int {
        return count
    }

    override fun newCopy(): ModelResolver {
        // 创建新的实例，避免状态共享问题
        return YourModelResolver(root, gavCollector)
    }
}

