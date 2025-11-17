package com.maven.privateuploader.analyzer

import org.apache.maven.model.Dependency
import org.apache.maven.model.Parent
import org.apache.maven.model.Repository
import org.apache.maven.model.building.FileModelSource
import org.apache.maven.model.building.ModelSource2
import org.apache.maven.model.resolution.InvalidRepositoryException
import org.apache.maven.model.resolution.ModelResolver
import org.apache.maven.model.resolution.UnresolvableModelException
import java.io.File

/**
 * 自定义 ModelResolver
 * 对应原 Java 项目中的 YourModelResolver 类
 */
class YourModelResolver(
    private val root: String,
    private val gavCollector: GavCollector
) : ModelResolver {
    
    private var count = 0

    override fun resolveModel(groupId: String, artifactId: String, version: String): ModelSource2 {
        val pom = findPomInLocalRepository(groupId, artifactId, version)
        return FileModelSource(pom)
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
        return FileModelSource(f)
    }

    override fun resolveModel(dependency: Dependency): ModelSource2 {
        val f = findPomInLocalRepository(dependency.groupId, dependency.artifactId, dependency.version)
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
        return this
    }
}

