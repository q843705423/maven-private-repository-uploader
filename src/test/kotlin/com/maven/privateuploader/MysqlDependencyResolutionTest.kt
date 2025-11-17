package com.maven.privateuploader

import com.maven.privateuploader.analyzer.GavParser
import com.maven.privateuploader.analyzer.EffectivePomResolver
import com.maven.privateuploader.analyzer.YourModelResolver
import com.maven.privateuploader.analyzer.Env
import com.maven.privateuploader.analyzer.GavCollector
import com.maven.privateuploader.analyzer.DependencyResolver
import com.maven.privateuploader.model.DependencyInfo
import com.maven.privateuploader.model.CheckStatus
import org.apache.maven.model.Model
import org.apache.maven.model.Dependency
import org.apache.maven.model.building.DefaultModelBuilderFactory
import org.apache.maven.model.building.DefaultModelBuildingRequest
import org.apache.maven.model.building.ModelBuildingRequest
import org.apache.maven.model.building.ModelBuildingResult
import org.junit.Test
import org.junit.Assert.*
import org.junit.Before
import java.io.File

/**
 * 测试 MySQL Connector J 依赖解析问题
 * 验证是否能正确解析传递依赖（如 protobuf-java）
 */
class MysqlDependencyResolutionTest {

    private lateinit var env: Env
    private lateinit var gavParser: GavParser
    private lateinit var effectivePomResolver: EffectivePomResolver
    private lateinit var dependencyResolver: DependencyResolver

    @Before
    fun setUp() {
        // 使用默认的 Maven 本地仓库路径
        val localRepo = System.getProperty("user.home") + "/.m2/repository"
        env = Env(localRepo)
        gavParser = GavParser(env)
        effectivePomResolver = EffectivePomResolver()
        dependencyResolver = DependencyResolver()
    }

    @Test
    fun testMysqlConnectorJDependencyResolution() {
        println("=== 测试 MySQL Connector J 依赖解析 ===")

        // 1. 测试 DependencyResolver 对 MySQL 依赖的解析
        try {
            println("1. 测试 DependencyResolver 解析 MySQL Connector J...")
            val mysqlDependency = Dependency()
            mysqlDependency.groupId = "com.mysql"
            mysqlDependency.artifactId = "mysql-connector-j"
            mysqlDependency.version = "8.0.33"
            mysqlDependency.scope = "runtime"

            val resolvedGav = dependencyResolver.resolve(mysqlDependency)
            println("MySQL Connector J 解析结果:")
            println("  GAV: ${resolvedGav.groupId}:${resolvedGav.artifactId}:${resolvedGav.version}")
            println("  类型: ${resolvedGav.type}")
            println("  路径: ${resolvedGav.path}")
            println("  文件存在: ${File(resolvedGav.path).exists()}")
            println()
        } catch (e: Exception) {
            println("解析 MySQL Connector J 失败: ${e.message}")
            println("原因: ${e.cause?.message}")
            println()
        }

        // 2. 测试 protobuf-java 依赖解析
        try {
            println("2. 测试 DependencyResolver 解析 protobuf-java...")
            val protobufDependency = Dependency()
            protobufDependency.groupId = "com.google.protobuf"
            protobufDependency.artifactId = "protobuf-java"
            protobufDependency.version = "3.21.9"

            val resolvedGav = dependencyResolver.resolve(protobufDependency)
            println("protobuf-java 解析结果:")
            println("  GAV: ${resolvedGav.groupId}:${resolvedGav.artifactId}:${resolvedGav.version}")
            println("  类型: ${resolvedGav.type}")
            println("  路径: ${resolvedGav.path}")
            println("  文件存在: ${File(resolvedGav.path).exists()}")
            println()
        } catch (e: Exception) {
            println("解析 protobuf-java 失败: ${e.message}")
            println("原因: ${e.cause?.message}")
            println()
        }

        // 3. 测试 protobuf-parent POM 解析
        try {
            println("3. 测试 DependencyResolver 解析 protobuf-parent...")
            val protobufParentDependency = Dependency()
            protobufParentDependency.groupId = "com.google.protobuf"
            protobufParentDependency.artifactId = "protobuf-parent"
            protobufParentDependency.version = "3.21.9"
            protobufParentDependency.type = "pom"

            val resolvedGav = dependencyResolver.resolve(protobufParentDependency)
            println("protobuf-parent 解析结果:")
            println("  GAV: ${resolvedGav.groupId}:${resolvedGav.artifactId}:${resolvedGav.version}")
            println("  类型: ${resolvedGav.type}")
            println("  路径: ${resolvedGav.path}")
            println("  文件存在: ${File(resolvedGav.path).exists()}")
            println()
        } catch (e: Exception) {
            println("解析 protobuf-parent 失败: ${e.message}")
            println("原因: ${e.cause?.message}")
            println()
        }
    }

