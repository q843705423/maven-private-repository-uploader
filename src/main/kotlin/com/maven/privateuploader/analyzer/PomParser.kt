package com.maven.privateuploader.analyzer

import com.intellij.openapi.diagnostic.thisLogger
import com.maven.privateuploader.model.DependencyInfo
import org.w3c.dom.Document
import org.w3c.dom.Element
import org.w3c.dom.Node
import org.w3c.dom.NodeList
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/**
 * POM 解析器
 * 用于解析 POM 文件并提取父 POM 和 BOM 依赖信息
 */
class PomParser {
    
    private val logger = thisLogger()
    private val documentBuilderFactory = DocumentBuilderFactory.newInstance().apply {
        isNamespaceAware = true  // 启用命名空间感知
    }
    private val documentBuilder = documentBuilderFactory.newDocumentBuilder()
    
    /**
     * POM 信息数据类
     */
    data class PomInfo(
        val groupId: String?,
        val artifactId: String?,
        val version: String?,
        val parent: ParentInfo?,
        val bomDependencies: List<BomDependency>,
        val plugins: List<PluginDependency>,
        val properties: Map<String, String>
    )
    
    /**
     * 父 POM 信息
     */
    data class ParentInfo(
        val groupId: String,
        val artifactId: String,
        val version: String,
        val relativePath: String?
    )
    
    /**
     * BOM 依赖信息（dependencyManagement 中 scope=import 的依赖）
     */
    data class BomDependency(
        val groupId: String,
        val artifactId: String,
        val version: String
    )
    
    /**
     * Maven 插件信息
     */
    data class PluginDependency(
        val groupId: String,
        val artifactId: String,
        val version: String
    )
    
    /**
     * 解析 POM 文件
     *
     * @param pomFile POM 文件路径
     * @return POM 信息，如果解析失败返回 null
     */
    fun parsePom(pomFile: File): PomInfo? {
        if (!pomFile.exists() || !pomFile.isFile) {
            logger.warn("POM 文件不存在: ${pomFile.absolutePath}")
            return null
        }
        
        return try {
            val document = documentBuilder.parse(pomFile)
            document.documentElement.normalize()
            
            // 检测命名空间
            val namespaceURI = document.documentElement.namespaceURI
            logger.debug("POM 文件命名空间: $namespaceURI")
            
            // 解析基本信息（原始值，可能包含属性引用）
            val rawGroupId = getElementText(document, "groupId", namespaceURI)
            val rawArtifactId = getElementText(document, "artifactId", namespaceURI)
            val rawVersion = getElementText(document, "version", namespaceURI)
            
            // 解析父 POM（原始值，可能包含属性引用）
            val rawParent = parseParent(document, namespaceURI)
            
            // 先解析当前 POM 的 properties（用于解析父 POM 的 version）
            val currentProperties = parseProperties(document, namespaceURI)
            
            // 如果父 POM 的 version 包含属性引用，先解析它
            val resolvedParent = if (rawParent != null) {
                val resolvedVersion = resolveProperties(rawParent.version, currentProperties)
                if (resolvedVersion != null && resolvedVersion != rawParent.version) {
                    // 如果 version 被解析了，需要重新构建父 POM 路径
                    ParentInfo(
                        groupId = rawParent.groupId,
                        artifactId = rawParent.artifactId,
                        version = resolvedVersion,
                        relativePath = rawParent.relativePath
                    )
                } else {
                    rawParent
                }
            } else {
                null
            }
            
            // 合并父 POM 的属性（父 POM 的属性优先级更高）
            val allProperties = mergeParentProperties(currentProperties, resolvedParent)
            
            // 应用属性解析到基本信息
            val groupId = resolveProperties(rawGroupId, allProperties)
            val artifactId = resolveProperties(rawArtifactId, allProperties)
            val version = resolveProperties(rawVersion, allProperties)
            
            // 解析 BOM 依赖（需要应用属性解析）
            val bomDependencies = parseBomDependencies(document, namespaceURI, allProperties)
            
            // 解析 Maven 插件（需要应用属性解析）
            val plugins = parsePlugins(document, namespaceURI, allProperties)
            
            PomInfo(
                groupId = groupId,
                artifactId = artifactId,
                version = version,
                parent = resolvedParent,
                bomDependencies = bomDependencies,
                plugins = plugins,
                properties = allProperties
            )
        } catch (e: Exception) {
            logger.error("解析 POM 文件失败: ${pomFile.absolutePath}", e)
            null
        }
    }
    
