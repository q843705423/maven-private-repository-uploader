package com.maven.privateuploader.analyzer

import com.intellij.openapi.diagnostic.thisLogger
import com.maven.privateuploader.model.DependencyInfo
import org.apache.maven.model.Model
import org.apache.maven.model.Parent
import org.apache.maven.model.Dependency
import org.apache.maven.model.Plugin
import org.w3c.dom.Document
import org.w3c.dom.Element
import org.w3c.dom.Node
import org.w3c.dom.NodeList
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/**
 * POM 解析器
 * 用于解析 POM 文件并提取父 POM 和 BOM 依赖信息
 * 
 * 已改造为使用 Maven 官方的 maven-model-builder 来构建有效 POM，
 * 保证和 Maven CLI 构建行为一致。
 */
class PomParser {
    
    private val logger = thisLogger()
    private val effectivePomResolver = EffectivePomResolver()
    
    // 保留 DOM 解析器用于向后兼容（某些场景可能需要读取原始 XML）
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
        val dependencies: List<TransitiveDependency>,
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
     * 传递依赖信息（dependencies 中的普通依赖）
     */
    data class TransitiveDependency(
        val groupId: String,
        val artifactId: String,
        val version: String?,
        val scope: String?,
        val type: String?
    )
    
    /**
     * Maven 插件信息（最终解析后的版本）
     */
    data class PluginDependency(
        val groupId: String,
        val artifactId: String,
        val version: String
    )
    
    /**
     * 原始插件声明（用于继承，保留未解析的版本表达式）
     */
    data class RawPluginDecl(
        val groupId: String?,
        val artifactId: String,
        val rawVersionExpr: String?,
        val sourcePom: File,
        val depth: Int = 0,
        val fromPluginManagement: Boolean = false
    )
    
    /**
     * 解析 POM 文件
     * 
     * 使用 Maven 官方的 maven-model-builder 来构建有效 POM，
     * 保证和 Maven CLI 构建行为一致。
     *
     * @param pomFile POM 文件路径
     * @param parsePlugins 是否解析插件（默认 true）。当为 false 时，只解析 properties 等基本信息，不解析插件
     * @return POM 信息，如果解析失败返回 null
     */
    fun parsePom(pomFile: File, parsePlugins: Boolean = true): PomInfo? {
        if (!pomFile.exists() || !pomFile.isFile) {
            logger.warn("POM 文件不存在: ${pomFile.absolutePath}")
            return null
        }
        
        return try {
            // 使用 Maven 官方的 ModelBuilder 构建有效 POM
            val effectiveModel = effectivePomResolver.buildEffectiveModel(pomFile, processPlugins = parsePlugins)
                ?: return null
            
            // 将有效 Model 转换为 PomInfo
            convertModelToPomInfo(effectiveModel, pomFile, parsePlugins)
        } catch (e: Exception) {
            logger.error("解析 POM 文件失败: ${pomFile.absolutePath}", e)
            null
        }
    }
    
    /**
     * 将 Maven Model 转换为 PomInfo
     */
    private fun convertModelToPomInfo(model: Model, pomFile: File, parsePlugins: Boolean): PomInfo {
        // 基本信息（effective model 已经处理完所有继承和属性替换）
        val groupId = model.groupId
        val artifactId = model.artifactId
        val version = model.version
        
        // 父 POM 信息
        val parent = model.parent?.let { convertParent(it) }
        
        // Properties（effective model 已经合并了所有父 POM 的属性）
        val properties = model.properties?.let { props ->
            props.stringPropertyNames().associateWith { props.getProperty(it) }
        } ?: emptyMap()
        
        // BOM 依赖（dependencyManagement 中 scope=import 的依赖）
        val bomDependencies = parseBomDependenciesFromModel(model)
        
        // 普通依赖（dependencies 中的依赖，effective model 已经应用了 dependencyManagement）
        val dependencies = model.dependencies?.mapNotNull { dep ->
            convertDependency(dep)
        } ?: emptyList()
        
        // 插件（effective model 已经应用了 pluginManagement 和继承）
        val plugins = if (parsePlugins) {
            parsePluginsFromModel(model)
        } else {
            emptyList()
        }
        
        logger.info("【POM解析】解析完成: $groupId:$artifactId:$version, " +
                "父POM=${parent?.let { "${it.groupId}:${it.artifactId}:${it.version}" } ?: "无"}, " +
                "BOM依赖=${bomDependencies.size}个, " +
                "普通依赖=${dependencies.size}个, " +
                "插件=${plugins.size}个")
        
        return PomInfo(
            groupId = groupId,
            artifactId = artifactId,
            version = version,
            parent = parent,
            bomDependencies = bomDependencies,
            dependencies = dependencies,
            plugins = plugins,
            properties = properties
        )
    }
    
