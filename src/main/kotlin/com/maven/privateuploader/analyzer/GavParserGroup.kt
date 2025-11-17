package com.maven.privateuploader.analyzer

import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.progress.ProgressIndicator
import com.maven.privateuploader.model.DependencyInfo
import org.apache.maven.model.building.ModelBuildingException
import org.jetbrains.idea.maven.project.MavenProject
import java.io.File

/**
 * GAV 解析器组
 * 对应原 Java 项目中的 GavParserGroup 类
 * 适配到 IntelliJ Platform，用于替换 MavenDependencyAnalyzer
 */
class GavParserGroup(private val localRepositoryPath: String) {
    
    private val logger = thisLogger()
    
    /**
     * 获取所有依赖
     * 
     * @param pomPath 主 POM 文件路径
     * @param superPom 对于maven的插件的所有文件夹，需要进行特殊处理，确保不会因为他而没有数据
     * @return 依赖信息列表
     */
    fun getAll(pomPath: String, superPom: List<String>): List<DependencyInfo> {
        val env = Env(localRepositoryPath)
        val gavCollector = GavCollector()

        // 注意：原 Java 代码中有个 bug，这里修复了
        // 原代码是 if (pomFile.exists()) throw ...，应该是 if (!pomFile.exists())
        val pomFile = File(pomPath)

        if (!pomFile.exists()) {
            logger.warn("POM 文件不存在: $pomPath")
            // 即使主 POM 不存在，也继续处理文件夹路径
        }else{
            GavParser(env).parse(pomPath, gavCollector)
        }


        try {
            GavBatchParser(env).parseBatch(superPom, gavCollector)
            return gavCollector.toDependencyInfoList()
        } catch (e: ModelBuildingException) {
            logger.error("解析 POM 文件失败: $pomPath", e)
            return emptyList()
        }
    }
    
}

