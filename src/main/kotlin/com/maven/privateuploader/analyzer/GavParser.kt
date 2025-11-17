package com.maven.privateuploader.analyzer

import org.apache.maven.model.building.*
import java.io.File

/**
 * GAV 解析器
 * 对应原 Java 项目中的 GavParser 类
 */
class GavParser(private val env: Env) {

    fun parse(pathname: String, gavCollector: GavCollector) {
        val pomFile = File(pathname)
        parseRecursive(pomFile, gavCollector)
    }

    private fun parseRecursive(pomFile: File, gavCollector: GavCollector) {
        val root = env.getRoot()

        val builder = DefaultModelBuilderFactory().newInstance()
        val req = DefaultModelBuildingRequest()
        req.pomFile = pomFile
        req.isProcessPlugins = true
        req.isTwoPhaseBuilding = false
        req.validationLevel = ModelBuildingRequest.VALIDATION_LEVEL_MINIMAL
        req.systemProperties = System.getProperties()
        // 这里要设置你自己的 ModelResolver
        val modelResolver = YourModelResolver(root, gavCollector)
        req.modelResolver = modelResolver

        val result = builder.build(req)

        val effectiveModel = result.effectiveModel

        val dependencies = effectiveModel.dependencies

        val dependencyResolver = DependencyResolver()
        val pluginResolver = PluginResolver()
        for (dependency in dependencies) {
            val resolve = dependencyResolver.resolve(dependency)
            gavCollector.add(resolve)
        }

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

