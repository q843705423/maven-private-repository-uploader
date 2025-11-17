package com.maven.privateuploader

import com.maven.privateuploader.analyzer.*
import com.maven.privateuploader.model.CheckStatus
import org.junit.Test
import org.junit.Assert.*
import org.junit.Before
import java.io.File

/**
 * 测试传递依赖解析问题
 */
class TransitiveDependencyTest {

    private lateinit var env: Env
    private lateinit var gavParser: GavParser

    @Before
    fun setUp() {
        val localRepo = System.getProperty("user.home") + "/.m2/repository"
        env = Env(localRepo)
        gavParser = GavParser(env)
    }

    @Test
    fun testMysqlConnectorJTransitiveDependencies() {
        println("=== 测试 MySQL Connector J 传递依赖解析 ===")

        // 创建一个测试 POM，只包含 MySQL Connector J 依赖
        val testPomContent = """
            <?xml version="1.0" encoding="UTF-8"?>
            <project xmlns="http://maven.apache.org/POM/4.0.0"
                     xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
                     xsi:schemaLocation="http://maven.apache.org/POM/4.0.0
                     http://maven.apache.org/xsd/maven-4.0.0.xsd">
                <modelVersion>4.0.0</modelVersion>
                <groupId>com.test</groupId>
                <artifactId>test-mysql-transitive</artifactId>
                <version>1.0.0</version>
                <packaging>jar</packaging>

                <dependencies>
                    <dependency>
                        <groupId>com.mysql</groupId>
                        <artifactId>mysql-connector-j</artifactId>
                        <version>8.0.33</version>
                    </dependency>
                </dependencies>
            </project>
        """.trimIndent()

        try {
            println("创建测试 POM 文件...")
            val tempPomFile = File.createTempFile("test-mysql-transitive", ".xml")
            tempPomFile.writeText(testPomContent)
            println("测试 POM 文件路径: ${tempPomFile.absolutePath}")

            // 使用 GavCollector 收集所有依赖
            val gavCollector = GavCollector()
            gavParser.parse(tempPomFile.absolutePath, gavCollector)

            println("=== 依赖解析结果 ===")
            val allGavs = gavCollector.getGavs()
            println("总共解析到 ${allGavs.size} 个依赖")

            // 检查是否包含 MySQL Connector J
            val mysqlFound = allGavs.any { gav ->
                gav.groupId == "com.mysql" && gav.artifactId == "mysql-connector-j"
            }
            println("MySQL Connector J 找到: $mysqlFound")

            // 检查是否包含传递依赖 protobuf-java
            val protobufFound = allGavs.any { gav ->
                gav.groupId == "com.google.protobuf" && gav.artifactId == "protobuf-java"
            }
            println("protobuf-java 找到: $protobufFound")

            // 检查是否包含传递依赖 oci-java-sdk-common
            val ociFound = allGavs.any { gav ->
                gav.groupId == "com.oracle.oci.sdk" && gav.artifactId == "oci-java-sdk-common"
            }
            println("oci-java-sdk-common 找到: $ociFound")

            println("\n=== 所有依赖列表 ===")
            allGavs.forEachIndexed { index, gav ->
                println("${index + 1}. ${gav.groupId}:${gav.artifactId}:${gav.version}:${gav.type}")
                println("   路径: ${gav.path}")
                println("   文件存在: ${File(gav.path).exists()}")
                println()
            }

            // 断言
            assertTrue("应该找到 MySQL Connector J", mysqlFound)

            // 这个断言可能会失败，验证我们的假设
            if (!protobufFound) {
                println("❌ 确认问题：传递依赖 protobuf-java 没有被解析到！")
            } else {
                println("✅ 传递依赖 protobuf-java 正确解析")
            }

            tempPomFile.deleteOnExit()

        } catch (e: Exception) {
            println("解析失败: ${e.message}")
            println("详细错误: ${e.stackTraceToString()}")
        }
    }

    @Test
    fun testDirectMysqlPomParsing() {
        println("=== 直接解析 MySQL Connector J 的 POM ===")

        val mysqlPomFile = File("C:\\Users\\admin\\.m2\\repository\\com\\mysql\\mysql-connector-j\\8.0.33\\mysql-connector-j-8.0.33.pom")

        if (!mysqlPomFile.exists()) {
            println("MySQL POM 文件不存在: ${mysqlPomFile.absolutePath}")
            return
        }

        try {
            val gavCollector = GavCollector()
            gavParser.parse(mysqlPomFile.absolutePath, gavCollector)

            println("=== 直接解析 MySQL POM 的结果 ===")
            val allGavs = gavCollector.getGavs()
            println("总共解析到 ${allGavs.size} 个依赖")

            allGavs.forEachIndexed { index, gav ->
                println("${index + 1}. ${gav.groupId}:${gav.artifactId}:${gav.version}:${gav.type}")
            }

            // 检查是否包含传递依赖
            val protobufFound = allGavs.any { gav ->
                gav.groupId == "com.google.protobuf" && gav.artifactId == "protobuf-java"
            }

            println("protobuf-java 作为传递依赖找到: $protobufFound")

        } catch (e: Exception) {
            println("直接解析 MySQL POM 失败: ${e.message}")
            println("详细错误: ${e.stackTraceToString()}")
        }
    }
}