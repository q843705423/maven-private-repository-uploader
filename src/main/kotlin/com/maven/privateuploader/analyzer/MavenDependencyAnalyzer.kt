package com.maven.privateuploader.analyzer

import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.project.Project
import com.maven.privateuploader.model.CheckStatus
import com.maven.privateuploader.model.DependencyInfo
import org.jetbrains.idea.maven.model.MavenArtifact
import org.jetbrains.idea.maven.project.MavenProject
import org.jetbrains.idea.maven.project.MavenProjectsManager
import java.io.File

/**
 * Maven依赖分析器
 * 负责分析当前项目中所有的Maven依赖（包括传递依赖）
 */
class MavenDependencyAnalyzer(private val project: Project) {

    private val logger = thisLogger()
    private val mavenProjectsManager = MavenProjectsManager.getInstance(project)

    /**
     * 分析项目中所有的Maven依赖
     *
     * @param progressIndicator 进度指示器
     * @return 依赖信息列表
     */
    fun analyzeDependencies(progressIndicator: ProgressIndicator? = null): List<DependencyInfo> {
        logger.info("=========================================")
        logger.info("【Maven依赖分析】开始分析Maven依赖...")
        logger.info("=========================================")

        val dependencies = mutableSetOf<DependencyInfo>()

        // 等待Maven项目完全加载（最多等待5秒，每次等待100ms）
        var allMavenProjects = mavenProjectsManager.projects
        var waitCount = 0
        val maxWaitCount = 50  // 5秒 = 50 * 100ms
        
        while (allMavenProjects.isEmpty() && waitCount < maxWaitCount) {
            logger.info("等待Maven项目加载... (${waitCount + 1}/$maxWaitCount)")
            Thread.sleep(100)
            allMavenProjects = mavenProjectsManager.projects
            waitCount++
        }
        
        if (allMavenProjects.isEmpty()) {
            logger.warn("等待超时，Maven项目列表仍为空")
        }

        val rootProjects = mavenProjectsManager.rootProjects

        logger.info("Maven项目信息:")
        logger.info("- 总项目数: ${allMavenProjects.size}")
        logger.info("- 根项目数: ${rootProjects.size}")
        logger.info("- Maven项目管理器状态: ${mavenProjectsManager.state}")

        // 记录所有项目的详细信息
        allMavenProjects.forEachIndexed { index, project ->
            logger.info("项目 $index: ${project.displayName} (${project.mavenId.groupId}:${project.mavenId.artifactId}:${project.mavenId.version})")
            logger.info("  路径: ${project.directory}")
            logger.info("  打包: ${project.packaging}")
            logger.info("  直接依赖数: ${project.dependencies.size}")
        }

        progressIndicator?.text = "分析Maven项目依赖..."
        progressIndicator?.isIndeterminate = false

        val totalProjects = allMavenProjects.size

        allMavenProjects.forEachIndexed { index, mavenProject ->
            progressIndicator?.fraction = index.toDouble() / totalProjects.toDouble()
            progressIndicator?.text2 = "正在处理模块: ${mavenProject.displayName}"

            logger.info("处理Maven项目 (${index + 1}/$totalProjects): ${mavenProject.displayName}")

            // 分析项目依赖
            analyzeProjectDependencies(mavenProject, dependencies)
        }

        progressIndicator?.fraction = 1.0
        progressIndicator?.text2 = "依赖分析完成"

        logger.info("=========================================")
        logger.info("【Maven依赖分析】依赖分析完成，共发现 ${dependencies.size} 个依赖")
        logger.info("=========================================")

        // 记录最终找到的依赖列表（前20个）
        dependencies.take(20).forEachIndexed { index, dep ->
            logger.info("依赖 $index: ${dep.getGAV()} -> ${dep.localPath}")
        }
        if (dependencies.size > 20) {
            logger.info("... 还有 ${dependencies.size - 20} 个依赖")
        }

        return dependencies.sortedBy { it.getGAV() }
    }

