package com.maven.privateuploader.model

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
    var errorMessage: String = ""
) {
    /**
     * 获取GAV坐标字符串
     */
    fun getGAV(): String = "$groupId:$artifactId:$version"

    /**
     * 获取完整的构件坐标
     */
    fun getCoordinate(): String = "$groupId:$artifactId:$version:$packaging"

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