package com.maven.privateuploader.model

/**
 * 私仓配置信息
 *
 * @param repositoryUrl 私仓URL
 * @param username 用户名
 * @param password 密码
 * @param repositoryId 仓库ID（可选，用于某些私仓系统）
 * @param enabled 是否启用
 */
data class RepositoryConfig(
    var repositoryUrl: String = "",
    var username: String = "",
    var password: String = "",
    var repositoryId: String = "",
    var enabled: Boolean = true
) {
    /**
     * 验证配置是否有效
     */
    fun isValid(): Boolean {
        return repositoryUrl.isNotBlank() && username.isNotBlank() && password.isNotBlank()
    }

    /**
     * 获取基础URL（去除末尾的斜杠）
     */
    fun getBaseUrl(): String {
        return repositoryUrl.trimEnd('/')
    }

    /**
     * 获取部署URL
     */
    fun getDeployUrl(): String {
        val baseUrl = getBaseUrl()
        return if (repositoryId.isNotBlank()) {
            "$baseUrl/repository/$repositoryId/"
        } else {
            "$baseUrl/"
        }
    }
}