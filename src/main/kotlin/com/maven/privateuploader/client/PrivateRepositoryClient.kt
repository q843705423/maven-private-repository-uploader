package com.maven.privateuploader.client

import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.progress.ProgressIndicator
import com.maven.privateuploader.model.DependencyInfo
import com.maven.privateuploader.model.RepositoryConfig
import com.maven.privateuploader.model.CheckStatus
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import okio.buffer
import okio.sink
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
            exists
        } catch (e: Exception) {
            logger.error("检查依赖 ${dependency.getGAV()} 存在性时发生错误", e)
            false
        }
    }

    /**
     * 批量检查依赖是否存在
     *
     * @param dependencies 依赖列表
     * @param progressIndicator 进度指示器
     */
    fun checkDependenciesExist(dependencies: List<DependencyInfo>, progressIndicator: ProgressIndicator?) {
        if (!config.isValid()) {
            logger.error("私仓配置无效，无法批量检查依赖存在性")
            dependencies.forEach { it.checkStatus = CheckStatus.ERROR }
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
                dependency.errorMessage = ""

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

        progressIndicator?.text2 = "上传依赖: ${dependency.getGAV()}"

        return try {
            // 上传JAR文件
            val jarResult = uploadFile(dependency, jarFile, "jar")
            if (!jarResult.success) {
                return jarResult
            }

            // 上传POM文件
            val pomFile = findPomFile(jarFile)
            if (pomFile != null && pomFile.exists()) {
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

            logger.info("依赖 ${dependency.getGAV()} 上传成功")
            UploadResult(true, "上传成功")

        } catch (e: Exception) {
            logger.error("上传依赖 ${dependency.getGAV()} 时发生错误", e)
            UploadResult(false, "上传失败: ${e.message}")
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

            val requestBody = RequestBody.create(mediaType, file)
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