    /**
     * 解析父 POM 信息
     * 注意：这个方法在 parsePom 中调用时，properties 还没有完全解析，所以这里只解析原始值
     * 属性解析会在 parsePom 的主流程中完成
     */
    private fun parseParent(document: Document, namespaceURI: String?): ParentInfo? {
        val parentNode = getElementByTagName(document, "parent", namespaceURI) as? Element
            ?: return null
        
        val groupId = getElementText(parentNode, "groupId", namespaceURI)
        val artifactId = getElementText(parentNode, "artifactId", namespaceURI)
        val version = getElementText(parentNode, "version", namespaceURI)
        val relativePath = getElementText(parentNode, "relativePath", namespaceURI)
        
        if (groupId.isNullOrBlank() || artifactId.isNullOrBlank() || version.isNullOrBlank()) {
            logger.warn("父 POM 信息不完整: groupId=$groupId, artifactId=$artifactId, version=$version")
            return null
        }
        
        logger.debug("成功解析父 POM: $groupId:$artifactId:$version")
        return ParentInfo(
            groupId = groupId,
            artifactId = artifactId,
            version = version,
            relativePath = relativePath
        )
    }
    
    /**
     * 解析 properties 节点
     */
    private fun parseProperties(document: Document, namespaceURI: String?): Map<String, String> {
        val properties = mutableMapOf<String, String>()
        
        try {
            val propertiesNode = getElementByTagName(document, "properties", namespaceURI) as? Element
                ?: return properties
            
            // 获取 properties 节点的所有子元素
            val childNodes = propertiesNode.childNodes
            for (i in 0 until childNodes.length) {
                val node = childNodes.item(i)
                if (node.nodeType == Node.ELEMENT_NODE) {
                    val element = node as Element
                    val propertyName = element.tagName
                    val propertyValue = element.textContent?.trim() ?: ""
                    if (propertyName.isNotBlank() && propertyValue.isNotBlank()) {
                        properties[propertyName] = propertyValue
                        logger.debug("解析属性: $propertyName = $propertyValue")
                    }
                }
            }
        } catch (e: Exception) {
            logger.warn("解析 properties 时发生错误", e)
        }
        
        return properties
    }
    
    /**
     * 合并父 POM 的属性
     * 父 POM 的属性会覆盖子 POM 的同名属性
     */
    private fun mergeParentProperties(
        currentProperties: Map<String, String>,
        parent: ParentInfo?
    ): Map<String, String> {
        if (parent == null) {
            return currentProperties
        }
        
        // 尝试加载父 POM 的属性
        val parentPomFile = buildLocalPomPath(parent.groupId, parent.artifactId, parent.version)
        if (!parentPomFile.exists() || !parentPomFile.isFile) {
            logger.debug("父 POM 文件不存在，无法继承属性: ${parentPomFile.absolutePath}")
            return currentProperties
        }
        
        try {
            val parentPomInfo = parsePom(parentPomFile)
            if (parentPomInfo != null) {
                // 合并属性：父 POM 的属性优先级更高
                val merged = mutableMapOf<String, String>()
                merged.putAll(currentProperties)
                merged.putAll(parentPomInfo.properties)
                logger.debug("合并父 POM 属性: 当前 ${currentProperties.size} 个，父 POM ${parentPomInfo.properties.size} 个，合并后 ${merged.size} 个")
                return merged
            }
        } catch (e: Exception) {
            logger.warn("解析父 POM 属性时发生错误: ${parentPomFile.absolutePath}", e)
        }
        
        return currentProperties
    }
    
