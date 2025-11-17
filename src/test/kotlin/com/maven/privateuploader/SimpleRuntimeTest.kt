package com.maven.privateuploader

import com.maven.privateuploader.analyzer.*
import org.apache.maven.model.Dependency
import org.junit.Test
import org.junit.Assert.*
import org.junit.Before
import java.io.File
import java.io.FileWriter
import java.nio.file.Files
import java.nio.file.Path

/**
 * 简化的Runtime依赖测试，避免与其他测试冲突
 */
class SimpleRuntimeTest {

    @Test
    fun testDependencyResolverWithMySQLConnector() {
        println("=== 测试MySQL Connector J依赖解析 ===")

        // 创建一个runtime依赖
        val runtimeDependency = Dependency()
        runtimeDependency.groupId = "com.mysql"
        runtimeDependency.artifactId = "mysql-connector-j"
        runtimeDependency.version = "8.0.33"
        runtimeDependency.scope = "runtime"
        runtimeDependency.type = "jar"

        println("原始依赖: ${runtimeDependency.groupId}:${runtimeDependency.artifactId}:${runtimeDependency.version} [scope: ${runtimeDependency.scope}]")

        // 使用DependencyResolver解析
        val dependencyResolver = DependencyResolver()
        val resolvedGav = dependencyResolver.resolve(runtimeDependency)

        println("解析结果:")
        println("  GAV: ${resolvedGav.groupId}:${resolvedGav.artifactId}:${resolvedGav.version}:${resolvedGav.type}")
        println("  路径: ${resolvedGav.path}")

        // 验证解析结果
        assertEquals("组ID应该保持一致", runtimeDependency.groupId, resolvedGav.groupId)
        assertEquals("构件ID应该保持一致", runtimeDependency.artifactId, resolvedGav.artifactId)
        assertEquals("版本应该保持一致", runtimeDependency.version, resolvedGav.version)
        assertEquals("类型应该保持一致", runtimeDependency.type, resolvedGav.type)

        // 验证路径构建是否正确
        assertTrue("路径应该包含正确的仓库结构",
                  resolvedGav.path.contains("com${File.separator}mysql${File.separator}mysql-connector-j${File.separator}8.0.33"))
        assertTrue("路径应该以正确的文件名结尾",
                  resolvedGav.path.endsWith("mysql-connector-j-8.0.33.jar"))

        println("✓ Runtime依赖解析测试通过")
    }

    @Test
    fun testGavCollectorWithMySQLDependency() {
        println("=== 测试GavCollector收集MySQL依赖 ===")

        val gavCollector = GavCollector()

        // 创建MySQL Connector J的GAV对象
        val mysqlGav = Gav(
            groupId = "com.mysql",
            artifactId = "mysql-connector-j",
            version = "8.0.33",
            type = "jar",
            path = "/test/path/com/mysql/mysql-connector-j/8.0.33/mysql-connector-j-8.0.33.jar"
        )

        // 添加到收集器
        gavCollector.add(mysqlGav)

        // 验证收集结果
        assertEquals("应该收集到1个依赖", 1, gavCollector.count())
        assertEquals("收集器大小应该是1", 1, gavCollector.getGavs().size)

        val collectedGav = gavCollector.getGavs().first()
        assertEquals("组ID应该正确", "com.mysql", collectedGav.groupId)
        assertEquals("构件ID应该正确", "mysql-connector-j", collectedGav.artifactId)
        assertEquals("版本应该正确", "8.0.33", collectedGav.version)
        assertEquals("类型应该正确", "jar", collectedGav.type)

        println("收集到的依赖: ${collectedGav.groupId}:${collectedGav.artifactId}:${collectedGav.version}")
        println("✓ GavCollector测试通过")
    }