    /**
     * 分析单个Maven项目的依赖
     */
    private fun analyzeProjectDependencies(mavenProject: MavenProject, dependencies: MutableSet<DependencyInfo>) {
        try {
            logger.info("开始分析项目: ${mavenProject.displayName}")
            logger.info("项目路径: ${mavenProject.directory}")

            // 记录项目的基本信息
            logger.info("项目GAV: ${mavenProject.mavenId.groupId}:${mavenProject.mavenId.artifactId}:${mavenProject.mavenId.version}")

            // 分析父POM（递归分析父POM链）
            logger.info("【父POM分析】开始分析父POM链...")
            try {
                val analyzedParents = mutableSetOf<String>() // 用于避免循环依赖
                analyzeParentPomRecursive(mavenProject, dependencies, analyzedParents)
                logger.info("【父POM分析】父POM分析完成，当前依赖数: ${dependencies.size}")
            } catch (e: Exception) {
                logger.error("【父POM分析】分析父POM时发生异常，继续分析其他依赖", e)
                // 即使父POM分析失败，也继续分析其他依赖
            }

            // 分析直接依赖
            logger.info("【直接依赖分析】开始分析直接依赖，当前依赖数: ${dependencies.size}")
            logger.info("【直接依赖分析】发现 ${mavenProject.dependencies.size} 个直接依赖")
            var directDependencyCount = 0
            mavenProject.dependencies.forEachIndexed { index, dependency ->
                logger.debug("直接依赖 $index: ${dependency.groupId}:${dependency.artifactId}:${dependency.version}, 文件: ${dependency.file}, 包装: ${dependency.packaging}")
                if (shouldIncludeDependency(dependency, mavenProject)) {
                    val dependencyInfo = createDependencyInfo(dependency)
                    dependencies.add(dependencyInfo)
                    directDependencyCount++
                    logger.debug("添加直接依赖: ${dependencyInfo.getGAV()}")
                }
            }
            logger.info("【直接依赖分析】直接依赖分析完成，添加了 $directDependencyCount 个直接依赖，当前总依赖数: ${dependencies.size}")

            // 分析传递依赖树 - 这是关键！
            logger.info("【传递依赖分析】开始分析传递依赖树，当前依赖数: ${dependencies.size}")
            val dependencyCountBeforeTree = dependencies.size
            analyzeDependencyTree(mavenProject, dependencies)
            val dependencyCountAfterTree = dependencies.size
            logger.info("【传递依赖分析】传递依赖树分析完成，新增 ${dependencyCountAfterTree - dependencyCountBeforeTree} 个依赖，当前总依赖数: ${dependencies.size}")

            // 分析当前项目的 POM 文件中的插件
            logger.info("【插件分析】开始分析当前项目的插件，当前依赖数: ${dependencies.size}")
            try {
                val pomVirtualFile = mavenProject.file
                if (pomVirtualFile != null && pomVirtualFile.exists()) {
                    // 将 VirtualFile 转换为 File
                    val pomFile = File(pomVirtualFile.path)
                    if (pomFile.exists() && pomFile.isFile) {
                        val pomParser = PomParser()
                        val pomInfo = pomParser.parsePom(pomFile)
                        if (pomInfo != null && pomInfo.plugins.isNotEmpty()) {
                            logger.info("【插件分析】当前项目包含 ${pomInfo.plugins.size} 个插件，开始分析")
                            analyzePlugins(pomInfo.plugins, dependencies, pomParser)
                            logger.info("【插件分析】当前项目插件分析完成，当前总依赖数: ${dependencies.size}")
                        } else {
                            logger.debug("【插件分析】当前项目没有插件或解析失败")
                        }
                    } else {
                        logger.warn("【插件分析】当前项目的 POM 文件不存在或不是文件: ${pomFile.absolutePath}")
                    }
                } else {
                    logger.warn("【插件分析】当前项目的 POM 文件不存在: ${pomVirtualFile?.path}")
                }
            } catch (e: Exception) {
                logger.error("【插件分析】分析当前项目插件时发生错误", e)
            }

            logger.info("项目 ${mavenProject.displayName} 分析完成，当前总依赖数: ${dependencies.size}")

        } catch (e: Exception) {
            logger.error("分析项目 ${mavenProject.displayName} 依赖时发生错误", e)
        }
    }

