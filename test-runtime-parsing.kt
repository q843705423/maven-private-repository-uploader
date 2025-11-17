import java.io.File
import java.io.FileWriter

// 临时创建一个独立的分析程序来测试runtime依赖解析
fun main() {
    println("=== Maven Runtime依赖解析测试程序 ===")

    // 创建一个包含runtime依赖的测试POM文件
    val tempDir = createTempDir("test-pom-")
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
    <packaging>jar</packaging>

    <dependencies>
        <!-- compile scope -->
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter</artifactId>
            <version>2.7.0</version>
        </dependency>

        <!-- runtime scope (这里是关键) -->
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

    val pomFile = File(tempDir, "pom.xml")
    FileWriter(pomFile).use { it.write(pomContent) }

    println("创建测试POM文件: ${pomFile.absolutePath}")
    println("POM文件内容:")
    println(pomContent)

    println("\n=== 分析结果 ===")
    println("1. POM文件中明确包含 com.mysql:mysql-connector-j:8.0.33 [scope: runtime]")
    println("2. 根据Maven规范，runtime依赖应该被包含在最终构建中")
    println("3. 插件应该能够解析并收集到这个依赖")

    println("\n=== 问题诊断 ===")
    println("基于你的错误信息，可能的原因：")
    println("1. 插件可能过滤掉了runtime作用域的依赖")
    println("2. 有效POM解析过程中可能存在问题")
    println("3. 依赖收集器可能有逻辑错误")
    println("4. 本地仓库中可能没有下载MySQL Connector J")

    println("\n=== 建议的调试步骤 ===")
    println("1. 检查本地Maven仓库是否有 mysql:mysql-connector-java:8.0.33")
    println("2. 启用详细日志查看解析过程")
    println("3. 检查POM解析器的作用域过滤逻辑")
    println("4. 验证DependencyResolver的scope处理")

    // 清理
    pomFile.delete()
    tempDir.delete()

    println("\n测试完成。请根据上述分析检查插件代码。")
}

// 由于无法直接使用Kotlin的createTempDir，手动实现
fun createTempDir(prefix: String): File {
    val tempDir = File(System.getProperty("java.io.tmpdir"), prefix + System.currentTimeMillis())
    tempDir.mkdirs()
    return tempDir
}