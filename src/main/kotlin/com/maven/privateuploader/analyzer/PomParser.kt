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
        val bomDependencies: List<BomDependency>
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
            
            val groupId = getElementText(document, "groupId", namespaceURI)
            val artifactId = getElementText(document, "artifactId", namespaceURI)
            val version = getElementText(document, "version", namespaceURI)
            
            val parent = parseParent(document, namespaceURI)
            val bomDependencies = parseBomDependencies(document, namespaceURI)
            
            PomInfo(
                groupId = groupId,
                artifactId = artifactId,
                version = version,
                parent = parent,
                bomDependencies = bomDependencies
            )
        } catch (e: Exception) {
            logger.error("解析 POM 文件失败: ${pomFile.absolutePath}", e)
            null
        }
    }
    
    /**
     * 解析父 POM 信息
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
     * 解析 dependencyManagement 中 scope=import 的 BOM 依赖
     */
    private fun parseBomDependencies(document: Document, namespaceURI: String?): List<BomDependency> {
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
                
                val groupId = getElementText(dependencyNode, "groupId", namespaceURI)
                val artifactId = getElementText(dependencyNode, "artifactId", namespaceURI)
                val version = getElementText(dependencyNode, "version", namespaceURI)
                
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
}

