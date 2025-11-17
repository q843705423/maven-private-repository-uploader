package com.maven.privateuploader.analyzer

import java.io.File

/**
 * 插件解析器
 * 对应原 Java 项目中的 PluginResolver 类
 */
class PluginResolver(private val localRepository: File = getDefaultLocalRepository()) {
    
    fun resolve(plugin: org.apache.maven.model.Plugin): Gav {
        var groupId = plugin.groupId
        val artifactId = plugin.artifactId
        val version = plugin.version
        
        // 如果 groupId 为 null，默认为 "org.apache.maven.plugins"
        if (groupId.isNullOrEmpty()) {
            groupId = "org.apache.maven.plugins"
        }

        // 插件通常是 jar 类型
        val type = "jar"

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