    /**
     * 转换 Maven Parent 为 ParentInfo
     */
    private fun convertParent(parent: Parent): ParentInfo {
        return ParentInfo(
            groupId = parent.groupId ?: "",
            artifactId = parent.artifactId ?: "",
            version = parent.version ?: "",
            relativePath = parent.relativePath
        )
    }
    
    /**
     * 从有效 Model 中解析 BOM 依赖（dependencyManagement 中 scope=import 的依赖）
     */
    private fun parseBomDependenciesFromModel(model: Model): List<BomDependency> {
        val bomDependencies = mutableListOf<BomDependency>()
        
        model.dependencyManagement?.dependencies?.forEach { dep ->
            // 检查 scope 是否为 import
            if (dep.scope == "import") {
                // 检查 type 是否为 pom（BOM 必须是 pom 类型）
                val type = dep.type ?: "pom"
                if (type == "pom") {
                    val groupId = dep.groupId
                    val artifactId = dep.artifactId
                    val version = dep.version
                    
                    if (!groupId.isNullOrBlank() && !artifactId.isNullOrBlank() && !version.isNullOrBlank()) {
                        bomDependencies.add(
                            BomDependency(
                                groupId = groupId,
                                artifactId = artifactId,
                                version = version
                            )
                        )
                        logger.debug("发现 BOM 依赖: $groupId:$artifactId:$version")
                    }
                }
            }
        }
        
        return bomDependencies
    }
    
    /**
     * 转换 Maven Dependency 为 TransitiveDependency
     */
    private fun convertDependency(dep: Dependency): TransitiveDependency? {
        val groupId = dep.groupId
        val artifactId = dep.artifactId
        
        if (groupId.isNullOrBlank() || artifactId.isNullOrBlank()) {
            return null
        }
        
        return TransitiveDependency(
            groupId = groupId,
            artifactId = artifactId,
            version = dep.version,
            scope = dep.scope,
            type = dep.type
        )
    }
    
    /**
     * 从有效 Model 中解析插件
     */
    private fun parsePluginsFromModel(model: Model): List<PluginDependency> {
        val plugins = mutableSetOf<PluginDependency>()
        
        // 解析 build/plugins 中的插件（effective model 已经应用了 pluginManagement 和继承）
        model.build?.plugins?.forEach { plugin ->
            convertPlugin(plugin)?.let { plugins.add(it) }
        }
        
        // 解析 build/pluginManagement/plugins 中的插件（如果它们被引用但没有在 plugins 中显式声明）
        // 注意：effective model 通常已经将 pluginManagement 中的插件合并到 plugins 中，
        // 但为了完整性，我们也检查一下 pluginManagement
        model.build?.pluginManagement?.plugins?.forEach { plugin ->
            convertPlugin(plugin)?.let { plugins.add(it) }
        }
        
        return plugins.toList()
    }
    
