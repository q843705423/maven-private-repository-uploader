package com.maven.privateuploader.analyzer

import com.intellij.openapi.diagnostic.thisLogger
import java.io.File

/**
 * 依赖解析器
 * 对应原 Java 项目中的 DependencyResolver 类
 */
class DependencyResolver(private val localRepository: File = getDefaultLocalRepository()) {

    private val logger = thisLogger()

    fun resolve(dependency: org.apache.maven.model.Dependency): Gav {
        val groupId = dependency.groupId
        val artifactId = dependency.artifactId
        val version = dependency.version
        val scope = dependency.scope ?: "compile" // 默认作用域是compile
        var type = dependency.type

        // 如果 type 为 null，默认为 "jar"
        if (type.isNullOrEmpty()) {
            type = "jar"
        }

        // 详细日志：记录依赖解析过程
        logger.debug("解析依赖: $groupId:$artifactId:$version [scope: $scope, type: $type]")

        // 构建本地仓库路径
        val repoPath = groupId.replace('.', File.separatorChar) + File.separator +
                artifactId + File.separator +
                version + File.separator +
                artifactId + "-" + version + "." + type

        val artifactFile = File(localRepository, repoPath)
        val path = artifactFile.absolutePath

        // 检查文件是否存在
        val exists = artifactFile.exists()
        logger.debug("依赖文件: $path, 存在: $exists")

        // 特别关注runtime作用域的依赖
        if (scope == "runtime") {
            logger.info("Runtime作用域依赖解析: $groupId:$artifactId:$version -> $path")
        }

        return Gav(groupId, artifactId, version, type, path)
    }

    companion object {
        private fun getDefaultLocalRepository(): File {
            val m2Repo = System.getProperty("maven.repo.local")
            if (!m2Repo.isNullOrEmpty()) {
                return File(m2Repo)
            }
            val userHome = System.getProperty("user.home")
            return File(userHome, ".m2/repository")
        }
    }
}