    /**
     * 分析完整的依赖树（包括传递依赖）
     */
    private fun analyzeDependencyTree(mavenProject: MavenProject, dependencies: MutableSet<DependencyInfo>) {
        try {
            // 获取所有已解析的依赖（包括传递依赖）
            // 使用 getDeclaredDependencies 和 plugins 来获取依赖
            val allDependencies = mutableListOf<MavenArtifact>()

            // 添加声明的依赖
            allDependencies.addAll(mavenProject.dependencies)

            logger.info("依赖树包含 ${allDependencies.size} 个节点")

            allDependencies.forEachIndexed { index, dependency ->
                try {
                    logger.debug("依赖树节点 $index: ${dependency.groupId}:${dependency.artifactId}:${dependency.version}, 文件: ${dependency.file}, 范围: ${dependency.scope}")

                    // 过滤掉项目的自有模块和测试依赖
                    if (shouldIncludeDependency(dependency, mavenProject)) {
                        val dependencyInfo = createDependencyInfo(dependency)
                        dependencies.add(dependencyInfo)
                        logger.debug("添加依赖树依赖: ${dependencyInfo.getGAV()}")
                    }
                } catch (e: Exception) {
                    logger.warn("处理依赖树节点时出错: ${e.message}")
                }
            }

            // 尝试通过插件获取更多依赖
            try {
                mavenProject.plugins.forEach { plugin ->
                    try {
                        // 插件依赖的类型是MavenId，不是MavenArtifact，所以我们需要跳过这部分
                        // 或者我们可以记录插件信息，但不将其作为依赖上传
                        logger.debug("发现插件: ${plugin.groupId}:${plugin.artifactId}:${plugin.version}")
                    } catch (e: Exception) {
                        logger.debug("处理插件依赖时出错: ${e.message}")
                    }
                }
            } catch (e: Exception) {
                logger.warn("获取插件依赖时出错: ${e.message}")
            }

            logger.info("依赖树分析完成，总依赖数: ${dependencies.size}")

        } catch (e: Exception) {
            logger.error("分析依赖树时发生错误", e)
        }
    }

    /**
     * 判断是否应该包含此依赖
     */
    private fun shouldIncludeDependency(artifact: org.jetbrains.idea.maven.model.MavenArtifact, currentProject: MavenProject): Boolean {
        // 过滤条件
        if (artifact.file == null) {
            logger.debug("跳过无文件的依赖: ${artifact.groupId}:${artifact.artifactId}")
            return false
        }

        // 包含JAR包和POM包（父POM的packaging是pom）
        if (artifact.packaging != "jar" && artifact.packaging != "pom") {
            logger.debug("跳过非JAR/POM依赖: ${artifact.groupId}:${artifact.artifactId} (${artifact.packaging})")
            return false
        }

        // 跳过测试依赖（但POM类型的依赖不受scope限制）
        if (artifact.packaging == "jar" && (artifact.scope == "test" || artifact.scope == "provided")) {
            logger.debug("跳过测试/提供依赖: ${artifact.groupId}:${artifact.artifactId} (${artifact.scope})")
            return false
        }

        // 跳过项目自有模块
        if (isProjectModule(artifact)) {
            logger.debug("跳过项目自有模块: ${artifact.groupId}:${artifact.artifactId}")
            return false
        }

        return true
    }

    // 注意：这个方法现在不再需要，因为我们在 analyzeDependencyTree 中已经处理了插件依赖

    /**
     * 创建依赖信息对象
     */
    private fun createDependencyInfo(artifact: org.jetbrains.idea.maven.model.MavenArtifact): DependencyInfo {
        val localPath = artifact.file?.absolutePath ?: ""
        val packaging = artifact.packaging ?: "jar"

        return DependencyInfo(
            groupId = artifact.groupId,
            artifactId = artifact.artifactId,
            version = artifact.version,
            packaging = packaging,
            localPath = localPath,
            checkStatus = CheckStatus.UNKNOWN,
            selected = false
        ).apply {
            // 默认选择缺失的依赖（这个逻辑将在预检查后更新）
            selected = false
        }
    }

    /**
     * 判断是否为项目的模块
     */
    private fun isProjectModule(artifact: org.jetbrains.idea.maven.model.MavenArtifact): Boolean {
        val mavenProjects = mavenProjectsManager.projects
        return mavenProjects.any { mavenProject ->
            mavenProject.mavenId.groupId == artifact.groupId &&
            mavenProject.mavenId.artifactId == artifact.artifactId
        }
    }

