package com.maven.privateuploader

import com.maven.privateuploader.analyzer.*
import org.junit.Test
import org.junit.Assert.*
import org.junit.Before
import java.io.File

/**
 * 解析真实项目POM文件的测试
 * 用于调试为什么MySQL驱动没有显示在表格中
 */
class RealProjectPomParsingTest {

    private val realPomPath = "D:\\code\\java\\stock-recommendations\\pom.xml"
    private lateinit var realPomFile: File

    @Before
    fun setUp() {
        realPomFile = File(realPomPath)
        assertTrue("真实项目POM文件应该存在", realPomFile.exists())
    }

    @Test
    fun testRealProjectPomParsing() {
        println("=== 开始解析真实项目POM文件 ===")
        println("POM文件路径: $realPomPath")
        println("POM文件大小: ${realPomFile.length()} bytes")

        // 1. 首先验证POM文件内容
        val pomContent = realPomFile.readText()
        assertTrue("POM文件应该包含MySQL驱动声明", pomContent.contains("mysql-connector-j"))
        assertTrue("POM文件应该包含runtime scope", pomContent.contains("runtime"))
        assertTrue("POM文件应该包含com.mysql组ID", pomContent.contains("com.mysql"))

        println("✅ POM文件验证通过")
        println("   - 包含 mysql-connector-j: ${pomContent.contains("mysql-connector-j")}")
        println("   - 包含 runtime scope: ${pomContent.contains("runtime")}")
        println("   - 包含 com.mysql: ${pomContent.contains("com.mysql")}")

        // 2. 使用EffectivePomResolver解析
        println("\n=== 使用EffectivePomResolver解析 ===")
        val effectivePomResolver = EffectivePomResolver()
        val effectiveModel = effectivePomResolver.buildEffectiveModel(realPomFile)

        assertNotNull("有效POM模型不应该为null", effectiveModel)
        println("✅ 有效POM解析成功")
        println("   - 项目: ${effectiveModel?.groupId}:${effectiveModel?.artifactId}:${effectiveModel?.version}")
        println("   - 总依赖数: ${effectiveModel?.dependencies?.size}")

        // 3. 查找MySQL驱动
        val allDeps = effectiveModel?.dependencies ?: emptyList()
        val mysqlDeps = allDeps.filter {
            it.groupId?.contains("mysql", ignoreCase = true) == true
        }
        val runtimeDeps = allDeps.filter { it.scope == "runtime" }
        val mysqlRuntimeDeps = runtimeDeps.filter {
            it.groupId?.contains("mysql", ignoreCase = true) == true
        }

        println("=== 依赖分析结果 ===")
        println("   - 总依赖数: ${allDeps.size}")
        println("   - MySQL相关依赖: ${mysqlDeps.size}")
        println("   - Runtime依赖: ${runtimeDeps.size}")
        println("   - MySQL Runtime依赖: ${mysqlRuntimeDeps.size}")

        // 详细显示MySQL依赖
        mysqlDeps.forEachIndexed { index, dep ->
            println("MySQL依赖 $index:")
            println("  - GroupId: ${dep.groupId}")
            println("  - ArtifactId: ${dep.artifactId}")
            println("  - Version: ${dep.version}")
            println("  - Scope: ${dep.scope}")
            println("  - Type: ${dep.type}")
            println("  - Optional: ${dep.isOptional}")
        }

        // 验证MySQL Connector J是否存在
        val mysqlConnectorJ = mysqlDeps.find {
            it.groupId == "com.mysql" && it.artifactId == "mysql-connector-j"
        }

        if (mysqlConnectorJ != null) {
            println("✅ 找到MySQL Connector J!")
            println("   - 详情: ${mysqlConnectorJ.groupId}:${mysqlConnectorJ.artifactId}:${mysqlConnectorJ.version}")
            println("   - 作用域: ${mysqlConnectorJ.scope ?: "compile"}")
        } else {
            println("❌ 没有找到MySQL Connector J")

            // 搜索可能的变体
            val possibleVariants = allDeps.filter { dep ->
                (dep.groupId?.contains("mysql") == true ||
                 dep.artifactId?.contains("mysql") == true ||
                 dep.artifactId?.contains("connector") == true)
            }

            if (possibleVariants.isNotEmpty()) {
                println("找到可能的MySQL相关依赖:")
                possibleVariants.forEach { dep ->
                    println("  - ${dep.groupId}:${dep.artifactId}:${dep.version} [${dep.scope}]")
                }
            }
        }

        assertNotNull("应该能找到MySQL Connector J", mysqlConnectorJ)
        assertEquals("MySQL Connector J版本应该是8.0.33", "8.0.33", mysqlConnectorJ?.version)
        assertEquals("MySQL Connector J作用域应该是runtime", "runtime", mysqlConnectorJ?.scope)
    }

