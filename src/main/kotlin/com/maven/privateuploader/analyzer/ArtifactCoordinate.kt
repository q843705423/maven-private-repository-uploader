package com.maven.privateuploader.analyzer

/**
 * Maven 构件坐标
 * 用于表示构建项目所需的所有 POM / JAR 坐标
 *
 * @param groupId 组ID
 * @param artifactId 构件ID
 * @param version 版本
 * @param packaging 打包类型（jar / pom / maven-plugin / war ...）
 * @param classifier 分类器（可为空）
 * @param scope 作用域（对 dependency 而言）
 * @param sourceType 来源类型（PARENT / DEPENDENCY / DEP_MANAGED / PLUGIN / PLUGIN_DEP / BOM / PROJECT）
 */
data class ArtifactCoordinate(
    val groupId: String,
    val artifactId: String,
    val version: String,
    val packaging: String = "jar",
    val classifier: String? = null,
    val scope: String? = null,
    val sourceType: String = "DEPENDENCY"
) {
    /**
     * 获取 GAV 坐标字符串
     */
    fun getGAV(): String = "$groupId:$artifactId:$version"

    /**
     * 获取完整的坐标字符串（包含 packaging）
     */
    fun getCoordinate(): String {
        return if (classifier != null) {
            "$groupId:$artifactId:$classifier:$version:$packaging"
        } else {
            "$groupId:$artifactId:$version:$packaging"
        }
    }

    /**
     * 获取唯一键（用于去重）
     */
    fun getKey(): String {
        return if (classifier != null) {
            "$groupId:$artifactId:$classifier:$version:$packaging"
        } else {
            "$groupId:$artifactId:$version:$packaging"
        }
    }

    override fun toString(): String = getCoordinate()
}