    /**
     * 解析属性引用，将 ${property.name} 替换为实际值
     * 支持嵌套属性引用（如 ${project.version}）
     */
    private fun resolveProperties(text: String?, properties: Map<String, String>): String? {
        if (text.isNullOrBlank()) {
            return text
        }
        
        var result: String = text
        val propertyPattern = Regex("\\$\\{([^}]+)\\}")
        var maxIterations = 10  // 防止无限循环
        var changed = true
        
        while (changed && maxIterations > 0) {
            changed = false
            result = propertyPattern.replace(result) { matchResult ->
                val propertyName = matchResult.groupValues[1]
                val propertyValue = properties[propertyName]
                if (propertyValue != null) {
                    changed = true
                    propertyValue
                } else {
                    // 如果属性未找到，保留原始引用（可能是系统属性或其他）
                    logger.debug("未找到属性: $propertyName，保留原始引用")
                    matchResult.value
                }
            }
            maxIterations--
        }
        
        if (maxIterations == 0 && result.contains("\${")) {
            logger.warn("属性解析可能未完成，可能存在循环引用或未解析的属性: $result")
        }
        
        return result
    }
    
    /**
     * 解析 dependencyManagement 中 scope=import 的 BOM 依赖
     */
    private fun parseBomDependencies(document: Document, namespaceURI: String?, properties: Map<String, String>): List<BomDependency> {
        val bomDependencies = mutableListOf<BomDependency>()
        
        try {
            // 查找 dependencyManagement 节点
            val dependencyManagementNode = getElementByTagName(document, "dependencyManagement", namespaceURI) as? Element
                ?: return emptyList()
            
            // 查找所有 dependencies
            val dependenciesNodeList = getElementsByTagName(dependencyManagementNode, "dependency", namespaceURI)
            
            for (i in 0 until dependenciesNodeList.length) {
                val dependencyNode = dependenciesNodeList.item(i) as? Element
                    ?: continue
                
                // 检查 scope 是否为 import
                val scope = getElementText(dependencyNode, "scope", namespaceURI)
                if (scope != "import") {
                    continue
                }
                
                // 检查 type 是否为 pom（BOM 必须是 pom 类型）
                // 如果 type 未指定，根据 Maven 规范，scope=import 时默认应该是 pom
                val type = getElementText(dependencyNode, "type", namespaceURI)
                if (type != null && type != "pom") {
                    logger.debug("跳过非 pom 类型的 import 依赖: type=$type")
                    continue
                }
                
                val rawGroupId = getElementText(dependencyNode, "groupId", namespaceURI)
                val rawArtifactId = getElementText(dependencyNode, "artifactId", namespaceURI)
                val rawVersion = getElementText(dependencyNode, "version", namespaceURI)
                
                // 应用属性解析
                val groupId = resolveProperties(rawGroupId, properties)
                val artifactId = resolveProperties(rawArtifactId, properties)
                val version = resolveProperties(rawVersion, properties)
                
                if (groupId.isNullOrBlank() || artifactId.isNullOrBlank() || version.isNullOrBlank()) {
                    logger.warn("BOM 依赖信息不完整: groupId=$groupId, artifactId=$artifactId, version=$version")
                    continue
                }
                
                bomDependencies.add(
                    BomDependency(
                        groupId = groupId,
                        artifactId = artifactId,
                        version = version
                    )
                )
                
                logger.debug("发现 BOM 依赖: $groupId:$artifactId:$version")
            }
        } catch (e: Exception) {
            logger.warn("解析 BOM 依赖时发生错误", e)
        }
        
        return bomDependencies
    }
    