    /**
     * 递归分析父POM链
     * 
     * @param mavenProject Maven项目
     * @param dependencies 依赖集合
     * @param analyzedParents 已分析的父POM集合（用于避免循环依赖）
     */
    private fun analyzeParentPomRecursive(
        mavenProject: MavenProject,
        dependencies: MutableSet<DependencyInfo>,
        analyzedParents: MutableSet<String>
    ) {
        try {
            // 获取父POM信息
            val parentId = mavenProject.parentId
            if (parentId != null) {
                val groupId = parentId.groupId
                val artifactId = parentId.artifactId
                val version = parentId.version
                
                if (groupId != null && artifactId != null && version != null &&
                    groupId.isNotBlank() && artifactId.isNotBlank() && version.isNotBlank()) {
                    
                    val parentKey = "$groupId:$artifactId:$version"
                    
                    // 检查是否已经分析过（避免循环依赖）
                    if (analyzedParents.contains(parentKey)) {
                        logger.debug("父POM $parentKey 已分析过，跳过（避免循环依赖）")
                        return
                    }
                    
                    logger.info("【父POM分析】发现父POM: $parentKey")
                    
                    // 构建父POM的本地路径
                    val localRepoPath = getLocalMavenRepositoryPath()
                    val parentPomPath = File(localRepoPath,
                        "${groupId.replace('.', '/')}/$artifactId/$version/$artifactId-$version.pom")
                    
                    if (parentPomPath.exists() && parentPomPath.isFile) {
                        val parentDependency = DependencyInfo(
                            groupId = groupId,
                            artifactId = artifactId,
                            version = version,
                            packaging = "pom",
                            localPath = parentPomPath.absolutePath,
                            checkStatus = CheckStatus.UNKNOWN,
                            selected = false
                        )
                        dependencies.add(parentDependency)
                        analyzedParents.add(parentKey)
                        logger.info("【父POM分析】添加父POM: ${parentDependency.getGAV()} (${parentPomPath.absolutePath})")
                        
                        // 递归分析父POM的父POM和BOM依赖
                        val pomParser = PomParser()
                        val pomInfo = pomParser.parsePom(parentPomPath)
                        if (pomInfo != null) {
                            // 处理父POM的父POM
                            if (pomInfo.parent != null) {
                                logger.info("【父POM分析】父POM ${parentDependency.getGAV()} 还有父POM: ${pomInfo.parent.groupId}:${pomInfo.parent.artifactId}:${pomInfo.parent.version}，开始递归分析")
                                val parentParentDependency = pomParser.parentToDependencyInfo(pomInfo.parent)
                                
                                // 添加父POM的父POM到依赖列表（即使本地文件不存在）
                                val parentParentKey = "${parentParentDependency.groupId}:${parentParentDependency.artifactId}:${parentParentDependency.version}"
                                if (!analyzedParents.contains(parentParentKey)) {
                                    dependencies.add(parentParentDependency)
                                    analyzedParents.add(parentParentKey)
                                    logger.info("【父POM分析】添加父POM的父POM: ${parentParentDependency.getGAV()} (本地文件${if (parentParentDependency.localPath.isEmpty()) "不存在" else "存在"})")
                                }
                                
                                // 如果本地文件存在，继续递归分析
                                if (parentParentDependency.localPath.isNotEmpty()) {
                                    analyzeParentPomFromFile(parentParentDependency.localPath, dependencies, analyzedParents)
                                } else {
                                    logger.info("【父POM分析】父POM ${parentParentDependency.getGAV()} 本地文件不存在，无法继续递归分析")
                                }
                            } else {
                                logger.debug("父POM ${parentDependency.getGAV()} 没有父POM，递归结束")
                            }
                            
                            // 处理父POM中的BOM依赖
                            if (pomInfo.bomDependencies.isNotEmpty()) {
                                logger.info("【BOM分析】父POM ${parentDependency.getGAV()} 包含 ${pomInfo.bomDependencies.size} 个BOM依赖，开始分析")
                                analyzeBomDependencies(pomInfo.bomDependencies, dependencies, analyzedParents, pomParser)
                            }
                            
                            // 不处理父POM中的插件
                            // 原因：按照 Maven 规则，子 POM 的 properties 会覆盖父 POM 的 properties
                            // 所以插件的版本应该从子 POM 的 properties 中获取（已经合并了父 POM 的 properties）
                            // 如果解析父 POM 的插件，会使用父 POM 自己的 properties，导致版本不正确
                            // 只解析当前 POM 的插件即可，因为子 POM 的插件配置会继承父 POM 的，但使用子 POM 的 properties
                        }
                    } else {
                        logger.warn("父POM文件不存在: ${parentPomPath.absolutePath}")
                    }
                } else {
                    logger.debug("父POM信息不完整")
                }
            } else {
                logger.debug("项目没有父POM")
            }
        } catch (e: Exception) {
            logger.error("分析父POM时发生错误", e)
        }
    }
    