    @Test
    fun testGavParserWithRealProject() {
        println("=== 使用GavParser解析真实项目 ===")

        // 设置环境
        val testLocalRepo = System.getProperty("user.home") + "/.m2/repository"
        val env = Env(testLocalRepo)
        val gavCollector = GavCollector()
        val gavParser = GavParser(env)

        println("使用本地仓库: $testLocalRepo")

        try {
            // 解析POM
            gavParser.parse(realPomFile.absolutePath, gavCollector)

            println("✅ GavParser解析完成")
            println("   - 收集到的依赖数量: ${gavCollector.count()}")

            val collectedGavs = gavCollector.getGavs()

            // 查找MySQL相关GAV
            val mysqlGavs = collectedGavs.filter {
                it.groupId.contains("mysql", ignoreCase = true) ||
                it.artifactId.contains("mysql", ignoreCase = true)
            }

            println("   - MySQL相关GAV数量: ${mysqlGavs.size}")

            // 详细显示MySQL GAV
            mysqlGavs.forEachIndexed { index, gav ->
                println("MySQL GAV $index:")
                println("  - GroupId: ${gav.groupId}")
                println("  - ArtifactId: ${gav.artifactId}")
                println("  - Version: ${gav.version}")
                println("  - Type: ${gav.type}")
                println("  - Path: ${gav.path}")
            }

            // 查找MySQL Connector J
            val mysqlConnectorJ = mysqlGavs.find {
                it.groupId == "com.mysql" && it.artifactId == "mysql-connector-j"
            }

            if (mysqlConnectorJ != null) {
                println("✅ GavParser找到MySQL Connector J!")
                println("   - 详情: ${mysqlConnectorJ.groupId}:${mysqlConnectorJ.artifactId}:${mysqlConnectorJ.version}")
                println("   - 路径: ${mysqlConnectorJ.path}")

                // 验证文件是否存在
                val jarFile = File(mysqlConnectorJ.path)
                println("   - JAR文件存在: ${jarFile.exists()}")
                if (jarFile.exists()) {
                    println("   - JAR文件大小: ${jarFile.length()} bytes")
                }
            } else {
                println("❌ GavParser没有找到MySQL Connector J")

                // 显示前几个依赖用于调试
                println("前10个收集到的依赖:")
                collectedGavs.take(10).forEachIndexed { index, gav ->
                    println("  $index: ${gav.groupId}:${gav.artifactId}:${gav.version}")
                }
            }

            assertNotNull("GavParser应该能找到MySQL Connector J", mysqlConnectorJ)
            assertEquals("MySQL Connector J版本应该正确", "8.0.33", mysqlConnectorJ?.version)

        } catch (e: Exception) {
            println("❌ GavParser解析失败: ${e.message}")
            println("异常类型: ${e.javaClass.simpleName}")
            e.printStackTrace()
            fail("GavParser解析不应该失败: ${e.message}")
        }
    }

    @Test
    fun testDependencyResolverWithRealMySQLDep() {
        println("=== 测试DependencyResolver解析真实MySQL依赖 ===")

        // 创建真实的MySQL依赖对象（从POM中复制）
        val mysqlDependency = org.apache.maven.model.Dependency()
        mysqlDependency.groupId = "com.mysql"
        mysqlDependency.artifactId = "mysql-connector-j"
        mysqlDependency.version = "8.0.33"
        mysqlDependency.scope = "runtime"
        mysqlDependency.type = "jar"

        println("解析MySQL依赖:")
        println("  - GroupId: ${mysqlDependency.groupId}")
        println("  - ArtifactId: ${mysqlDependency.artifactId}")
        println("  - Version: ${mysqlDependency.version}")
        println("  - Scope: ${mysqlDependency.scope}")

        try {
            val dependencyResolver = DependencyResolver()
            val resolvedGav = dependencyResolver.resolve(mysqlDependency)

            println("✅ DependencyResolver解析成功!")
            println("   - 解析结果: ${resolvedGav.groupId}:${resolvedGav.artifactId}:${resolvedGav.version}")
            println("   - 文件路径: ${resolvedGav.path}")

            // 验证文件是否存在
            val jarFile = File(resolvedGav.path)
            println("   - JAR文件存在: ${jarFile.exists()}")

            if (jarFile.exists()) {
                println("   - JAR文件大小: ${jarFile.length()} bytes")
                println("   - JAR文件绝对路径: ${jarFile.absolutePath}")
            } else {
                println("   - ⚠️ JAR文件不存在，这可能解释了为什么没有显示在表格中")
            }

            // 验证解析结果
            assertEquals("组ID应该保持一致", mysqlDependency.groupId, resolvedGav.groupId)
            assertEquals("构件ID应该保持一致", mysqlDependency.artifactId, resolvedGav.artifactId)
            assertEquals("版本应该保持一致", mysqlDependency.version, resolvedGav.version)

        } catch (e: Exception) {
            println("❌ DependencyResolver解析失败: ${e.message}")
            println("异常类型: ${e.javaClass.simpleName}")
            e.printStackTrace()
            fail("DependencyResolver解析不应该失败: ${e.message}")
        }
    }
}