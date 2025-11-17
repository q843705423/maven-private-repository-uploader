package com.maven.privateuploader

import com.maven.privateuploader.analyzer.*
import org.apache.maven.model.Dependency
import org.apache.maven.model.Model
import org.apache.maven.model.building.DefaultModelBuildingRequest
import org.junit.Test
import org.junit.Assert.*
import org.junit.Before
import java.io.File
import java.io.FileWriter
import java.nio.file.Files
import java.nio.file.Path

/**
 * 专门测试POM解析中runtime作用域依赖的单元测试
 */
class RuntimeDependencyPomParsingTest {

    private lateinit var tempDir: Path
    private lateinit var testLocalRepo: Path
    private lateinit var env: Env

    @Before
    fun setUp() {
        // 创建临时目录用于测试
        tempDir = Files.createTempDirectory("pom-parsing-test")
        testLocalRepo = Files.createTempDirectory("test-maven-repo")

        // 创建测试环境
        env = Env(testLocalRepo.toString())
    }

    @Test
    fun testRuntimeDependencyParsing() {
        // 创建一个包含runtime作用域依赖的测试POM文件
        val pomContent = """
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0
         http://maven.apache.org/xsd/maven-4.0.0.xsd">
    <modelVersion>4.0.0</modelVersion>

    <groupId>com.test</groupId>
    <artifactId>runtime-dependency-test</artifactId>
    <version>1.0.0</version>
    <packaging>jar</packaging>

    <dependencies>
        <!-- compile scope -->
        <dependency>
            <groupId>org.springframework</groupId>
            <artifactId>spring-core</artifactId>
            <version>5.3.21</version>
        </dependency>

        <!-- runtime scope -->
        <dependency>
            <groupId>com.mysql</groupId>
            <artifactId>mysql-connector-j</artifactId>
            <version>8.0.33</version>
            <scope>runtime</scope>
        </dependency>

        <!-- provided scope -->
        <dependency>
            <groupId>javax.servlet</groupId>
            <artifactId>javax.servlet-api</artifactId>
            <version>4.0.1</version>
            <scope>provided</scope>
        </dependency>

        <!-- test scope -->
        <dependency>
            <groupId>junit</groupId>
            <artifactId>junit</artifactId>
            <version>4.13.2</version>
            <scope>test</scope>
        </dependency>
    </dependencies>
</project>
        """.trimIndent()

        val pomFile = tempDir.resolve("pom.xml").toFile()
        FileWriter(pomFile).use { it.write(pomContent) }

        println("测试POM文件路径: ${pomFile.absolutePath}")
        println("测试Maven仓库路径: ${testLocalRepo.toAbsolutePath()}")

        // 1. 使用EffectivePomResolver解析
        val effectivePomResolver = EffectivePomResolver()
        val effectiveModel = effectivePomResolver.buildEffectiveModel(pomFile)

        assertNotNull("有效POM模型不应该为null", effectiveModel)

        println("=== 有效POM解析结果 ===")
        println("项目: ${effectiveModel?.groupId}:${effectiveModel?.artifactId}:${effectiveModel?.version}")
        println("依赖数量: ${effectiveModel?.dependencies?.size}")

        // 2. 验证不同作用域的依赖是否被正确解析
        effectiveModel?.dependencies?.forEach { dep ->
            val scope = dep.scope ?: "compile" // 默认scope是compile
            println("发现依赖: ${dep.groupId}:${dep.artifactId}:${dep.version} [scope: $scope]")
        }

        // 3. 使用GavParser解析
        val gavCollector = GavCollector()
        val gavParser = GavParser(env)
        gavParser.parse(pomFile.absolutePath, gavCollector)

        println("\n=== GavParser解析结果 ===")
        println("收集到的GAV数量: ${gavCollector.getGavs().size}")

        gavCollector.getGavs().forEach { gav ->
            println("GAV: ${gav.groupId}:${gav.artifactId}:${gav.version}:${gav.type} [path: ${gav.path}]")
        }

        // 4. 验证runtime依赖是否被包含
        val runtimeDepsFromEffectiveModel = effectiveModel?.dependencies?.filter {
            it.scope == "runtime"
        } ?: emptyList()

        val mysqlDepsFromEffectiveModel = runtimeDepsFromEffectiveModel.filter {
            it.groupId == "com.mysql" && it.artifactId == "mysql-connector-j"
        }

        println("\n=== Runtime作用域依赖验证 ===")
        println("从有效POM中发现的runtime依赖数量: ${runtimeDepsFromEffectiveModel.size}")
        println("MySQL Connector J依赖数量: ${mysqlDepsFromEffectiveModel.size}")

        // 验证runtime依赖是否被正确解析
        assertTrue("应该能找到MySQL Connector J的runtime依赖",
                  mysqlDepsFromEffectiveModel.isNotEmpty())

        val mysqlDep = mysqlDepsFromEffectiveModel.first()
        assertEquals("MySQL Connector J版本应该是8.0.33", "8.0.33", mysqlDep.version)
        assertEquals("MySQL Connector J作用域应该是runtime", "runtime", mysqlDep.scope)

        // 5. 验证GavCollector是否收集到了runtime依赖
        val mysqlGav = gavCollector.getGavs().find {
            it.groupId == "com.mysql" && it.artifactId == "mysql-connector-j"
        }

        assertNotNull("GavCollector应该收集到MySQL Connector J", mysqlGav)
        if (mysqlGav != null) {
            assertEquals("收集到的MySQL Connector J版本应该正确", "8.0.33", mysqlGav.version)
            assertEquals("收集到的MySQL Connector J类型应该是jar", "jar", mysqlGav.type)
        }

        println("\n=== 测试验证通过 ===")
        println("✓ Runtime作用域依赖被正确解析")
        println("✓ MySQL Connector J依赖被正确收集")
    }

