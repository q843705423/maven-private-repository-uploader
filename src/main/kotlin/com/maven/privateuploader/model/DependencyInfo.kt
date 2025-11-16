package com.maven.privateuploader.model

import java.io.File

/**
 * Maven依赖信息数据类
 *
 * @param groupId 组ID
 * @param artifactId 构件ID
 * @param version 版本
 * @param packaging 打包类型（jar、war等）
 * @param localPath 本地仓库路径
 * @param existsInPrivateRepo 是否存在于私仓中
 * @param checkStatus 检查状态
 */
data class DependencyInfo(
    val groupId: String,
    val artifactId: String,
    val version: String,
    val packaging: String = "jar",
    val localPath: String = "",
    var existsInPrivateRepo: Boolean = false,
    var checkStatus: CheckStatus = CheckStatus.UNKNOWN,
    var selected: Boolean = false,
    var errorMessage: String = "",
    var stackTrace: String = "",
    var uploadUrl: String = ""
) {
    /**
     * 获取GAV坐标字符串
     */
    fun getGAV(): String = "$groupId:$artifactId:$version"

    /**
     * 获取完整的构件坐标
     */
    fun getCoordinate(): String = "$groupId:$artifactId:$version:$packaging"

    /**
     * 获取预期的本地文件路径（即使文件不存在也会返回预期路径）
     * 用于在UI中显示，即使文件不存在也能看到预期的路径位置
     */
    fun getExpectedLocalPath(): String {
        // 如果已有实际路径，直接返回
        if (localPath.isNotEmpty()) {
            return localPath
        }
        
        // 构建预期的本地仓库路径
        val localRepoPath = getLocalMavenRepositoryPath()
        val extension = if (packaging == "pom") "pom" else packaging
        val fileName = if (packaging == "pom") {
            "$artifactId-$version.pom"
        } else {
            "$artifactId-$version.$extension"
        }
        
        val expectedPath = File(localRepoPath,
            "${groupId.replace('.', '/')}/$artifactId/$version/$fileName")
        return expectedPath.absolutePath
    }
    
    /**
     * 检查本地文件是否实际存在
     */
    fun isLocalFileExists(): Boolean {
        return localPath.isNotEmpty() && File(localPath).exists()
    }
    
    /**
     * 获取本地 Maven 仓库路径
     */
    private fun getLocalMavenRepositoryPath(): String {
        val mavenHome = System.getProperty("user.home")
        val mavenRepo = System.getProperty("maven.repo.local", "$mavenHome/.m2/repository")
        return File(mavenRepo).absolutePath
    }

    override fun toString(): String = getGAV()

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is DependencyInfo) return false
        return groupId == other.groupId &&
               artifactId == other.artifactId &&
               version == other.version &&
               packaging == other.packaging
    }

    override fun hashCode(): Int {
        return listOf(groupId, artifactId, version, packaging).hashCode()
    }
}

/**
 * 依赖检查状态枚举
 */
enum class CheckStatus {
    /**
     * 未知状态（未检查）
     */
    UNKNOWN,

    /**
     * 已存在于私仓
     */
    EXISTS,

    /**
     * 在私仓中缺失
     */
    MISSING,

    /**
     * 检查失败
     */
    ERROR,

    /**
     * 正在检查中
     */
    CHECKING
}