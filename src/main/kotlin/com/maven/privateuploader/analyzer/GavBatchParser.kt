package com.maven.privateuploader.analyzer

import org.apache.maven.model.building.ModelBuildingException
import java.io.File
import java.util.*

/**
 * 批量 GAV 解析器
 * 对应原 Java 项目中的 GavBatchParser 类
 */
class GavBatchParser(private val env: Env) {

    private val gavParser = GavParser(env)
    private val localRepositoryRoot = env.getRoot()

    /**
     * 批量解析文件夹列表中的 pom 和 jar 文件
     *
     * @param folderPaths  文件夹路径列表
     * @param gavCollector 用于收集解析结果的 GAV 收集器
     * @throws ModelBuildingException 解析异常
     */
    fun parseBatch(folderPaths: List<String>, gavCollector: GavCollector) {
        // 用于存储待解析的文件（广度优先）
        val fileQueue: Queue<File> = LinkedList()
        // 用于记录已处理的文件，避免重复解析
        val processedFiles = mutableSetOf<String>()
        // 用于记录所有遇到的 groupId:artifactId 组合
        val gaKeys = mutableSetOf<String>()

        // 创建一个自定义的 GAV 收集器，用于跟踪 groupId:artifactId
        val trackingCollector = TrackingGavCollector(gavCollector, gaKeys)

        // 第一步：收集所有 pom 和 jar 文件（广度优先）
        val processedFolders = mutableSetOf<String>()
        for (folderPath in folderPaths) {
            // folderPath 可能是绝对路径或相对路径
            val folder = if (File(folderPath).isAbsolute) {
                File(folderPath)
            } else {
                File(env.getRoot(), folderPath)
            }
            if (!folder.exists() || !folder.isDirectory) {
                System.err.println("警告: 文件夹不存在或不是目录: $folderPath")
                continue
            }
            collectFilesBFS(folder, fileQueue, processedFolders)
        }

        // 第二步：解析所有收集到的 pom 文件
        while (fileQueue.isNotEmpty()) {
            val file = fileQueue.poll()
            val filePath = file.absolutePath

            if (processedFiles.contains(filePath)) {
                continue
            }
            processedFiles.add(filePath)

            try {
                if (file.name.endsWith(".pom")) {
                    // 直接解析 pom 文件
                    gavParser.parse(filePath, trackingCollector)
                } else if (file.name.endsWith(".jar")) {
                    // 对于 jar 文件，尝试找到对应的 pom 文件
                    val pomFile = findCorrespondingPom(file)
                    if (pomFile != null && pomFile.exists()) {
                        val pomPath = pomFile.absolutePath
                        if (!processedFiles.contains(pomPath)) {
                            gavParser.parse(pomPath, trackingCollector)
                            processedFiles.add(pomPath)
                        }
                    }
                }
            } catch (e: ModelBuildingException) {
                System.err.println("解析文件失败: $filePath, 错误: ${e.message}")
                // 继续处理下一个文件，不中断整个流程
            }
        }

        // 第三步：对于解析出的所有 GAV，查找该 groupId:artifactId 的所有版本
        expandAllVersions(gaKeys, gavCollector, processedFiles)
    }

    /**
     * 使用广度优先搜索收集文件夹中的所有 pom 和 jar 文件
     */
    private fun collectFilesBFS(rootFolder: File, fileQueue: Queue<File>, processedFolders: MutableSet<String>) {
        val folderQueue: Queue<File> = LinkedList()
        folderQueue.offer(rootFolder)
        processedFolders.add(rootFolder.absolutePath)

        while (folderQueue.isNotEmpty()) {
            val currentFolder = folderQueue.poll()

            val files = currentFolder.listFiles()
            if (files == null) {
                continue
            }

            for (file in files) {
                if (file.isDirectory) {
                    // 避免处理某些系统目录和隐藏目录
                    if (!file.name.startsWith(".") &&
                        file.name != "target" &&
                        file.name != "node_modules"
                    ) {
                        val folderPath = file.absolutePath
                        if (!processedFolders.contains(folderPath)) {
                            folderQueue.offer(file)
                            processedFolders.add(folderPath)
                        }
                    }
                } else if (file.isFile) {
                    val fileName = file.name.lowercase()
                    // 只收集 pom 和 jar 文件，避免误解析其他文件
                    if (fileName.endsWith(".pom") || fileName.endsWith(".jar")) {
                        fileQueue.offer(file)
                    }
                }
            }
        }
    }

