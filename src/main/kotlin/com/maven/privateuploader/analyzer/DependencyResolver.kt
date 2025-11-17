package com.maven.privateuploader.analyzer

import java.io.File

/**
 * 依赖解析器
 * 对应原 Java 项目中的 DependencyResolver 类
 */
class DependencyResolver(private val localRepository: File = getDefaultLocalRepository()) {
    
    fun resolve(dependency: org.apache.maven.model.Dependency): Gav {
        val groupId = dependency.groupId
        val artifactId = dependency.artifactId
        val version = dependency.version
        var type = dependency.type
        
        // 如果 type 为 null，默认为 "jar"
        if (type.isNullOrEmpty()) {
            type = "jar"
        }

        // 构建本地仓库路径
        val repoPath = groupId.replace('.', File.separatorChar) + File.separator +
                artifactId + File.separator +
                version + File.separator +
                artifactId + "-" + version + "." + type
        
        val artifactFile = File(localRepository, repoPath)
        val path = artifactFile.absolutePath

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