    @Test
    fun testDependencyResolverWithRuntimeScope() {
        // 创建一个runtime依赖
        val runtimeDependency = Dependency()
        runtimeDependency.groupId = "com.mysql"
        runtimeDependency.artifactId = "mysql-connector-j"
        runtimeDependency.version = "8.0.33"
        runtimeDependency.scope = "runtime"
        runtimeDependency.type = "jar"

        // 使用DependencyResolver解析
        val dependencyResolver = DependencyResolver()
        val resolvedGav = dependencyResolver.resolve(runtimeDependency)

        println("=== DependencyResolver测试 ===")
        println("原始依赖: ${runtimeDependency.groupId}:${runtimeDependency.artifactId}:${runtimeDependency.version} [scope: ${runtimeDependency.scope}]")
        println("解析结果: ${resolvedGav.groupId}:${resolvedGav.artifactId}:${resolvedGav.version}:${resolvedGav.type}")
        println("解析路径: ${resolvedGav.path}")

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
    }

    @Test
    fun testMultiScopeDependencyPom() {
        // 创建包含多种作用域依赖的POM文件
        val multiScopePom = """
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0
         http://maven.apache.org/xsd/maven-4.0.0.xsd">
    <modelVersion>4.0.0</modelVersion>

    <groupId>com.ruoyi</groupId>
    <artifactId>stock-recommendations</artifactId>
    <version>3.8.6</version>
    <packaging>jar</packaging>

    <dependencies>
        <!-- compile scope (默认) -->
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter</artifactId>
            <version>2.7.0</version>
        </dependency>

        <!-- runtime scope (MySQL驱动) -->
        <dependency>
            <groupId>com.mysql</groupId>
            <artifactId>mysql-connector-j</artifactId>
            <version>8.0.33</version>
            <scope>runtime</scope>
        </dependency>

        <!-- provided scope (Servlet API) -->
        <dependency>
            <groupId>javax.servlet</groupId>
            <artifactId>javax.servlet-api</artifactId>
            <version>4.0.1</version>
            <scope>provided</scope>
        </dependency>

        <!-- test scope -->
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-test</artifactId>
            <version>2.7.0</version>
            <scope>test</scope>
        </dependency>

        <!-- import scope (在dependencyManagement中) -->
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-dependencies</artifactId>
            <version>2.7.0</version>
            <type>pom</type>
            <scope>import</scope>
        </dependency>
    </dependencies>
</project>
        """.trimIndent()

        val pomFile = tempDir.resolve("multi-scope-pom.xml").toFile()
        FileWriter(pomFile).use { it.write(multiScopePom) }

        // 使用EffectivePomResolver解析
        val effectivePomResolver = EffectivePomResolver()
        val effectiveModel = effectivePomResolver.buildEffectiveModel(pomFile)

        assertNotNull("有效POM模型不应该为null", effectiveModel)

        println("=== 多作用域依赖解析测试 ===")
        println("项目: ${effectiveModel?.groupId}:${effectiveModel?.artifactId}:${effectiveModel?.version}")

        // 按作用域分组统计依赖
        val dependenciesByScope = effectiveModel?.dependencies?.groupBy {
            it.scope ?: "compile"
        } ?: emptyMap()

        dependenciesByScope.forEach { (scope, deps) ->
            println("\n[$scope] 作用域依赖 (${deps.size}个):")
            deps.forEach { dep ->
                println("  - ${dep.groupId}:${dep.artifactId}:${dep.version}")
            }
        }

        // 验证runtime依赖是否存在
        val runtimeDeps = dependenciesByScope["runtime"] ?: emptyList()
        val mysqlRuntimeDep = runtimeDeps.find {
            it.groupId == "com.mysql" && it.artifactId == "mysql-connector-j"
        }

        assertTrue("应该能找到MySQL Connector J的runtime依赖", mysqlRuntimeDep != null)
        assertEquals("MySQL Connector J版本应该是8.0.33", "8.0.33", mysqlRuntimeDep?.version)

        // 验证所有依赖都被解析
        val totalDeps = dependenciesByScope.values.sumOf { it.size }
        println("\n总依赖数量: $totalDeps")
        assertTrue("应该解析到相当数量的依赖", totalDeps >= 4) // 至少应该有4个主要依赖
    }

    @org.junit.After
    fun tearDown() {
        // 清理临时文件
        tempDir.toFile().deleteRecursively()
        testLocalRepo.toFile().deleteRecursively()
    }
}