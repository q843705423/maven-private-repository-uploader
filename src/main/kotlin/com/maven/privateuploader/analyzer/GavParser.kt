package com.maven.privateuploader.analyzer

import com.intellij.openapi.diagnostic.thisLogger
import org.apache.maven.model.building.*
import java.io.File

/**
 * GAV 解析器
 * 对应原 Java 项目中的 GavParser 类
 */
class GavParser(private val env: Env) {

    private val logger = thisLogger()

    fun parse(pathname: String, gavCollector: GavCollector) {
        val pomFile = File(pathname)
        logger.info("=== 开始解析POM文件 ===")
        logger.info("POM文件路径: $pathname")
        logger.info("POM文件是否存在: ${pomFile.exists()}")
        logger.info("POM文件大小: ${if (pomFile.exists()) pomFile.length() else "N/A"} bytes")

        parseRecursive(pomFile, gavCollector)

        logger.info("=== POM解析完成 ===")
        logger.info("收集到的总依赖数量: ${gavCollector.getGavs().size}")

        // 详细日志记录所有收集到的依赖
        logger.info("=== 收集到的依赖详情 ===")
        gavCollector.getGavs().forEachIndexed { index, gav ->
            logger.info("依赖 #$index: ${gav.groupId}:${gav.artifactId}:${gav.version}:${gav.type} [路径: ${gav.path}]")
        }
    }

    private fun parseRecursive(pomFile: File, gavCollector: GavCollector) {
        val root = env.getRoot()
        logger.info("=== 开始递归解析POM ===")
        logger.info("使用Maven仓库根目录: $root")

        val builder = DefaultModelBuilderFactory().newInstance()
        val req = DefaultModelBuildingRequest()
        req.pomFile = pomFile
        req.isProcessPlugins = false  // 修改：避免插件处理干扰依赖解析
        req.isTwoPhaseBuilding = false
        req.validationLevel = ModelBuildingRequest.VALIDATION_LEVEL_MINIMAL
        req.systemProperties = System.getProperties()
        // 🔧 关键修复：启用传递依赖解析
        // 注意：传递依赖解析通过 ModelResolver 的递归调用来实现

        // 这里要设置你自己的 ModelResolver
        val modelResolver = YourModelResolver(root, gavCollector)
        req.modelResolver = modelResolver

        logger.info("开始构建有效POM模型...")
        val result = builder.build(req)

        if (result.problems?.isNotEmpty() == true) {
            logger.warn("构建有效POM时发现警告:")
            result.problems?.forEach { problem ->
                logger.warn("  - ${problem.message}")
            }
        }

        val effectiveModel = result.effectiveModel
        logger.info("有效POM模型构建成功")
        logger.info("项目: ${effectiveModel.groupId}:${effectiveModel.artifactId}:${effectiveModel.version}")
        logger.info("打包类型: ${effectiveModel.packaging}")

        val dependencies = effectiveModel.dependencies
        logger.info("=== 依赖分析开始 ===")
        logger.info("有效POM中发现 ${dependencies.size} 个依赖")

        val dependencyResolver = DependencyResolver()
        val pluginResolver = PluginResolver()

        var runtimeDeps = 0
        var compileDeps = 0
        var providedDeps = 0
        var testDeps = 0
        var importDeps = 0
        var systemDeps = 0

        logger.info("=== 开始处理每个依赖 ===")
        dependencies.forEachIndexed { index, dependency ->
            val scope = dependency.scope ?: "compile"
            when (scope) {
                "runtime" -> runtimeDeps++
                "compile" -> compileDeps++
                "provided" -> providedDeps++
                "test" -> testDeps++
                "import" -> importDeps++
                "system" -> systemDeps++
            }

            logger.info("处理依赖 #$index: ${dependency.groupId}:${dependency.artifactId}:${dependency.version}")
            logger.info("  - 作用域: $scope")
            logger.info("  - 类型: ${dependency.type ?: "jar"}")
            logger.info("  - 可选性: ${dependency.isOptional}")

            try {
                logger.info("  - 正在解析依赖...")
                val resolve = dependencyResolver.resolve(dependency)
                logger.info("  - 解析成功: ${resolve.groupId}:${resolve.artifactId}:${resolve.version}:${resolve.type}")
                logger.info("  - 文件路径: ${resolve.path}")

                // 特别关注MySQL驱动
                if (dependency.groupId?.contains("mysql") == true &&
                    dependency.artifactId?.contains("connector") == true) {
                    logger.info("🔥 发现MySQL驱动依赖！")
                    logger.info("  - 详细信息: ${dependency.groupId}:${dependency.artifactId}:${dependency.version}")
                    logger.info("  - 作用域: $scope")
                    logger.info("  - 解析路径: ${resolve.path}")
                }

                gavCollector.add(resolve)
            } catch (e: Exception) {
                logger.error("  - 解析依赖失败: ${e.message}")
                logger.error("  - 异常类型: ${e.javaClass.simpleName}")
            }
        }

        logger.info("=== 依赖处理完成 ===")
        logger.info("依赖作用域统计:")
        logger.info("  - Runtime (运行时): $runtimeDeps")
        logger.info("  - Compile (编译时): $compileDeps")
        logger.info("  - Provided (已提供): $providedDeps")
        logger.info("  - Test (测试): $testDeps")
        logger.info("  - Import (导入): $importDeps")
        logger.info("  - System (系统): $systemDeps")

        val plugins = effectiveModel.build.plugins
        for (plugin in plugins) {
            val resolve = pluginResolver.resolve(plugin)
            if (resolve != null) {
                gavCollector.add(resolve)
            }
        }
        val plugins1 = effectiveModel.build.pluginManagement.plugins
        for (plugin in plugins1) {
            val resolve = pluginResolver.resolve(plugin)
            if (resolve != null) {
                gavCollector.add(resolve)
            }
        }

        // 检查是否是多模块项目，如果是则递归解析子模块
        val modules = effectiveModel.modules
        if (modules != null && modules.isNotEmpty()) {
            val parentDir = pomFile.parentFile
            for (module in modules) {
                val modulePomFile = File(parentDir, module + File.separator + "pom.xml")
                if (modulePomFile.exists()) {
                    parseRecursive(modulePomFile, gavCollector)
                }
            }
        }
    }
}