    /**
     * 解析 Maven 插件（build/plugins 和 build/pluginManagement/plugins）
     */
    private fun parsePlugins(document: Document, namespaceURI: String?, properties: Map<String, String>): List<PluginDependency> {
        val plugins = mutableListOf<PluginDependency>()
        
        try {
            // 查找 build 节点
            val buildNode = getElementByTagName(document, "build", namespaceURI) as? Element
                ?: return emptyList()
            
            // 解析 build/plugins 中的插件
            val pluginsNode = getElementByTagName(buildNode, "plugins", namespaceURI) as? Element
            if (pluginsNode != null) {
                val pluginNodeList = getElementsByTagName(pluginsNode, "plugin", namespaceURI)
                for (i in 0 until pluginNodeList.length) {
                    val pluginNode = pluginNodeList.item(i) as? Element
                        ?: continue
                    parsePluginNode(pluginNode, namespaceURI, properties, plugins)
                }
            }
            
            // 解析 build/pluginManagement/plugins 中的插件
            val pluginManagementNode = getElementByTagName(buildNode, "pluginManagement", namespaceURI) as? Element
            if (pluginManagementNode != null) {
                val pluginManagementPluginsNode = getElementByTagName(pluginManagementNode, "plugins", namespaceURI) as? Element
                if (pluginManagementPluginsNode != null) {
                    val pluginNodeList = getElementsByTagName(pluginManagementPluginsNode, "plugin", namespaceURI)
                    for (i in 0 until pluginNodeList.length) {
                        val pluginNode = pluginNodeList.item(i) as? Element
                            ?: continue
                        parsePluginNode(pluginNode, namespaceURI, properties, plugins)
                    }
                }
            }
        } catch (e: Exception) {
            logger.warn("解析 Maven 插件时发生错误", e)
        }
        
        return plugins
    }
    
    /**
     * 解析单个插件节点
     */
    private fun parsePluginNode(pluginNode: Element, namespaceURI: String?, properties: Map<String, String>, plugins: MutableList<PluginDependency>) {
        try {
            // 解析插件的 groupId、artifactId、version
            // 注意：插件的 groupId 可能继承自父 POM 或使用默认值 org.apache.maven.plugins
            var rawGroupId = getElementText(pluginNode, "groupId", namespaceURI)
            val rawArtifactId = getElementText(pluginNode, "artifactId", namespaceURI)
            val rawVersion = getElementText(pluginNode, "version", namespaceURI)
            
            // 如果 groupId 为空，使用默认值 org.apache.maven.plugins
            if (rawGroupId.isNullOrBlank()) {
                rawGroupId = "org.apache.maven.plugins"
            }
            
            // 应用属性解析
            val groupId = resolveProperties(rawGroupId, properties) ?: "org.apache.maven.plugins"
            val artifactId = resolveProperties(rawArtifactId, properties)
            val version = resolveProperties(rawVersion, properties)
            
            if (artifactId.isNullOrBlank() || version.isNullOrBlank()) {
                logger.debug("插件信息不完整，跳过: groupId=$groupId, artifactId=$artifactId, version=$version")
                return
            }
            
            // 检查是否已存在（避免重复）
            val pluginKey = "$groupId:$artifactId:$version"
            if (plugins.any { it.groupId == groupId && it.artifactId == artifactId && it.version == version }) {
                logger.debug("插件 $pluginKey 已存在，跳过重复")
                return
            }
            
            plugins.add(
                PluginDependency(
                    groupId = groupId,
                    artifactId = artifactId,
                    version = version
                )
            )
            
            logger.debug("发现 Maven 插件: $groupId:$artifactId:$version")
            
            // 解析插件中的依赖（plugin/dependencies/dependency）
            val pluginDependenciesNode = getElementByTagName(pluginNode, "dependencies", namespaceURI) as? Element
            if (pluginDependenciesNode != null) {
                val dependencyNodeList = getElementsByTagName(pluginDependenciesNode, "dependency", namespaceURI)
                for (i in 0 until dependencyNodeList.length) {
                    val dependencyNode = dependencyNodeList.item(i) as? Element
                        ?: continue
                    parsePluginDependencyNode(dependencyNode, namespaceURI, properties, plugins)
                }
            }
        } catch (e: Exception) {
            logger.warn("解析插件节点时发生错误", e)
        }
    }
    
