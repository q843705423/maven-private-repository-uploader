package com.maven.privateuploader.analyzer

import com.intellij.openapi.diagnostic.thisLogger
import org.apache.maven.model.Model
import org.apache.maven.model.building.*
import org.apache.maven.model.resolution.ModelResolver
import org.apache.maven.model.resolution.UnresolvableModelException
import java.io.File
import java.util.Properties

/**
 * 基于 Maven 官方 maven-model-builder 的有效 POM 解析器
 * 
 * 使用 Maven 官方的 ModelBuilder 来构建有效 POM（effective model），
 * 保证和 Maven CLI 构建行为一致，包括：
 * - 父 POM 继承链完整处理
 * - dependencyManagement、pluginManagement 正确"抹平"
 * - 属性替换、profile 激活逻辑完整
 */
class EffectivePomResolver {
    
    private val logger = thisLogger()
    private val modelBuilder: ModelBuilder
    private val localRepositoryPath: String
    
    init {
        // 使用工厂创建 ModelBuilder 实例
        modelBuilder = DefaultModelBuilderFactory().newInstance()
        // 获取本地 Maven 仓库路径
        localRepositoryPath = getLocalMavenRepositoryPath()
    }
    
    /**
     * 获取本地 Maven 仓库路径
     */
    private fun getLocalMavenRepositoryPath(): String {
        val mavenHome = System.getProperty("user.home")
        return System.getProperty("maven.repo.local", "$mavenHome/.m2/repository")
    }
    
    /**
     * 创建 ModelResolver，用于从本地仓库解析父 POM
     */
    private fun createModelResolver(): ModelResolver {
        return object : ModelResolver {
            override fun resolveModel(groupId: String, artifactId: String, version: String): ModelSource {
                val pomPath = File(
                    localRepositoryPath,
                    "${groupId.replace('.', '/')}/$artifactId/$version/$artifactId-$version.pom"
                )
                
                if (pomPath.exists() && pomPath.isFile) {
                    logger.debug("从本地仓库解析 POM: $groupId:$artifactId:$version -> ${pomPath.absolutePath}")
                    return FileModelSource(pomPath)
                } else {
                    logger.warn("本地仓库中找不到 POM: $groupId:$artifactId:$version (路径: ${pomPath.absolutePath})")
                    throw UnresolvableModelException(
                        "无法从本地仓库解析 POM: $groupId:$artifactId:$version",
                        groupId,
                        artifactId,
                        version
                    )
                }
            }

            override fun resolveModel(parent: org.apache.maven.model.Parent): ModelSource {
                return resolveModel(parent.groupId, parent.artifactId, parent.version)
            }

            override fun resolveModel(dependency: org.apache.maven.model.Dependency): ModelSource {
                return resolveModel(dependency.groupId, dependency.artifactId, dependency.version)
            }

            override fun addRepository(repository: org.apache.maven.model.Repository) {
                // 本地解析器不支持远程仓库，忽略
            }

            override fun addRepository(
                repository: org.apache.maven.model.Repository,
                replace: Boolean
            ) {
                // 本地解析器不支持远程仓库，忽略
            }

            override fun newCopy(): ModelResolver {
                // 返回新的实例
                return createModelResolver()
            }
        }
    }
    
    /**
     * 解析给定 pom 文件，返回"有效 POM"模型（已处理父 POM、属性替换、dependencyManagement 等）
     * 
     * @param pomFile POM 文件路径
     * @param processPlugins 是否解析插件（默认 true）
     * @return 有效 POM 模型，如果解析失败返回 null
     */
    fun buildEffectiveModel(pomFile: File, processPlugins: Boolean = true): Model? {
        if (!pomFile.exists() || !pomFile.isFile) {
            logger.warn("POM 文件不存在: ${pomFile.absolutePath}")
            return null
        }
        
        return try {
            val request = DefaultModelBuildingRequest()
            request.pomFile = pomFile
            
            // 可选：如果项目有 settings.xml，可以在这里指定
            // val userSettings = File(System.getProperty("user.home"), ".m2/settings.xml")
            // if (userSettings.exists()) {
            //     request.userSettingsFile = userSettings
            // }
            
            // 是否解析 <build><plugins> 区域
            request.isProcessPlugins = processPlugins
            
            // 校验级别，保持和 Maven 3.x 一致
            request.validationLevel = ModelBuildingRequest.VALIDATION_LEVEL_MAVEN_3_0
            
            // 设置系统属性和用户属性（用于 profile 激活和属性替换）
            request.systemProperties = System.getProperties() as Properties
            request.userProperties = Properties() // 可以额外传用户属性
            
            // 设置 ModelResolver，用于解析父 POM
            request.modelResolver = createModelResolver()
            
            val result = modelBuilder.build(request)
            
            val effectiveModel = result.effectiveModel
            logger.debug("成功构建有效 POM: ${effectiveModel.groupId}:${effectiveModel.artifactId}:${effectiveModel.version}")
            
            effectiveModel
        } catch (e: ModelBuildingException) {
            logger.error("构建有效 POM 失败: ${pomFile.absolutePath}", e)
            // 如果是因为父 POM 不在本地仓库导致的错误，记录详细信息
            if (e.cause != null) {
                logger.debug("构建失败原因: ${e.cause?.message}")
            }
            null
        } catch (e: Exception) {
            logger.error("解析 POM 文件时发生未知错误: ${pomFile.absolutePath}", e)
            null
        }
    }
    
    /**
     * 检查是否能够成功构建有效 POM（用于诊断）
     */
    fun canBuildEffectiveModel(pomFile: File): Boolean {
        return buildEffectiveModel(pomFile, processPlugins = false) != null
    }
}

