package com.maven.privateuploader.analyzer

/**
 * GAV 坐标信息
 * 对应原 Java 项目中的 Gav record
 */
data class Gav(
    val groupId: String,
    val artifactId: String,
    val version: String,
    val type: String,
    val path: String
) {
    override fun toString(): String {
        return "$groupId:$artifactId:$version-$type"
    }
}