    /**
     * 从POM文件路径递归分析父POM链
     * 
     * @param pomFilePath POM文件路径
     * @param dependencies 依赖集合
     * @param analyzedParents 已分析的父POM集合（用于避免循环依赖）
     */
    private fun analyzeParentPomFromFile(
        pomFilePath: String,
        dependencies: MutableSet<DependencyInfo>,
        analyzedParents: MutableSet<String>
    ) {
        try {
            val pomFile = File(pomFilePath)
            if (!pomFile.exists() || !pomFile.isFile) {
                logger.warn("POM文件不存在: ${pomFile.absolutePath}")
                return
            }
            
            val pomParser = PomParser()
            val pomInfo = pomParser.parsePom(pomFile)
            
            if (pomInfo == null) {
                logger.warn("无法解析POM文件: ${pomFile.absolutePath}")
                return
            }
            
            // 添加当前POM到依赖列表（如果还没有添加）
            // 检查 groupId、artifactId、version 是否完整
            val groupId = pomInfo.groupId
            val artifactId = pomInfo.artifactId
            val version = pomInfo.version
            
            if (groupId.isNullOrBlank() || artifactId.isNullOrBlank() || version.isNullOrBlank()) {
                logger.warn("POM文件信息不完整，跳过: groupId=$groupId, artifactId=$artifactId, version=$version (${pomFile.absolutePath})")
                return
            }
            
            val currentKey = "$groupId:$artifactId:$version"
            if (!analyzedParents.contains(currentKey)) {
                val currentDependency = DependencyInfo(
                    groupId = groupId,
                    artifactId = artifactId,
                    version = version,
                    packaging = "pom",
                    localPath = pomFile.absolutePath,
                    checkStatus = CheckStatus.UNKNOWN,
                    selected = false
                )
                dependencies.add(currentDependency)
                analyzedParents.add(currentKey)
                logger.info("【父POM分析】添加父POM: ${currentDependency.getGAV()} (${pomFile.absolutePath})")
            } else {
                logger.debug("【父POM分析】父POM $currentKey 已分析过，跳过（避免循环依赖）")
            }
            
            // 递归分析父POM
            if (pomInfo.parent != null) {
                logger.info("【父POM分析】POM ${currentKey} 还有父POM: ${pomInfo.parent.groupId}:${pomInfo.parent.artifactId}:${pomInfo.parent.version}，开始递归分析")
                val parentDependency = pomParser.parentToDependencyInfo(pomInfo.parent)
                
                // 添加父POM到依赖列表（即使本地文件不存在）
                val parentKey = "${parentDependency.groupId}:${parentDependency.artifactId}:${parentDependency.version}"
                if (!analyzedParents.contains(parentKey)) {
                    dependencies.add(parentDependency)
                    analyzedParents.add(parentKey)
                    logger.info("【父POM分析】添加父POM: ${parentDependency.getGAV()} (本地文件${if (parentDependency.localPath.isEmpty()) "不存在" else "存在"})")
                }
                
                // 如果本地文件存在，继续递归分析
                if (parentDependency.localPath.isNotEmpty()) {
                    analyzeParentPomFromFile(parentDependency.localPath, dependencies, analyzedParents)
                } else {
                    logger.info("【父POM分析】父POM ${parentDependency.getGAV()} 本地文件不存在，无法继续递归分析")
                }
            } else {
                logger.debug("POM ${currentKey} 没有父POM，递归结束")
            }
            
            // 处理当前POM中的BOM依赖
            if (pomInfo.bomDependencies.isNotEmpty()) {
                logger.info("【BOM分析】POM ${currentKey} 包含 ${pomInfo.bomDependencies.size} 个BOM依赖，开始分析")
                analyzeBomDependencies(pomInfo.bomDependencies, dependencies, analyzedParents, pomParser)
            }
            
            // 不处理当前POM中的插件（因为这是递归解析父 POM 链的方法）
            // 插件的解析应该在 analyzeMavenProject 方法中完成，使用当前项目的 POM 文件
            // 这样可以确保使用当前项目的 properties（已经合并了父 POM 的 properties，但子 POM 的会覆盖父 POM 的）
        } catch (e: Exception) {
            logger.error("从文件分析父POM时发生错误: $pomFilePath", e)
        }
    }

