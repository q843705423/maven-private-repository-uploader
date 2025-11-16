package com.maven.privateuploader.analyzer

import com.intellij.openapi.diagnostic.thisLogger
import org.apache.maven.model.Model
import org.apache.maven.model.building.*
import java.io.File
import java.util.*

/**
 * Maven 模型工具类
 * 封装 maven-model-builder，用于构建有效 POM 模型
 */
class MavenModelUtils {

    private val logger = thisLogger()
    private val modelBuilder: ModelBuilder

    init {
        // 使用工厂创建 ModelBuilder 实例
        modelBuilder = DefaultModelBuilderFactory().newInstance()
    }

    /**
     * 构建有效 POM 模型
     * 
     * @param pomFile POM 文件
     * @param processPlugins 是否处理插件（默认 true）
     * @return 有效 POM 模型，如果解析失败返回 null
     */
    fun buildEffectiveModel(
        pomFile: File,
        processPlugins: Boolean = true
    ): Model? {
        if (!pomFile.exists() || !pomFile.isFile) {
            logger.warn("POM 文件不存在: ${pomFile.absolutePath}")
            return null
        }

        return try {
            val request = DefaultModelBuildingRequest()
            request.pomFile = pomFile

            // 是否解析 <build><plugins> 区域
            request.isProcessPlugins = processPlugins

            // 校验级别，保持和 Maven 3.x 一致
            request.validationLevel = ModelBuildingRequest.VALIDATION_LEVEL_MAVEN_3_0

            // 设置系统属性和用户属性（用于 profile 激活和属性替换）
            request.systemProperties = System.getProperties() as Properties
            request.userProperties = Properties()

            // 注意：ModelBuilder 会使用默认的 ModelResolver 从本地仓库解析 parent POM
            // 如果父 POM 不在本地仓库，可能会失败

            // 构建有效模型
            val result = modelBuilder.build(request)

            val effectiveModel = result.effectiveModel
            logger.debug("成功构建有效 POM: ${effectiveModel.groupId}:${effectiveModel.artifactId}:${effectiveModel.version}")
            
            effectiveModel
        } catch (e: ModelBuildingException) {
            logger.error("构建有效 POM 失败: ${pomFile.absolutePath}", e)
            if (e.cause != null) {
                logger.debug("构建失败原因: ${e.cause?.message}")
            }
            null
        } catch (e: Exception) {
            logger.error("解析 POM 文件时发生未知错误: ${pomFile.absolutePath}", e)
            null
        }
    }
}

