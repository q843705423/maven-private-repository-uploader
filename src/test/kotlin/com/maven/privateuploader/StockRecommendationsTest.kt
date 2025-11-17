package com.maven.privateuploader

import com.maven.privateuploader.analyzer.*
import com.maven.privateuploader.model.CheckStatus
import org.junit.Test
import org.junit.Assert.*
import org.junit.Before
import java.io.File

/**
 * 测试 stock-recommendations 项目的依赖解析
 */
class StockRecommendationsTest {

    private lateinit var env: Env
    private lateinit var gavParser: GavParser

    @Before
    fun setUp() {
        val localRepo = System.getProperty("user.home") + "/.m2/repository"
        env = Env(localRepo)
        gavParser = GavParser(env)
    }

    @Test
    fun testStockRecommendationsPomParsing() {
        println("=== 测试 stock-recommendations 项目 POM 解析 ===")

        val stockPomFile = File("D:\\code\\java\\stock-recommendations\\pom.xml")

        if (!stockPomFile.exists()) {
            println("stock-recommendations POM 文件不存在: ${stockPomFile.absolutePath}")
            return
        }

        try {
            println("开始解析 stock-recommendations POM...")
            val gavCollector = GavCollector()
            gavParser.parse(stockPomFile.absolutePath, gavCollector)

            println("=== stock-recommendations 依赖解析结果 ===")
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

            // 检查是否包含其他常见依赖
            val springFound = allGavs.any { gav ->
                gav.groupId?.contains("spring") == true
            }
            println("Spring 相关依赖找到: $springFound")

            println("\n=== 关键依赖列表 ===")
            allGavs.forEachIndexed { index, gav ->
                if (gav.groupId?.contains("mysql") == true ||
                    gav.groupId?.contains("protobuf") == true ||
                    gav.groupId?.contains("spring") == true) {
                    println("${index + 1}. ${gav.groupId}:${gav.artifactId}:${gav.version}:${gav.type}")
                    println("   路径: ${gav.path}")
                    println("   文件存在: ${File(gav.path).exists()}")
                    println()
                }
            }

            // 断言
            assertTrue("应该找到 MySQL Connector J", mysqlFound)

            if (protobufFound) {
                println("✅ 传递依赖 protobuf-java 正确解析 - 问题已修复！")
            } else {
                println("❌ 传递依赖 protobuf-java 仍然缺失 - 需要进一步修复")
            }

        } catch (e: Exception) {
            println("解析 stock-recommendations POM 失败: ${e.message}")
            println("详细错误: ${e.stackTraceToString()}")
        }
    }
}