package com.maven.privateuploader.client

import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.progress.ProgressIndicator
import com.maven.privateuploader.analyzer.PomParser
import com.maven.privateuploader.model.DependencyInfo
import com.maven.privateuploader.model.RepositoryConfig
import com.maven.privateuploader.model.CheckStatus
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import okio.buffer
import okio.sink
import okio.source
import java.io.File
import java.io.IOException
import java.util.Base64
import java.util.concurrent.TimeUnit

/**
 * Maven私仓客户端
 * 负责与Maven私仓进行HTTP交互
 */
class PrivateRepositoryClient(private val config: RepositoryConfig) {

    private val logger = thisLogger()
    private val httpClient: OkHttpClient by lazy {
        val builder = OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .writeTimeout(60, TimeUnit.SECONDS)

        // 添加认证
        if (config.username.isNotBlank() && config.password.isNotBlank()) {
            val credential = Base64.getEncoder().encodeToString("${config.username}:${config.password}".toByteArray())
            builder.addInterceptor { chain ->
                val request = chain.request().newBuilder()
                    .addHeader("Authorization", "Basic $credential")
                    .build()
                chain.proceed(request)
            }
        }

        // 添加日志拦截器（仅在开发环境）
        try {
            val loggingInterceptor = Class.forName("okhttp3.logging.HttpLoggingInterceptor")
            val interceptor = loggingInterceptor.getDeclaredConstructor().newInstance()
            val setLevel = loggingInterceptor.getMethod("setLevel", Class.forName("okhttp3.logging.HttpLoggingInterceptor\$Level"))
            val levelEnum = Class.forName("okhttp3.logging.HttpLoggingInterceptor\$Level").getField("HEADERS").get(null)
            setLevel.invoke(interceptor, levelEnum)
            builder.addInterceptor(interceptor as Interceptor)
        } catch (e: Exception) {
            logger.warn("无法创建HTTP日志拦截器: ${e.message}")
        }

        builder.build()
    }

    /**
     * 检查依赖是否存在于私仓中
     *
     * @param dependency 依赖信息
     * @return 是否存在
     */
    fun checkDependencyExists(dependency: DependencyInfo): Boolean {
        if (!config.isValid()) {
            logger.error("私仓配置无效，无法检查依赖存在性")
            dependency.checkStatus = CheckStatus.ERROR
            dependency.errorMessage = "私仓配置无效"
            return false
        }

        return try {
            val url = buildDependencyUrl(dependency)
            val request = Request.Builder()
                .url(url)
                .head()
                .build()

            logger.debug("检查依赖存在性: $url")

            val response = httpClient.newCall(request).execute()
            val exists = response.isSuccessful

            logger.debug("依赖 ${dependency.getGAV()} 是否存在: $exists (HTTP ${response.code})")
            
            // 如果失败，记录错误信息
            if (!exists && response.code != 404) {
                dependency.errorMessage = "HTTP ${response.code}: ${response.message}"
            } else {
                dependency.errorMessage = ""
            }
            
            exists
        } catch (e: Exception) {
            logger.error("检查依赖 ${dependency.getGAV()} 存在性时发生错误", e)
            dependency.errorMessage = e.message ?: "检查异常: ${e.javaClass.simpleName}"
            false
        }
    }