    /**
     * 为 jar 文件查找对应的 pom 文件
     * 假设 jar 文件在 Maven 仓库结构中：groupId/artifactId/version/artifactId-version.jar
     * 对应的 pom 文件应该是：groupId/artifactId/version/artifactId-version.pom
     */
    private fun findCorrespondingPom(jarFile: File): File? {
        val jarPath = jarFile.absolutePath
        if (jarPath.endsWith(".jar")) {
            val pomPath = jarPath.substring(0, jarPath.length - 4) + ".pom"
            return File(pomPath)
        }
        return null
    }

    /**
     * 对于解析出的所有 GAV，查找该 groupId:artifactId 的所有版本
     */
    private fun expandAllVersions(gaKeys: Set<String>, gavCollector: GavCollector, processedFiles: MutableSet<String>) {
        if (gaKeys.isEmpty()) {
            return
        }

        val localRepo = File(localRepositoryRoot)
        if (!localRepo.exists() || !localRepo.isDirectory) {
            return
        }

        // 对于每个 groupId:artifactId 组合，查找所有版本
        for (gaKey in gaKeys) {
            val parts = gaKey.split(":")
            if (parts.size != 2) {
                continue
            }
            val groupId = parts[0]
            val artifactId = parts[1]

            // 构建本地仓库路径：groupId/artifactId/
            val groupPath = groupId.replace('.', File.separatorChar)
            val artifactDir = File(localRepo, groupPath + File.separator + artifactId)

            if (artifactDir.exists() && artifactDir.isDirectory) {
                // 查找该 artifact 的所有版本目录
                findAndParseAllVersionsForGA(artifactDir, gavCollector, processedFiles)
            }
        }
    }

    /**
     * 在指定 artifact 目录下查找并解析所有版本的 pom 文件
     */
    private fun findAndParseAllVersionsForGA(artifactDir: File, gavCollector: GavCollector, processedFiles: MutableSet<String>) {
        val versionDirs = artifactDir.listFiles()
        if (versionDirs == null) {
            return
        }

        for (versionDir in versionDirs) {
            if (!versionDir.isDirectory) {
                continue
            }

            // 查找该版本目录下的 pom 文件
            val files = versionDir.listFiles()
            if (files == null) {
                continue
            }

            for (file in files) {
                if (file.isFile && file.name.endsWith(".pom")) {
                    val pomPath = file.absolutePath
                    // 只解析尚未处理过的 pom 文件
                    if (!processedFiles.contains(pomPath)) {
                        try {
                            gavParser.parse(pomPath, gavCollector)
                            processedFiles.add(pomPath)
                        } catch (e: ModelBuildingException) {
                            // 忽略解析失败的文件，继续处理其他文件
                            System.err.println("解析版本扩展失败: $pomPath, 错误: ${e.message}")
                        }
                    }
                }
            }
        }
    }

    /**
     * 内部类：用于跟踪 groupId:artifactId 组合的 GAV 收集器
     * 继承 GavCollector 以便可以传递给 GavParser
     */
    private class TrackingGavCollector(
        private val delegate: GavCollector,
        private val gaKeys: MutableSet<String>
    ) : GavCollector() {
        
        override fun add(gav: Gav) {
            delegate.add(gav)
            // 记录 groupId:artifactId 组合
            val gaKey = "${gav.groupId}:${gav.artifactId}"
            gaKeys.add(gaKey)
        }

        override fun show() {
            delegate.show()
        }

        override fun count(): Int {
            return delegate.count()
        }

        override fun translateTo(targetDir: File) {
            delegate.translateTo(targetDir)
        }
    }
}