    /**
     * 转换 Maven Plugin 为 PluginDependency
     */
    private fun convertPlugin(plugin: Plugin): PluginDependency? {
        val groupId = plugin.groupId ?: "org.apache.maven.plugins"
        val artifactId = plugin.artifactId
        val version = plugin.version
        
        if (artifactId.isNullOrBlank() || version.isNullOrBlank()) {
            logger.debug("插件信息不完整，跳过: groupId=$groupId, artifactId=$artifactId, version=$version")
            return null
        }
        
        return PluginDependency(
            groupId = groupId,
            artifactId = artifactId,
            version = version
        )
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
     * 子 POM 的属性会覆盖父 POM 的同名属性（符合 Maven 规则）
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
            // 只解析 properties，不解析插件，避免过早解析版本
            val parentPomInfo = parsePom(parentPomFile, parsePlugins = false)
            if (parentPomInfo != null) {
                // 合并属性：子 POM 的属性优先级更高（符合 Maven 规则）
                val merged = mutableMapOf<String, String>()
                merged.putAll(parentPomInfo.properties)  // 先添加父 POM 的属性
                merged.putAll(currentProperties)  // 然后添加子 POM 的属性，会覆盖父 POM 的同名属性
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
     * 解析普通依赖（dependencies 中的依赖）
     * 用于递归分析传递依赖的 parent 和 dependencies
     */
    private fun parseDependencies(document: Document, namespaceURI: String?, properties: Map<String, String>): List<TransitiveDependency> {
        val dependencies = mutableListOf<TransitiveDependency>()
        
        try {
            // 查找 dependencies 节点（不是 dependencyManagement）
            val dependenciesNode = getElementByTagName(document, "dependencies", namespaceURI) as? Element
                ?: return emptyList()
            
            // 查找所有 dependency
            val dependencyNodeList = getElementsByTagName(dependenciesNode, "dependency", namespaceURI)
            
            for (i in 0 until dependencyNodeList.length) {
                val dependencyNode = dependencyNodeList.item(i) as? Element
                    ?: continue
                
                val rawGroupId = getElementText(dependencyNode, "groupId", namespaceURI)
                val rawArtifactId = getElementText(dependencyNode, "artifactId", namespaceURI)
                val rawVersion = getElementText(dependencyNode, "version", namespaceURI)
                val scope = getElementText(dependencyNode, "scope", namespaceURI)
                val type = getElementText(dependencyNode, "type", namespaceURI)
                
                // 应用属性解析
                val groupId = resolveProperties(rawGroupId, properties)
                val artifactId = resolveProperties(rawArtifactId, properties)
                val version = resolveProperties(rawVersion, properties)
                
                if (groupId.isNullOrBlank() || artifactId.isNullOrBlank()) {
                    logger.debug("依赖信息不完整，跳过: groupId=$groupId, artifactId=$artifactId")
                    continue
                }
                
                // 注意：version 可能为空（可能从 parent 或 BOM 继承），但我们也需要记录这个依赖
                dependencies.add(
                    TransitiveDependency(
                        groupId = groupId,
                        artifactId = artifactId,
                        version = version,
                        scope = scope,
                        type = type
                    )
                )
                
                logger.debug("发现传递依赖: $groupId:$artifactId:${version ?: "未指定版本"} (scope=${scope ?: "compile"}, type=${type ?: "jar"})")
            }
        } catch (e: Exception) {
            logger.warn("解析普通依赖时发生错误", e)
        }
        
        return dependencies
    }
    
    /**
     * 解析父 POM 的原始插件声明（用于继承）
     * 只读取 groupId/artifactId/rawVersionExpr，不解析版本表达式
     */
    private fun parseParentPluginsForInheritance(parentPomFile: File, depth: Int): List<RawPluginDecl> {
        val rawPlugins = mutableListOf<RawPluginDecl>()
        
        try {
            val document = documentBuilder.parse(parentPomFile)
            document.documentElement.normalize()
            
            val namespaceURI = document.documentElement.namespaceURI
            
            // 查找 build 节点
            val buildNode = getElementByTagName(document, "build", namespaceURI) as? Element
            if (buildNode == null) {
                return rawPlugins
            }
            
            // 解析 build/plugins 中的插件
            val pluginsNode = getElementByTagName(buildNode, "plugins", namespaceURI) as? Element
            if (pluginsNode != null) {
                val pluginNodeList = getElementsByTagName(pluginsNode, "plugin", namespaceURI)
                for (i in 0 until pluginNodeList.length) {
                    val pluginNode = pluginNodeList.item(i) as? Element
                        ?: continue
                    parseRawPluginNode(pluginNode, namespaceURI, parentPomFile, depth, fromPluginManagement = false, rawPlugins)
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
                        parseRawPluginNode(pluginNode, namespaceURI, parentPomFile, depth, fromPluginManagement = true, rawPlugins)
                    }
                }
            }
        } catch (e: Exception) {
            logger.warn("解析父 POM 原始插件声明时发生错误: ${parentPomFile.absolutePath}", e)
        }
        
        return rawPlugins
    }
    
    /**
     * 解析单个原始插件节点（只读取原始值，不解析属性）
     */
    private fun parseRawPluginNode(
        pluginNode: Element,
        namespaceURI: String?,
        sourcePom: File,
        depth: Int,
        fromPluginManagement: Boolean,
        rawPlugins: MutableList<RawPluginDecl>
    ) {
        try {
            // 只读取原始文本，不做属性解析
            var rawGroupId = getElementText(pluginNode, "groupId", namespaceURI)
            val rawArtifactId = getElementText(pluginNode, "artifactId", namespaceURI)
            val rawVersionExpr = getElementText(pluginNode, "version", namespaceURI)
            
            if (rawArtifactId.isNullOrBlank()) {
                return
            }
            
            // 如果 groupId 为空，使用默认值（但这里保留为 null，后续处理时再决定）
            rawPlugins.add(
                RawPluginDecl(
                    groupId = rawGroupId,
                    artifactId = rawArtifactId,
                    rawVersionExpr = rawVersionExpr,
                    sourcePom = sourcePom,
                    depth = depth,
                    fromPluginManagement = fromPluginManagement
                )
            )
            
            logger.debug("【原始插件声明】解析到: ${rawGroupId ?: "org.apache.maven.plugins"}:$rawArtifactId, versionExpr=${rawVersionExpr ?: "未指定"}, depth=$depth, fromPluginManagement=$fromPluginManagement")
        } catch (e: Exception) {
            logger.warn("解析原始插件节点时发生错误", e)
        }
    }
    
    /**
     * 解析 Maven 插件（build/plugins 和 build/pluginManagement/plugins）
     * 如果父 POM 中定义了插件但子 POM 中没有显式定义，会继承父 POM 的插件配置，但使用子 POM 的 properties 来解析版本
     */
    private fun parsePlugins(document: Document, namespaceURI: String?, properties: Map<String, String>, parentRawPlugins: List<RawPluginDecl> = emptyList()): List<PluginDependency> {
        val plugins = mutableListOf<PluginDependency>()
        
        try {
            // 查找 build 节点
            val buildNode = getElementByTagName(document, "build", namespaceURI) as? Element
            if (buildNode == null) {
                // 如果没有 build 节点，只继承父 POM 的插件
                logger.info("【插件解析】当前 POM 没有 build 节点，只继承父 POM 的插件")
                inheritParentRawPlugins(parentRawPlugins, properties, plugins)
                return plugins
            }
            
            // 解析 build/plugins 中的插件
            val pluginsNode = getElementByTagName(buildNode, "plugins", namespaceURI) as? Element
            if (pluginsNode != null) {
                val pluginNodeList = getElementsByTagName(pluginsNode, "plugin", namespaceURI)
                logger.info("【插件解析】当前 POM build/plugins 中有 ${pluginNodeList.length} 个插件")
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
                    logger.info("【插件解析】当前 POM build/pluginManagement/plugins 中有 ${pluginNodeList.length} 个插件")
                    for (i in 0 until pluginNodeList.length) {
                        val pluginNode = pluginNodeList.item(i) as? Element
                            ?: continue
                        parsePluginNode(pluginNode, namespaceURI, properties, plugins)
                    }
                }
            }
            
            // 继承父 POM 的插件（如果子 POM 中没有显式定义）
            logger.info("【插件解析】开始继承父 POM 的插件，当前已解析 ${plugins.size} 个插件")
            inheritParentRawPlugins(parentRawPlugins, properties, plugins)
        } catch (e: Exception) {
            logger.warn("解析 Maven 插件时发生错误", e)
        }
        
        return plugins
    }
    