    /**
     * 批量检查依赖是否存在（同步版本，用于兼容）
     *
     * @param dependencies 依赖列表
     * @param progressIndicator 进度指示器
     */
    fun checkDependenciesExist(dependencies: List<DependencyInfo>, progressIndicator: ProgressIndicator?) {
        if (!config.isValid()) {
            logger.error("私仓配置无效，无法批量检查依赖存在性")
            dependencies.forEach { 
                it.checkStatus = CheckStatus.ERROR
                it.errorMessage = "私仓配置无效"
            }
            return
        }

        logger.info("开始批量检查 ${dependencies.size} 个依赖的存在性...")

        dependencies.forEachIndexed { index, dependency ->
            progressIndicator?.fraction = index.toDouble() / dependencies.size.toDouble()
            progressIndicator?.text2 = "检查依赖: ${dependency.getGAV()}"

            dependency.checkStatus = CheckStatus.CHECKING

            try {
                val exists = checkDependencyExists(dependency)
                dependency.existsInPrivateRepo = exists
                dependency.checkStatus = if (exists) CheckStatus.EXISTS else CheckStatus.MISSING
                dependency.selected = !exists // 默认选择缺失的依赖
                // errorMessage已在checkDependencyExists中设置

            } catch (e: Exception) {
                logger.error("检查依赖 ${dependency.getGAV()} 存在性时发生错误", e)
                dependency.checkStatus = CheckStatus.ERROR
                dependency.errorMessage = e.message ?: "未知错误"
                dependency.existsInPrivateRepo = false
                dependency.selected = false
            }
        }

        progressIndicator?.fraction = 1.0
        progressIndicator?.text2 = "依赖检查完成"

        val existsCount = dependencies.count { it.checkStatus == CheckStatus.EXISTS }
        val missingCount = dependencies.count { it.checkStatus == CheckStatus.MISSING }
        val errorCount = dependencies.count { it.checkStatus == CheckStatus.ERROR }

        logger.info("依赖检查完成: 已存在=$existsCount, 缺失=$missingCount, 错误=$errorCount")
    }

    /**
     * 上传依赖到私仓
     *
     * @param dependency 依赖信息
     * @param progressIndicator 进度指示器
     * @return 上传结果
     */
    fun uploadDependency(dependency: DependencyInfo, progressIndicator: ProgressIndicator?): UploadResult {
        // 使用 Set 记录已上传的依赖，避免循环依赖和重复上传
        val uploadedDependencies = mutableSetOf<String>()
        return uploadDependencyRecursive(dependency, progressIndicator, uploadedDependencies)
    }
    