    /**
     * 分析BOM依赖（递归处理BOM及其父POM和子BOM）
     * 
     * @param bomDependencies BOM依赖列表
     * @param dependencies 依赖集合
     * @param analyzedParents 已分析的父POM集合（用于避免循环依赖）
     * @param pomParser POM解析器
     */
    private fun analyzeBomDependencies(
        bomDependencies: List<PomParser.BomDependency>,
        dependencies: MutableSet<DependencyInfo>,
        analyzedParents: MutableSet<String>,
        pomParser: PomParser
    ) {
        bomDependencies.forEach { bom ->
            try {
                val bomDependency = pomParser.bomToDependencyInfo(bom)
                val bomKey = "${bomDependency.groupId}:${bomDependency.artifactId}:${bomDependency.version}"
                
                // 检查是否已经分析过（避免循环依赖）
                if (analyzedParents.contains(bomKey)) {
                    logger.debug("【BOM分析】BOM $bomKey 已分析过，跳过（避免循环依赖）")
                    return@forEach
                }
                
                // 添加BOM到依赖列表（即使本地文件不存在）
                dependencies.add(bomDependency)
                analyzedParents.add(bomKey)
                logger.info("【BOM分析】添加BOM依赖: ${bomDependency.getGAV()} (本地文件${if (bomDependency.localPath.isEmpty()) "不存在" else "存在"})")
                
                // 如果本地文件存在，递归分析BOM的父POM和子BOM
                if (bomDependency.localPath.isNotEmpty()) {
                    val bomPomFile = File(bomDependency.localPath)
                    if (bomPomFile.exists() && bomPomFile.isFile) {
                        val bomPomInfo = pomParser.parsePom(bomPomFile)
                        if (bomPomInfo != null) {
                            // 递归分析BOM的父POM
                            if (bomPomInfo.parent != null) {
                                logger.info("【BOM分析】BOM ${bomDependency.getGAV()} 还有父POM: ${bomPomInfo.parent.groupId}:${bomPomInfo.parent.artifactId}:${bomPomInfo.parent.version}，开始递归分析")
                                val bomParentDependency = pomParser.parentToDependencyInfo(bomPomInfo.parent)
                                
                                val bomParentKey = "${bomParentDependency.groupId}:${bomParentDependency.artifactId}:${bomParentDependency.version}"
                                if (!analyzedParents.contains(bomParentKey)) {
                                    dependencies.add(bomParentDependency)
                                    analyzedParents.add(bomParentKey)
                                    logger.info("【BOM分析】添加BOM的父POM: ${bomParentDependency.getGAV()} (本地文件${if (bomParentDependency.localPath.isEmpty()) "不存在" else "存在"})")
                                }
                                
                                // 如果本地文件存在，继续递归分析
                                if (bomParentDependency.localPath.isNotEmpty()) {
                                    analyzeParentPomFromFile(bomParentDependency.localPath, dependencies, analyzedParents)
                                }
                            }
                            
                            // 递归分析BOM中的子BOM依赖
                            if (bomPomInfo.bomDependencies.isNotEmpty()) {
                                logger.info("【BOM分析】BOM ${bomDependency.getGAV()} 包含 ${bomPomInfo.bomDependencies.size} 个子BOM依赖，开始递归分析")
                                analyzeBomDependencies(bomPomInfo.bomDependencies, dependencies, analyzedParents, pomParser)
                            }
                            
                            // 处理BOM中的插件
                            if (bomPomInfo.plugins.isNotEmpty()) {
                                logger.info("【插件分析】BOM ${bomDependency.getGAV()} 包含 ${bomPomInfo.plugins.size} 个插件，开始分析")
                                analyzePlugins(bomPomInfo.plugins, dependencies, pomParser)
                            }
                        }
                    }
                } else {
                    logger.info("【BOM分析】BOM ${bomDependency.getGAV()} 本地文件不存在，无法继续递归分析")
                }
            } catch (e: Exception) {
                logger.error("【BOM分析】分析BOM依赖 ${bom.groupId}:${bom.artifactId}:${bom.version} 时发生错误", e)
            }
        }
    }
    