    /**
     * 解析插件中的依赖节点
     */
    private fun parsePluginDependencyNode(dependencyNode: Element, namespaceURI: String?, properties: Map<String, String>, plugins: MutableList<PluginDependency>) {
        try {
            val rawGroupId = getElementText(dependencyNode, "groupId", namespaceURI)
            val rawArtifactId = getElementText(dependencyNode, "artifactId", namespaceURI)
            val rawVersion = getElementText(dependencyNode, "version", namespaceURI)
            
            // 应用属性解析
            val groupId = resolveProperties(rawGroupId, properties)
            val artifactId = resolveProperties(rawArtifactId, properties)
            val version = resolveProperties(rawVersion, properties)
            
            if (groupId.isNullOrBlank() || artifactId.isNullOrBlank() || version.isNullOrBlank()) {
                logger.debug("插件依赖信息不完整，跳过: groupId=$groupId, artifactId=$artifactId, version=$version")
                return
            }
            
            // 检查是否已存在（避免重复）
            val pluginKey = "$groupId:$artifactId:$version"
            if (plugins.any { it.groupId == groupId && it.artifactId == artifactId && it.version == version }) {
                logger.debug("插件依赖 $pluginKey 已存在，跳过重复")
                return
            }
            
            plugins.add(
                PluginDependency(
                    groupId = groupId,
                    artifactId = artifactId,
                    version = version
                )
            )
            
            logger.debug("发现插件依赖: $groupId:$artifactId:$version")
        } catch (e: Exception) {
            logger.warn("解析插件依赖节点时发生错误", e)
        }
    }
    
    /**
     * 从 Document 中获取元素（支持命名空间）
     */
    private fun getElementByTagName(document: Document, tagName: String, namespaceURI: String?): Node? {
        return if (namespaceURI != null) {
            document.getElementsByTagNameNS(namespaceURI, tagName).item(0)
                ?: document.getElementsByTagName(tagName).item(0)  // 回退到无命名空间查找
        } else {
            document.getElementsByTagName(tagName).item(0)
        }
    }
    
    /**
     * 从 Element 中获取子元素（支持命名空间）
     */
    private fun getElementByTagName(element: Element, tagName: String, namespaceURI: String?): Node? {
        val nodeList = getElementsByTagName(element, tagName, namespaceURI)
        return if (nodeList.length > 0) {
            nodeList.item(0)
        } else {
            null
        }
    }
    
    /**
     * 从 Element 中获取元素列表（支持命名空间）
     */
    private fun getElementsByTagName(element: Element, tagName: String, namespaceURI: String?): NodeList {
        return if (namespaceURI != null) {
            val nsList = element.getElementsByTagNameNS(namespaceURI, tagName)
            if (nsList.length > 0) {
                nsList
            } else {
                element.getElementsByTagName(tagName)  // 回退到无命名空间查找
            }
        } else {
            element.getElementsByTagName(tagName)
        }
    }
    
    /**
     * 从 Document 中获取元素文本（支持命名空间）
     */
    private fun getElementText(document: Document, tagName: String, namespaceURI: String?): String? {
        val element = getElementByTagName(document, tagName, namespaceURI)
        return element?.textContent?.trim()?.takeIf { it.isNotBlank() }
    }
    
    /**
     * 从 Element 中获取子元素文本（支持命名空间）
     */
    private fun getElementText(element: Element, tagName: String, namespaceURI: String?): String? {
        val nodeList = getElementsByTagName(element, tagName, namespaceURI)
        if (nodeList.length == 0) {
            return null
        }
        return nodeList.item(0).textContent?.trim()?.takeIf { it.isNotBlank() }
    }
    
    /**
     * 根据 GAV 信息构建本地 Maven 仓库中的 POM 文件路径
     */
    fun buildLocalPomPath(groupId: String, artifactId: String, version: String): File {
        val localRepoPath = getLocalMavenRepositoryPath()
        val pomPath = File(localRepoPath,
            "${groupId.replace('.', '/')}/$artifactId/$version/$artifactId-$version.pom")
        return pomPath
    }
    