    /**
     * 递归上传依赖（包括父 POM 链和 BOM）
     *
     * @param dependency 依赖信息
     * @param progressIndicator 进度指示器
     * @param uploadedDependencies 已上传的依赖集合（用于避免循环依赖）
     * @return 上传结果
     */
    private fun uploadDependencyRecursive(
        dependency: DependencyInfo,
        progressIndicator: ProgressIndicator?,
        uploadedDependencies: MutableSet<String>
    ): UploadResult {
        if (!config.isValid()) {
            logger.error("私仓配置无效，无法上传依赖")
            return UploadResult(false, "私仓配置无效")
        }

        if (dependency.localPath.isBlank()) {
            logger.error("依赖 ${dependency.getGAV()} 本地路径为空")
            return UploadResult(false, "本地路径为空")
        }

        val jarFile = File(dependency.localPath)
        if (!jarFile.exists()) {
            logger.error("依赖文件不存在: ${dependency.localPath}")
            return UploadResult(false, "依赖文件不存在: ${dependency.localPath}")
        }

        // 生成依赖的唯一标识（GAV）
        val dependencyKey = "${dependency.groupId}:${dependency.artifactId}:${dependency.version}"
        
        // 检查是否已经上传过（避免循环依赖和重复上传）
        if (uploadedDependencies.contains(dependencyKey)) {
            logger.debug("依赖 $dependencyKey 已上传，跳过")
            return UploadResult(true, "已上传（跳过重复）")
        }

        progressIndicator?.text2 = "上传依赖: ${dependency.getGAV()}"

        return try {
            // 如果是POM类型的依赖（如父POM、BOM），需要递归上传父 POM 和 BOM
            if (dependency.packaging == "pom") {
                // 先递归上传父 POM 链和 BOM
                val pomParser = PomParser()
                val pomInfo = pomParser.parsePom(jarFile)
                
                if (pomInfo != null) {
                    // 递归上传父 POM 链
                    pomInfo.parent?.let { parent ->
                        val parentDependency = pomParser.parentToDependencyInfo(parent)
                        if (parentDependency.localPath.isNotEmpty()) {
                            logger.info("发现父 POM: ${parentDependency.getGAV()}，开始递归上传")
                            val parentResult = uploadDependencyRecursive(parentDependency, progressIndicator, uploadedDependencies)
                            if (!parentResult.success) {
                                logger.warn("父 POM ${parentDependency.getGAV()} 上传失败: ${parentResult.message}")
                                // 继续上传当前 POM，不因为父 POM 失败而中断
                            }
                        } else {
                            logger.warn("父 POM ${parentDependency.getGAV()} 本地文件不存在，无法上传")
                        }
                    }
                    
                    // 递归上传 BOM 依赖
                    pomInfo.bomDependencies.forEach { bom ->
                        val bomDependency = pomParser.bomToDependencyInfo(bom)
                        if (bomDependency.localPath.isNotEmpty()) {
                            logger.info("发现 BOM 依赖: ${bomDependency.getGAV()}，开始递归上传")
                            val bomResult = uploadDependencyRecursive(bomDependency, progressIndicator, uploadedDependencies)
                            if (!bomResult.success) {
                                logger.warn("BOM ${bomDependency.getGAV()} 上传失败: ${bomResult.message}")
                                // 继续上传当前 POM，不因为 BOM 失败而中断
                            }
                        } else {
                            logger.warn("BOM ${bomDependency.getGAV()} 本地文件不存在，无法上传")
                        }
                    }
                }
                
                // 上传当前 POM 文件
                val pomResult = uploadFile(dependency, jarFile, "pom")
                if (!pomResult.success) {
                    return pomResult
                }
                
                // 标记为已上传
                uploadedDependencies.add(dependencyKey)
                logger.info("POM ${dependency.getGAV()} 上传成功")
                return UploadResult(true, "上传成功")
            }

            // 对于JAR类型的依赖，上传JAR文件
            val jarResult = uploadFile(dependency, jarFile, "jar")
            if (!jarResult.success) {
                return jarResult
            }

            // 上传POM文件（JAR 依赖的 POM）
            val pomFile = findPomFile(jarFile)
            if (pomFile != null && pomFile.exists()) {
                // 解析 JAR 的 POM，递归上传父 POM 和 BOM
                val pomParser = PomParser()
                val pomInfo = pomParser.parsePom(pomFile)
                
                if (pomInfo != null) {
                    // 递归上传父 POM 链
                    pomInfo.parent?.let { parent ->
                        val parentDependency = pomParser.parentToDependencyInfo(parent)
                        if (parentDependency.localPath.isNotEmpty()) {
                            logger.info("发现父 POM: ${parentDependency.getGAV()}，开始递归上传")
                            val parentResult = uploadDependencyRecursive(parentDependency, progressIndicator, uploadedDependencies)
                            if (!parentResult.success) {
                                logger.warn("父 POM ${parentDependency.getGAV()} 上传失败: ${parentResult.message}")
                            }
                        } else {
                            logger.warn("父 POM ${parentDependency.getGAV()} 本地文件不存在，无法上传")
                        }
                    }
                    
                    // 递归上传 BOM 依赖
                    pomInfo.bomDependencies.forEach { bom ->
                        val bomDependency = pomParser.bomToDependencyInfo(bom)
                        if (bomDependency.localPath.isNotEmpty()) {
                            logger.info("发现 BOM 依赖: ${bomDependency.getGAV()}，开始递归上传")
                            val bomResult = uploadDependencyRecursive(bomDependency, progressIndicator, uploadedDependencies)
                            if (!bomResult.success) {
                                logger.warn("BOM ${bomDependency.getGAV()} 上传失败: ${bomResult.message}")
                            }
                        } else {
                            logger.warn("BOM ${bomDependency.getGAV()} 本地文件不存在，无法上传")
                        }
                    }
                }
                
                // 上传当前 POM 文件
                val pomResult = uploadFile(dependency, pomFile, "pom")
                if (!pomResult.success) {
                    return pomResult
                }
            }

            // 上传Sources JAR（如果存在）
            val sourcesFile = findSourcesFile(jarFile)
            if (sourcesFile != null && sourcesFile.exists()) {
                uploadFile(dependency, sourcesFile, "jar", "sources")
            }

            // 标记为已上传
            uploadedDependencies.add(dependencyKey)
            logger.info("依赖 ${dependency.getGAV()} 上传成功")
            UploadResult(true, "上传成功")

        } catch (e: Exception) {
            logger.error("上传依赖 ${dependency.getGAV()} 时发生错误", e)
            val errorMsg = e.message ?: "上传异常: ${e.javaClass.simpleName}"
            // 写回错误信息到dependency
            dependency.errorMessage = errorMsg
            dependency.checkStatus = CheckStatus.ERROR
            UploadResult(false, errorMsg)
        }
    }