    @Test
    fun testEffectivePomResolverWithRuntimeDeps() {
        println("=== 测试有效POM解析runtime依赖 ===")

        // 创建一个包含runtime依赖的POM内容
        val pomContent = """
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0
         http://maven.apache.org/xsd/maven-4.0.0.xsd">
    <modelVersion>4.0.0</modelVersion>
    <groupId>com.ruoyi</groupId>
    <artifactId>stock-recommendations</artifactId>
    <version>3.8.6</version>

    <dependencies>
        <dependency>
            <groupId>com.mysql</groupId>
            <artifactId>mysql-connector-j</artifactId>
            <version>8.0.33</version>
            <scope>runtime</scope>
        </dependency>
        <dependency>
            <groupId>org.springframework</groupId>
            <artifactId>spring-core</artifactId>
            <version>5.3.21</version>
        </dependency>
    </dependencies>
</project>
        """.trimIndent()

        // 创建临时POM文件
        val tempFile = Files.createTempFile("test-pom", ".xml").toFile()
        tempFile.writeText(pomContent)

        try {
            println("测试POM文件路径: ${tempFile.absolutePath}")

            // 使用EffectivePomResolver解析
            val effectivePomResolver = EffectivePomResolver()
            val effectiveModel = effectivePomResolver.buildEffectiveModel(tempFile)

            assertNotNull("有效POM模型不应该为null", effectiveModel)

            println("解析结果:")
            println("  项目: ${effectiveModel?.groupId}:${effectiveModel?.artifactId}:${effectiveModel?.version}")
            println("  依赖数量: ${effectiveModel?.dependencies?.size}")

            // 查找runtime依赖
            val runtimeDeps = effectiveModel?.dependencies?.filter { it.scope == "runtime" } ?: emptyList()
            val mysqlRuntimeDep = runtimeDeps.find { it.groupId == "com.mysql" && it.artifactId == "mysql-connector-j" }

            println("  Runtime依赖数量: ${runtimeDeps.size}")

            runtimeDeps.forEach { dep ->
                println("    - ${dep.groupId}:${dep.artifactId}:${dep.version} [scope: ${dep.scope}]")
            }

            // 验证结果
            assertTrue("应该能找到runtime依赖", runtimeDeps.isNotEmpty())
            assertNotNull("应该能找到MySQL Connector J", mysqlRuntimeDep)
            assertEquals("MySQL Connector J版本应该正确", "8.0.33", mysqlRuntimeDep?.version)
            assertEquals("MySQL Connector J作用域应该是runtime", "runtime", mysqlRuntimeDep?.scope)

            println("✓ 有效POM解析runtime依赖测试通过")

        } finally {
            // 清理临时文件
            tempFile.delete()
        }
    }

    @Test
    fun testAllComponentsWorkingTogether() {
        println("=== 综合测试：所有组件协同工作 ===")

        // 创建完整的POM文件
        val pomContent = """
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0
         http://maven.apache.org/xsd/maven-4.0.0.xsd">
    <modelVersion>4.0.0</modelVersion>
    <groupId>com.test</groupId>
    <artifactId>comprehensive-test</artifactId>
    <version>1.0.0</version>

    <dependencies>
        <dependency>
            <groupId>com.mysql</groupId>
            <artifactId>mysql-connector-j</artifactId>
            <version>8.0.33</version>
            <scope>runtime</scope>
        </dependency>
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter</artifactId>
            <version>2.7.0</version>
        </dependency>
    </dependencies>
</project>
        """.trimIndent()

        // 创建临时文件和环境
        val tempFile = Files.createTempFile("comprehensive-pom", ".xml").toFile()
        val testLocalRepo = Files.createTempDirectory("test-repo")

        try {
            tempFile.writeText(pomContent)
            println("POM文件: ${tempFile.absolutePath}")
            println("本地仓库: ${testLocalRepo.toAbsolutePath()}")

            // 1. 使用EffectivePomResolver解析
            val effectivePomResolver = EffectivePomResolver()
            val effectiveModel = effectivePomResolver.buildEffectiveModel(tempFile)
            assertNotNull("有效POM应该不为null", effectiveModel)

            // 2. 使用GavParser解析
            val env = Env(testLocalRepo.toString())
            val gavCollector = GavCollector()
            val gavParser = GavParser(env)
            gavParser.parse(tempFile.absolutePath, gavCollector)

            println("GavParser解析完成，收集到 ${gavCollector.count()} 个依赖")

            // 3. 验证结果
            val collectedGavs = gavCollector.getGavs()
            assertTrue("应该收集到依赖", collectedGavs.isNotEmpty())

            val mysqlGav = collectedGavs.find {
                it.groupId == "com.mysql" && it.artifactId == "mysql-connector-j"
            }
            assertNotNull("应该收集到MySQL Connector J", mysqlGav)
            assertEquals("MySQL Connector J版本应该正确", "8.0.33", mysqlGav?.version)

            println("找到MySQL Connector J: ${mysqlGav?.groupId}:${mysqlGav?.artifactId}:${mysqlGav?.version}")
            println("✓ 综合测试通过")

        } finally {
            // 清理资源
            tempFile.delete()
            testLocalRepo.toFile().deleteRecursively()
        }
    }
}