    /**
     * 分析插件依赖
     * 
     * @param plugins 插件列表
     * @param dependencies 依赖集合
     * @param pomParser POM解析器
     */
    private fun analyzePlugins(
        plugins: List<PomParser.PluginDependency>,
        dependencies: MutableSet<DependencyInfo>,
        pomParser: PomParser
    ) {
        plugins.forEach { plugin ->
            try {
                val pluginDependency = pomParser.pluginToDependencyInfo(plugin)
                val pluginKey = "${pluginDependency.groupId}:${pluginDependency.artifactId}:${pluginDependency.version}"
                
                // 检查是否已经添加过相同版本的插件（避免重复）
                if (dependencies.any { 
                    it.groupId == pluginDependency.groupId && 
                    it.artifactId == pluginDependency.artifactId && 
                    it.version == pluginDependency.version 
                }) {
                    logger.debug("【插件分析】插件 $pluginKey 已存在，跳过重复")
                    return@forEach
                }
                
                // 检查是否已经存在同一个插件的不同版本
                // 根据 Maven 规则，子 POM 的配置会覆盖父 POM 的配置
                // 由于递归解析时子 POM 的插件会先被添加，所以如果已存在同一插件，说明是子 POM 的版本，应该保留子 POM 的版本
                val existingPlugin = dependencies.find { 
                    it.groupId == pluginDependency.groupId && 
                    it.artifactId == pluginDependency.artifactId
                }
                
                if (existingPlugin != null) {
                    // 已存在同一插件的不同版本，保留已存在的版本（子 POM 的版本）
                    logger.debug("【插件分析】插件 ${pluginDependency.groupId}:${pluginDependency.artifactId} 已存在版本 ${existingPlugin.version}，跳过新版本 ${pluginDependency.version}（保留子 POM 的版本）")
                    return@forEach
                }
                
                // 添加插件到依赖列表（即使本地文件不存在）
                dependencies.add(pluginDependency)
                logger.info("【插件分析】添加插件: ${pluginDependency.getGAV()} (本地文件${if (pluginDependency.localPath.isEmpty()) "不存在" else "存在"})")
            } catch (e: Exception) {
                logger.error("【插件分析】分析插件 ${plugin.groupId}:${plugin.artifactId}:${plugin.version} 时发生错误", e)
            }
        }
    }

    /**
     * 获取本地Maven仓库路径
     */
    private fun getLocalMavenRepositoryPath(): String {
        val mavenHome = System.getProperty("user.home")
        val mavenRepo = System.getProperty("maven.repo.local", "$mavenHome/.m2/repository")
        return File(mavenRepo).absolutePath
    }

    /**
     * 检查项目是否为Maven项目
     */
    fun isMavenProject(): Boolean {
        val hasProjects = mavenProjectsManager.hasProjects()

        logger.info("Maven项目检查结果:")
        logger.info("- hasProjects: $hasProjects")
        logger.info("- projects.size: ${mavenProjectsManager.projects.size}")
        logger.info("- rootProjects.size: ${mavenProjectsManager.rootProjects.size}")
        logger.info("- mavenProjectsManager state: ${mavenProjectsManager.state}")

        // 检查项目是否正确配置
        if (hasProjects) {
            val projects = mavenProjectsManager.projects
            projects.forEach { project ->
                logger.info("发现Maven项目: ${project.displayName}")
                logger.info("  Maven文件: ${project.file}")
                logger.info("  项目目录: ${project.directory}")
                logger.info("  是否已导入: ${mavenProjectsManager.isMavenizedProject()}")
            }
        } else {
            // 如果没有检测到Maven项目，尝试查找pom.xml文件
            logger.warn("未检测到Maven项目，尝试查找pom.xml文件...")
            try {
                val projectBase = project.basePath
                if (projectBase != null) {
                    val pomFile = java.io.File(projectBase, "pom.xml")
                    logger.info("项目根目录下的pom.xml存在: ${pomFile.exists()}")
                    if (pomFile.exists()) {
                        logger.info("pom.xml路径: ${pomFile.absolutePath}")
                        logger.info("pom.xml可读: ${pomFile.canRead()}")
                    }
                }
            } catch (e: Exception) {
                logger.error("检查pom.xml文件时出错", e)
            }
        }

        return hasProjects
    }

    /**
     * 获取所有Maven模块
     */
    fun getMavenModules(): List<MavenProject> {
        return mavenProjectsManager.projects
    }
}