    /**
     * 上传文件到私仓
     */
    private fun uploadFile(
        dependency: DependencyInfo,
        file: File,
        extension: String,
        classifier: String? = null
    ): UploadResult {
        try {
            val url = buildFileUrl(dependency, extension, classifier)
            logger.debug("上传文件: $file -> $url")

            val mediaType = when (extension) {
                "jar" -> "application/java-archive".toMediaType()
                "pom" -> "text/xml".toMediaType()
                else -> "application/octet-stream".toMediaType()
            }

            val requestBody = file.source().buffer().readByteArray().toRequestBody(mediaType)
            val request = Request.Builder()
                .url(url)
                .put(requestBody)
                .build()

            val response = httpClient.newCall(request).execute()

            val success = response.isSuccessful
            val message = if (success) {
                "上传成功: ${response.message}"
            } else {
                "上传失败: HTTP ${response.code} - ${response.message}"
            }

            logger.debug("文件上传结果: $message")
            return UploadResult(success, message)

        } catch (e: Exception) {
            logger.error("上传文件时发生错误: ${file.absolutePath}", e)
            return UploadResult(false, "上传文件失败: ${e.message}")
        }
    }

    /**
     * 构建依赖的URL路径
     */
    private fun buildDependencyUrl(dependency: DependencyInfo): String {
        val baseUrl = config.getDeployUrl()
        val path = dependency.groupId.replace('.', '/') +
                "/${dependency.artifactId}/${dependency.version}/${dependency.artifactId}-${dependency.version}.${dependency.packaging}"
        return "$baseUrl$path"
    }

    /**
     * 构建文件的URL路径
     */
    private fun buildFileUrl(
        dependency: DependencyInfo,
        extension: String,
        classifier: String? = null
    ): String {
        val baseUrl = config.getDeployUrl()
        val fileName = if (classifier != null) {
            "${dependency.artifactId}-${dependency.version}-${classifier}.$extension"
        } else {
            "${dependency.artifactId}-${dependency.version}.$extension"
        }

        val path = dependency.groupId.replace('.', '/') +
                "/${dependency.artifactId}/${dependency.version}/$fileName"

        return "$baseUrl$path"
    }

    /**
     * 查找POM文件
     */
    private fun findPomFile(jarFile: File): File? {
        val jarName = jarFile.nameWithoutExtension
        val pomFile = File(jarFile.parent, "$jarName.pom")
        return if (pomFile.exists()) pomFile else null
    }

    /**
     * 查找Sources文件
     */
    private fun findSourcesFile(jarFile: File): File? {
        val jarName = jarFile.nameWithoutExtension
        val sourcesFile = File(jarFile.parent, "${jarName}-sources.jar")
        return if (sourcesFile.exists()) sourcesFile else null
    }
}

/**
 * 上传结果
 */
data class UploadResult(
    val success: Boolean,
    val message: String
)