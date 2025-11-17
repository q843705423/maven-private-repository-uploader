package com.maven.privateuploader.analyzer

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
    private val gavs = mutableListOf<Gav>()
    private val keys = mutableSetOf<String>()

    open fun add(gav: Gav) {
        val key = gav.toString()
        if (keys.contains(key)) {
            return
        }
        keys.add(key)
        gavs.add(gav)
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
        return gavs.map { gav ->
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

