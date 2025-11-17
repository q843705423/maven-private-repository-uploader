package com.maven.privateuploader.analyzer

import com.intellij.openapi.diagnostic.thisLogger
import com.maven.privateuploader.model.DependencyInfo
import com.maven.privateuploader.model.CheckStatus
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.StandardCopyOption

/**
 * GAV 收集器
 * 对应原 Java 项目中的 GavCollector 类
 * 同时提供转换为 DependencyInfo 的功能
 */
open class GavCollector {
    private val logger = thisLogger()
    private val gavs = mutableListOf<Gav>()
    private val keys = mutableSetOf<String>()

    open fun add(gav: Gav) {
        val key = gav.toString()
        if (keys.contains(key)) {
            logger.debug("GAV重复，跳过: $gav")
            return
        }

        // 记录收集到的GAV信息
        logger.debug("收集到GAV: ${gav.groupId}:${gav.artifactId}:${gav.version}:${gav.type}")

        // 特别关注数据库驱动等runtime依赖
        if (isRuntimeDependency(gav)) {
            logger.info("发现Runtime类型依赖: ${gav.groupId}:${gav.artifactId}:${gav.version} [path: ${gav.path}]")
        }

        keys.add(key)
        gavs.add(gav)
    }

    /**
     * 判断是否为常见的runtime类型依赖（如数据库驱动等）
     */
    private fun isRuntimeDependency(gav: Gav): Boolean {
        return (gav.groupId.contains("mysql") && gav.artifactId.contains("connector")) ||
               (gav.groupId.contains("postgresql") && gav.artifactId.contains("postgresql")) ||
               (gav.artifactId.contains("driver") || gav.artifactId.contains("connector")) ||
               (gav.groupId.contains("oracle") && gav.artifactId.contains("ojdbc")) ||
               (gav.groupId.contains("com.microsoft.sqlserver") && gav.artifactId.contains("mssql-jdbc"))
    }

    open fun show() {
        for (gav in gavs) {
            println(gav.path)
        }
    }

    open fun count(): Int {
        return gavs.size
    }

    fun getGavs(): List<Gav> {
        return ArrayList(gavs)
    }

    /**
     * 转换为 DependencyInfo 列表
     */
    fun toDependencyInfoList(): List<DependencyInfo> {
        logger.info("开始转换GAV列表为DependencyInfo，共 ${gavs.size} 个依赖")

        val result = gavs.map { gav ->
            logger.debug("转换GAV为DependencyInfo: ${gav.groupId}:${gav.artifactId}:${gav.version}")
            DependencyInfo(
                groupId = gav.groupId,
                artifactId = gav.artifactId,
                version = gav.version,
                packaging = gav.type,
                localPath = gav.path,
                checkStatus = CheckStatus.UNKNOWN,
                selected = false
            )
        }

        // 统计runtime类型依赖
        val runtimeDeps = result.filter { dep ->
            isRuntimeDependency(Gav(dep.groupId, dep.artifactId, dep.version, dep.packaging, dep.localPath))
        }

        logger.info("转换完成，其中可能的runtime依赖数量: ${runtimeDeps.size}")
        runtimeDeps.forEach { dep ->
            logger.info("Runtime依赖: ${dep.groupId}:${dep.artifactId}:${dep.version}")
        }

        return result
    }

    open fun translateTo(targetDir: File) {
        for (gav in gavs) {
            try {
                // 构建目标路径：groupId/artifactId/version/artifactId-version.type
                val groupIdPath = gav.groupId.replace(".", File.separator)
                val artifactId = gav.artifactId
                val version = gav.version
                val type = gav.type
                
                // 构建目标目录路径
                val absolutePath = targetDir.absolutePath
                if (absolutePath.isNullOrEmpty() || groupIdPath.isNullOrEmpty() || 
                    artifactId.isNullOrEmpty() || version.isNullOrEmpty()) {
                    println("==============================")
                    println(absolutePath)
                    println("==============================")
                    continue
                }
                val targetPath = Paths.get(absolutePath, groupIdPath, artifactId, version)
                
                // 创建目标目录
                Files.createDirectories(targetPath)
                
                // 构建目标文件名：artifactId-version.type
                val fileName = "$artifactId-$version.$type"
                val targetFile = targetPath.resolve(fileName)
                
                // 复制源文件到目标位置
                val sourceFile = File(gav.path)
                if (sourceFile.exists()) {
                    Files.copy(sourceFile.toPath(), targetFile, StandardCopyOption.REPLACE_EXISTING)
                }
            } catch (e: IOException) {
                System.err.println("Failed to copy file for $gav: ${e.message}")
            }
        }
    }
}