    /**
     * 继承父 POM 的原始插件声明
     * 使用 effective properties（已合并父+子）来解析版本表达式
     */
    private fun inheritParentRawPlugins(parentRawPlugins: List<RawPluginDecl>, effectiveProperties: Map<String, String>, plugins: MutableList<PluginDependency>) {
        parentRawPlugins.forEach { parentRawPlugin ->
            val groupId = parentRawPlugin.groupId ?: "org.apache.maven.plugins"
            
            // 检查子 POM 中是否已经定义了相同的插件（通过 groupId 和 artifactId 判断）
            val alreadyDefined = plugins.any { 
                it.groupId == groupId && it.artifactId == parentRawPlugin.artifactId 
            }
            
            if (alreadyDefined) {
                logger.info("【插件继承】插件 $groupId:${parentRawPlugin.artifactId} 已在子 POM 中定义，跳过继承")
                return@forEach
            }
            
            // 子 POM 中没有显式定义，继承父 POM 的插件配置
            // 使用 effective properties（已合并父+子，子覆盖父）来解析版本
            logger.info("【插件继承】尝试继承父 POM 插件: $groupId:${parentRawPlugin.artifactId}，原始 versionExpr=${parentRawPlugin.rawVersionExpr ?: "未指定"}，使用 effective properties 解析版本")
            
            // 优先从 properties 中查找版本（支持 ${maven-jar-plugin.version} 这种格式）
            val versionFromProperties = findPluginVersionFromProperties(groupId, parentRawPlugin.artifactId, effectiveProperties)
            
            // 如果 properties 中没有，尝试解析原始版本表达式
            val finalVersion = versionFromProperties ?: resolveProperties(parentRawPlugin.rawVersionExpr, effectiveProperties)
            
            if (finalVersion.isNullOrBlank()) {
                logger.warn("【插件继承】无法确定插件版本: $groupId:${parentRawPlugin.artifactId} (versionExpr=${parentRawPlugin.rawVersionExpr ?: "未指定"})")
                return@forEach
            }
            
            plugins.add(
                PluginDependency(
                    groupId = groupId,
                    artifactId = parentRawPlugin.artifactId,
                    version = finalVersion
                )
            )
            logger.info("【插件继承】继承父 POM 的插件: $groupId:${parentRawPlugin.artifactId}:$finalVersion (${if (versionFromProperties != null) "从 properties 中找到" else if (parentRawPlugin.rawVersionExpr != null) "解析版本表达式" else "使用默认"})")
        }
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
            var rawVersion = getElementText(pluginNode, "version", namespaceURI)
            
            // 如果 groupId 为空，使用默认值 org.apache.maven.plugins
            if (rawGroupId.isNullOrBlank()) {
                rawGroupId = "org.apache.maven.plugins"
            }
            
            // 应用属性解析
            val groupId = resolveProperties(rawGroupId, properties) ?: "org.apache.maven.plugins"
            val artifactId = resolveProperties(rawArtifactId, properties)
            
            // 如果插件没有显式指定 version，尝试从 properties 中查找
            if (rawVersion.isNullOrBlank() && !artifactId.isNullOrBlank()) {
                logger.info("【插件解析】插件 ${groupId}:${artifactId} 没有显式指定 version，从 properties 中查找")
                rawVersion = findPluginVersionFromProperties(groupId, artifactId, properties)
            }
            
            val version = resolveProperties(rawVersion, properties)
            
            if (artifactId.isNullOrBlank() || version.isNullOrBlank()) {
                logger.info("【插件解析】插件信息不完整，跳过: groupId=$groupId, artifactId=$artifactId, version=$version")
                return
            }
            
            logger.info("【插件解析】解析到插件: $groupId:$artifactId:$version (原始 version=${rawVersion ?: "未指定"})")
            
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
     * 从 properties 中查找插件版本
     * Maven 支持以下格式的属性名：
     * 1. ${groupId}:${artifactId}.version - 如 org.apache.maven.plugins:maven-jar-plugin.version
     * 2. ${artifactId}.version - 如 maven-jar-plugin.version
     * 3. plugin.${artifactId}.version - 某些情况下也可能使用
     */
    private fun findPluginVersionFromProperties(groupId: String, artifactId: String, properties: Map<String, String>): String? {
        logger.info("【插件版本查找】查找插件版本: groupId=$groupId, artifactId=$artifactId")
        
        // 尝试格式 1: ${groupId}:${artifactId}.version
        val key1 = "$groupId:$artifactId.version"
        properties[key1]?.let {
            logger.info("【插件版本查找】从 properties 中找到插件版本（格式1）: $key1 = $it")
            return it
        }
        
        // 尝试格式 2: ${artifactId}.version
        val key2 = "$artifactId.version"
        properties[key2]?.let {
            logger.info("【插件版本查找】从 properties 中找到插件版本（格式2）: $key2 = $it")
            return it
        }
        
        // 尝试格式 3: plugin.${artifactId}.version
        val key3 = "plugin.$artifactId.version"
        properties[key3]?.let {
            logger.info("【插件版本查找】从 properties 中找到插件版本（格式3）: $key3 = $it")
            return it
        }
        
        logger.info("【插件版本查找】未在 properties 中找到插件版本: groupId=$groupId, artifactId=$artifactId (尝试了 $key1, $key2, $key3)")
        return null
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
     * 将 TransitiveDependency 转换为 DependencyInfo
     * 即使本地文件不存在，也会创建 DependencyInfo 对象，以便在列表中显示该依赖
     */
    fun transitiveToDependencyInfo(transitive: TransitiveDependency): DependencyInfo? {
        // 如果 version 为空，无法构建路径，返回 null
        if (transitive.version.isNullOrBlank()) {
            logger.debug("传递依赖 ${transitive.groupId}:${transitive.artifactId} 没有版本信息，跳过")
            return null
        }
        
        val packaging = transitive.type ?: "jar"
        val localRepoPath = getLocalMavenRepositoryPath()
        
        // 根据 packaging 类型构建文件路径
        val localPath = when (packaging) {
            "pom" -> {
                val pomFile = File(localRepoPath,
                    "${transitive.groupId.replace('.', '/')}/${transitive.artifactId}/${transitive.version}/${transitive.artifactId}-${transitive.version}.pom")
                if (pomFile.exists()) pomFile.absolutePath else ""
            }
            else -> {
                // jar 或其他类型
                val jarFile = File(localRepoPath,
                    "${transitive.groupId.replace('.', '/')}/${transitive.artifactId}/${transitive.version}/${transitive.artifactId}-${transitive.version}.jar")
                val pomFile = File(localRepoPath,
                    "${transitive.groupId.replace('.', '/')}/${transitive.artifactId}/${transitive.version}/${transitive.artifactId}-${transitive.version}.pom")
                when {
                    jarFile.exists() -> jarFile.absolutePath
                    pomFile.exists() -> pomFile.absolutePath
                    else -> ""
                }
            }
        }
        
        if (localPath.isEmpty()) {
            logger.debug("传递依赖文件不存在: ${transitive.groupId}:${transitive.artifactId}:${transitive.version}")
        }
        
        return DependencyInfo(
            groupId = transitive.groupId,
            artifactId = transitive.artifactId,
            version = transitive.version,
            packaging = packaging,
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