    @Test
    fun testLocalMavenRepositoryForMysqlDependencies() {
        println("=== 测试本地 Maven 仓库中的 MySQL 相关依赖 ===")

        val localRepo = File(System.getProperty("user.home"), ".m2/repository")
        val mysqlPath = File(localRepo, "com/mysql/mysql-connector-j/8.0.33")
        val protobufPath = File(localRepo, "com/google/protobuf/protobuf-java/3.21.9")
        val protobufParentPath = File(localRepo, "com/google/protobuf/protobuf-parent/3.21.9")

        println("本地 Maven 仓库路径: $localRepo")
        println()

        println("MySQL Connector J 8.0.33 文件:")
        checkArtifactFiles(mysqlPath)
        println()

        println("protobuf-java 3.21.9 文件:")
        checkArtifactFiles(protobufPath)
        println()

        println("protobuf-parent 3.21.9 文件:")
        checkArtifactFiles(protobufParentPath)
        println()
    }

    @Test
    fun testEffectivePomResolutionWithMysqlDependency() {
        println("=== 测试有效 POM 解析（包含 MySQL 依赖） ===")

        // 创建一个包含 MySQL 依赖的测试 POM
        val testPomContent = """
            <?xml version="1.0" encoding="UTF-8"?>
            <project xmlns="http://maven.apache.org/POM/4.0.0"
                     xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
                     xsi:schemaLocation="http://maven.apache.org/POM/4.0.0
                     http://maven.apache.org/xsd/maven-4.0.0.xsd">
                <modelVersion>4.0.0</modelVersion>
                <groupId>com.test</groupId>
                <artifactId>test-project</artifactId>
                <version>1.0.0</version>
                <packaging>jar</packaging>

                <dependencies>
                    <dependency>
                        <groupId>com.mysql</groupId>
                        <artifactId>mysql-connector-j</artifactId>
                        <version>8.0.33</version>
                        <scope>runtime</scope>
                    </dependency>
                </dependencies>
            </project>
        """.trimIndent()

        try {
            println("创建测试 POM 文件...")
            val tempPomFile = File.createTempFile("test-mysql-pom", ".xml")
            tempPomFile.writeText(testPomContent)
            println("测试 POM 文件路径: ${tempPomFile.absolutePath}")

            // 解析有效 POM
            val buildingRequest = DefaultModelBuildingRequest()
            buildingRequest.pomFile = tempPomFile
            buildingRequest.validationLevel = ModelBuildingRequest.VALIDATION_LEVEL_MINIMAL
            buildingRequest.systemProperties = System.getProperties()

            // 设置 ModelResolver 用于解析依赖
            val gavCollector = GavCollector()
            val modelResolver = YourModelResolver(env.getRoot(), gavCollector)
            buildingRequest.modelResolver = modelResolver

            val modelBuilder = DefaultModelBuilderFactory().newInstance()
            val buildingResult = modelBuilder.build(buildingRequest)

            println("有效 POM 解析成功!")
            val effectiveModel = buildingResult.effectiveModel
            println("依赖数量: ${effectiveModel.dependencies.size}")

            effectiveModel.dependencies.forEach { dep ->
                println("依赖: ${dep.groupId}:${dep.artifactId}:${dep.version} [${dep.scope}]")
            }

            tempPomFile.deleteOnExit()

        } catch (e: Exception) {
            println("有效 POM 解析失败: ${e.message}")
            println("详细错误: ${e.stackTraceToString()}")
        }
    }

    private fun checkArtifactFiles(artifactDir: File) {
        if (!artifactDir.exists()) {
            println("  目录不存在: ${artifactDir.absolutePath}")
            return
        }

        println("  目录: ${artifactDir.absolutePath}")
        artifactDir.listFiles()?.forEach { file ->
            println("    - ${file.name} (${file.length()} bytes)")
        }
    }

    @Test
    fun testDependencyResolverWithMysql() {
        println("=== 测试 DependencyResolver 处理 MySQL 依赖 ===")

        try {
            // 模拟 MySQL Connector J 的依赖信息
            val mysqlDependency = Dependency()
            mysqlDependency.groupId = "com.mysql"
            mysqlDependency.artifactId = "mysql-connector-j"
            mysqlDependency.version = "8.0.33"
            mysqlDependency.scope = "runtime"

            println("尝试解析 MySQL Connector J 的依赖...")
            val resolvedGav = dependencyResolver.resolve(mysqlDependency)

            println("解析结果:")
            println("  - GAV: ${resolvedGav.groupId}:${resolvedGav.artifactId}:${resolvedGav.version}")
            println("  - 类型: ${resolvedGav.type}")
            println("  - 路径: ${resolvedGav.path}")
            println("  - 文件存在: ${File(resolvedGav.path).exists()}")

        } catch (e: Exception) {
            println("DependencyResolver 解析失败: ${e.message}")
            println("原因: ${e.cause?.message}")
        }
    }
}