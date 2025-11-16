package com.maven.privateuploader.analyzer

import com.intellij.openapi.diagnostic.thisLogger
import org.apache.maven.model.Model
import java.io.File
import java.util.*

/**
 * Maven 模块扫描器
 * 递归收集多模块项目中的所有模块 POM 文件
 */
class MavenModuleScanner(private val modelUtils: MavenModelUtils) {

    private val logger = thisLogger()

    /**
     * 收集所有模块的 POM 文件
     * 
     * @param rootPom 根 POM 文件
     * @return 所有模块的 POM 文件集合（包括根 POM）
     */
    fun collectAllModulePoms(rootPom: File): Set<File> {
        val result = LinkedHashSet<File>()
        val stack = ArrayDeque<File>()
        stack.push(rootPom)

        while (stack.isNotEmpty()) {
            val pom = stack.pop()
            
            // 如果已经处理过，跳过
            if (!result.add(pom)) {
                continue
            }

            try {
                // 构建有效模型
                val model = modelUtils.buildEffectiveModel(pom, processPlugins = false)
                if (model == null) {
                    logger.warn("无法构建有效模型: ${pom.absolutePath}")
                    continue
                }

                val baseDir = pom.parentFile
                if (baseDir == null) {
                    logger.warn("POM 文件没有父目录: ${pom.absolutePath}")
                    continue
                }

                // 获取所有子模块
                val modules = model.modules
                if (modules != null && modules.isNotEmpty()) {
                    logger.debug("发现 ${modules.size} 个子模块: ${pom.absolutePath}")
                    for (module in modules) {
                        val modulePom = File(File(baseDir, module), "pom.xml")
                        if (modulePom.exists() && modulePom.isFile) {
                            stack.push(modulePom)
                            logger.debug("添加子模块 POM: ${modulePom.absolutePath}")
                        } else {
                            logger.warn("子模块 POM 不存在: ${modulePom.absolutePath}")
                        }
                    }
                }
            } catch (e: Exception) {
                logger.error("处理 POM 文件时发生错误: ${pom.absolutePath}", e)
            }
        }

        logger.info("共收集到 ${result.size} 个模块 POM 文件")
        return result
    }
}

