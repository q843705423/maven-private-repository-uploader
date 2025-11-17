package com.maven.privateuploader.analyzer

/**
 * 环境配置
 * 对应原 Java 项目中的 Env 类
 */
class Env(private val localRoot: String) {
    fun getRoot(): String {
        return localRoot
    }
}