    /**
     * 获取本地 Maven 仓库路径
     */
    private fun getLocalMavenRepositoryPath(): String {
        val mavenHome = System.getProperty("user.home")
        val mavenRepo = System.getProperty("maven.repo.local", "$mavenHome/.m2/repository")
        return File(mavenRepo).absolutePath
    }
    
    /**
     * 将 ParentInfo 转换为 DependencyInfo
     * 即使本地文件不存在，也会创建 DependencyInfo 对象，以便在列表中显示该依赖
     */
    fun parentToDependencyInfo(parent: ParentInfo): DependencyInfo {
        val pomFile = buildLocalPomPath(parent.groupId, parent.artifactId, parent.version)
        val localPath = if (pomFile.exists()) {
            pomFile.absolutePath
        } else {
            logger.warn("父 POM 文件不存在: ${pomFile.absolutePath}，但仍会添加到依赖列表中")
            "" // 本地文件不存在时，localPath 为空
        }
        
        return DependencyInfo(
            groupId = parent.groupId,
            artifactId = parent.artifactId,
            version = parent.version,
            packaging = "pom",
            localPath = localPath,
            checkStatus = com.maven.privateuploader.model.CheckStatus.UNKNOWN,
            selected = false,
            errorMessage = if (localPath.isEmpty()) "本地文件不存在" else ""
        )
    }
    
    /**
     * 将 BomDependency 转换为 DependencyInfo
     * 即使本地文件不存在，也会创建 DependencyInfo 对象，以便在列表中显示该依赖
     */
    fun bomToDependencyInfo(bom: BomDependency): DependencyInfo {
        val pomFile = buildLocalPomPath(bom.groupId, bom.artifactId, bom.version)
        val localPath = if (pomFile.exists()) {
            pomFile.absolutePath
        } else {
            logger.warn("BOM POM 文件不存在: ${pomFile.absolutePath}，但仍会添加到依赖列表中")
            "" // 本地文件不存在时，localPath 为空
        }
        
        return DependencyInfo(
            groupId = bom.groupId,
            artifactId = bom.artifactId,
            version = bom.version,
            packaging = "pom",
            localPath = localPath,
            checkStatus = com.maven.privateuploader.model.CheckStatus.UNKNOWN,
            selected = false,
            errorMessage = if (localPath.isEmpty()) "本地文件不存在" else ""
        )
    }
    
    /**
     * 将 PluginDependency 转换为 DependencyInfo
     * Maven 插件的 packaging 是 maven-plugin，但实际文件是 jar
     * 即使本地文件不存在，也会创建 DependencyInfo 对象，以便在列表中显示该依赖
     */
    fun pluginToDependencyInfo(plugin: PluginDependency): DependencyInfo {
        // Maven 插件的文件路径：groupId/artifactId/version/artifactId-version.jar
        val localRepoPath = getLocalMavenRepositoryPath()
        val jarFile = File(localRepoPath,
            "${plugin.groupId.replace('.', '/')}/${plugin.artifactId}/${plugin.version}/${plugin.artifactId}-${plugin.version}.jar")
        val pomFile = File(localRepoPath,
            "${plugin.groupId.replace('.', '/')}/${plugin.artifactId}/${plugin.version}/${plugin.artifactId}-${plugin.version}.pom")
        
        // 优先使用 jar 文件路径，如果不存在则使用 pom 文件路径
        val localPath = when {
            jarFile.exists() -> jarFile.absolutePath
            pomFile.exists() -> pomFile.absolutePath
            else -> {
                logger.warn("插件文件不存在: ${jarFile.absolutePath}，但仍会添加到依赖列表中")
                "" // 本地文件不存在时，localPath 为空
            }
        }
        
        return DependencyInfo(
            groupId = plugin.groupId,
            artifactId = plugin.artifactId,
            version = plugin.version,
            packaging = "maven-plugin", // Maven 插件的 packaging 类型
            localPath = localPath,
            checkStatus = com.maven.privateuploader.model.CheckStatus.UNKNOWN,
            selected = false,
            errorMessage = if (localPath.isEmpty()) "本地文件不存在" else ""
        )
    }
}

