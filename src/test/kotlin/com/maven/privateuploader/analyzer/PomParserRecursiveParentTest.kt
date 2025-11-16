package com.maven.privateuploader.analyzer

import com.maven.privateuploader.model.CheckStatus
import org.junit.Test
import org.junit.Assert.*
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * PomParser 递归父 POM 测试
 * 
 * 测试场景：验证 PomParser 能够正确解析 POM 文件，并递归解析父 POM 的父 POM
 * 
 * 测试用例：ruoyi stock-recommendations 项目
 * - 主 POM: com.ruoyi:stock-recommendations:3.8.6
 * - 父 POM: org.springframework.boot:spring-boot-starter-parent:2.5.15
 * - 父 POM 的父 POM: org.springframework.boot:spring-boot-dependencies:2.5.15
 */
class PomParserRecursiveParentTest {

    private val pomParser = PomParserHelper()

    /**
     * 测试解析 ruoyi stock-recommendations POM 文件
     * 验证能够正确解析父 POM 信息，并且 parentToDependencyInfo 能够正确设置 localPath 和 packaging
     */
    @Test
    fun `test parse ruoyi stock-recommendations pom with recursive parent`() {
        // 获取测试资源文件
        val testPomFile = getTestResourceFile("pom/ruoyi-stock-recommendations.xml")
        assertNotNull("测试 POM 文件应该存在", testPomFile)
        assertTrue("测试 POM 文件应该可读", testPomFile!!.exists())

        // 解析主 POM
        val pomInfo = pomParser.parsePom(testPomFile!!)
        assertNotNull("应该能够解析 POM 文件", pomInfo)
        
        // 验证主 POM 基本信息
        assertEquals("com.ruoyi", pomInfo?.groupId)
        assertEquals("stock-recommendations", pomInfo?.artifactId)
        assertEquals("3.8.6", pomInfo?.version)

        // 验证父 POM 信息
        val parent = pomInfo?.parent
        assertNotNull("应该能够解析父 POM 信息", parent)
        assertEquals("org.springframework.boot", parent?.groupId)
        assertEquals("spring-boot-starter-parent", parent?.artifactId)
        assertEquals("2.5.15", parent?.version)

        // 创建临时目录结构，模拟本地 Maven 仓库
        val tempRepo = createTempMavenRepository()
        try {
            // 复制测试 POM 文件到临时仓库的相应位置
            val parentPomFile = createParentPomInRepo(tempRepo, parent!!)
            val grandParentPomFile = createGrandParentPomInRepo(tempRepo)

            // 测试 parentToDependencyInfo 方法
            // 注意：这里需要修改 PomParser 以支持自定义仓库路径，或者使用反射/模拟
            // 为了测试，我们直接验证 parentToDependencyInfo 的逻辑
            
            // 使用反射或创建一个测试用的 PomParser 实例，能够指定仓库路径
            val dependencyInfo = testParentToDependencyInfo(parent, parentPomFile, tempRepo)
            
            assertNotNull("parentToDependencyInfo 应该返回非空值", dependencyInfo)
            assertEquals("org.springframework.boot", dependencyInfo?.groupId)
            assertEquals("spring-boot-starter-parent", dependencyInfo?.artifactId)
            assertEquals("2.5.15", dependencyInfo?.version)
            assertEquals("packaging 应该设置为 pom", "pom", dependencyInfo?.packaging)
            assertTrue("localPath 应该不为空", dependencyInfo?.localPath?.isNotBlank() == true)
            assertTrue("localPath 应该指向存在的文件", File(dependencyInfo!!.localPath).exists())
            assertEquals(CheckStatus.UNKNOWN, dependencyInfo.checkStatus)
            assertFalse(dependencyInfo.selected)

            // 验证父 POM 的父 POM（spring-boot-dependencies）
            val parentPomInfo = pomParser.parsePom(File(dependencyInfo.localPath))
            assertNotNull("应该能够解析父 POM 文件", parentPomInfo)
            
            val grandParent = parentPomInfo?.parent
            assertNotNull("父 POM 应该也有父 POM（spring-boot-dependencies）", grandParent)
            assertEquals("org.springframework.boot", grandParent?.groupId)
            assertEquals("spring-boot-dependencies", grandParent?.artifactId)
            assertEquals("2.5.15", grandParent?.version)

            // 验证父 POM 的父 POM 也能转换为 DependencyInfo
            val grandParentDependencyInfo = testParentToDependencyInfo(grandParent!!, grandParentPomFile, tempRepo)
            assertNotNull("父 POM 的父 POM 也应该能够转换为 DependencyInfo", grandParentDependencyInfo)
            assertEquals("父 POM 的父 POM 的 packaging 应该设置为 pom", "pom", grandParentDependencyInfo?.packaging)
            assertTrue("父 POM 的父 POM 的 localPath 应该不为空", 
                grandParentDependencyInfo?.localPath?.isNotBlank() == true)
            assertTrue("父 POM 的父 POM 的 localPath 应该指向存在的文件", 
                File(grandParentDependencyInfo!!.localPath).exists())

        } finally {
            // 清理临时目录
            deleteTempDirectory(tempRepo)
        }
    }

    /**
     * 测试递归解析父 POM 链
     * 验证能够从主 POM 递归解析到父 POM 的父 POM
     */
    @Test
    fun `test recursive parent pom parsing`() {
        val testPomFile = getTestResourceFile("pom/ruoyi-stock-recommendations.xml")
        assertNotNull("测试 POM 文件应该存在", testPomFile)

        val tempRepo = createTempMavenRepository()
        try {
            // 解析主 POM
            val pomInfo = pomParser.parsePom(testPomFile!!)
            assertNotNull("应该能够解析主 POM", pomInfo)

            val parent = pomInfo?.parent
            assertNotNull("主 POM 应该有父 POM", parent)

            // 创建父 POM 文件
            val parentPomFile = createParentPomInRepo(tempRepo, parent!!)
            val grandParentPomFile = createGrandParentPomInRepo(tempRepo)

            // 解析父 POM
            val parentDependencyInfo = testParentToDependencyInfo(parent, parentPomFile, tempRepo)
            assertNotNull("父 POM 应该能够转换为 DependencyInfo", parentDependencyInfo)
            assertEquals("pom", parentDependencyInfo?.packaging)

            // 解析父 POM 的 POM 文件
            val parentPomInfo = pomParser.parsePom(File(parentDependencyInfo!!.localPath))
            assertNotNull("应该能够解析父 POM 的 POM 文件", parentPomInfo)

            // 验证父 POM 的父 POM
            val grandParent = parentPomInfo?.parent
            assertNotNull("父 POM 应该有父 POM（spring-boot-dependencies）", grandParent)
            assertEquals("org.springframework.boot", grandParent?.groupId)
            assertEquals("spring-boot-dependencies", grandParent?.artifactId)
            assertEquals("2.5.15", grandParent?.version)

            // 验证父 POM 的父 POM 也能转换为 DependencyInfo
            val grandParentDependencyInfo = testParentToDependencyInfo(grandParent!!, grandParentPomFile, tempRepo)
            assertNotNull("父 POM 的父 POM 应该能够转换为 DependencyInfo", grandParentDependencyInfo)
            assertEquals("pom", grandParentDependencyInfo?.packaging)
            assertTrue("父 POM 的父 POM 的 localPath 应该指向存在的文件",
                File(grandParentDependencyInfo!!.localPath).exists())

        } finally {
            deleteTempDirectory(tempRepo)
        }
    }

    /**
     * 测试 parentToDependencyInfo 方法正确设置 packaging 和 localPath
     */
    @Test
    fun `test parentToDependencyInfo sets packaging and localPath correctly`() {
        val tempRepo = createTempMavenRepository()
        try {
            val parent = PomParserHelper.ParentInfo(
                groupId = "org.springframework.boot",
                artifactId = "spring-boot-starter-parent",
                version = "2.5.15",
                relativePath = null
            )

            val parentPomFile = createParentPomInRepo(tempRepo, parent)
            val dependencyInfo = testParentToDependencyInfo(parent, parentPomFile, tempRepo)

            assertNotNull("应该能够创建 DependencyInfo", dependencyInfo)
            assertEquals("packaging 必须设置为 pom", "pom", dependencyInfo?.packaging)
            assertTrue("localPath 必须不为空", dependencyInfo?.localPath?.isNotBlank() == true)
            assertTrue("localPath 必须指向存在的文件", File(dependencyInfo!!.localPath).exists())
            assertEquals("groupId 应该正确", "org.springframework.boot", dependencyInfo.groupId)
            assertEquals("artifactId 应该正确", "spring-boot-starter-parent", dependencyInfo.artifactId)
            assertEquals("version 应该正确", "2.5.15", dependencyInfo.version)

        } finally {
            deleteTempDirectory(tempRepo)
        }
    }

    // ========== 辅助方法 ==========

    /**
     * 获取测试资源文件
     */
    private fun getTestResourceFile(path: String): File? {
        val resourceUrl = javaClass.classLoader.getResource(path)
        return if (resourceUrl != null) {
            File(resourceUrl.toURI())
        } else {
            // 如果从 classpath 找不到，尝试从项目目录查找
            val projectFile = File("src/test/testData/$path")
            if (projectFile.exists()) projectFile else null
        }
    }

    /**
     * 创建临时 Maven 仓库目录结构
     */
    private fun createTempMavenRepository(): File {
        val tempDir = Files.createTempDirectory("maven-repo-test").toFile()
        return tempDir
    }

    /**
     * 在临时仓库中创建父 POM 文件
     */
    private fun createParentPomInRepo(repo: File, parent: PomParser.ParentInfo): File {
        val parentDir = File(repo, 
            "${parent.groupId.replace('.', '/')}/${parent.artifactId}/${parent.version}")
        parentDir.mkdirs()
        
        val parentPomFile = File(parentDir, "${parent.artifactId}-${parent.version}.pom")
        val sourcePomFile = getTestResourceFile("pom/spring-boot-starter-parent-2.5.15.xml")
        
        if (sourcePomFile != null && sourcePomFile.exists()) {
            Files.copy(sourcePomFile.toPath(), parentPomFile.toPath(), StandardCopyOption.REPLACE_EXISTING)
        } else {
            // 如果测试文件不存在，创建一个基本的 POM 文件
            parentPomFile.writeText("""<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0">
    <modelVersion>4.0.0</modelVersion>
    <parent>
        <groupId>org.springframework.boot</groupId>
        <artifactId>spring-boot-dependencies</artifactId>
        <version>2.5.15</version>
    </parent>
    <groupId>${parent.groupId}</groupId>
    <artifactId>${parent.artifactId}</artifactId>
    <version>${parent.version}</version>
    <packaging>pom</packaging>
</project>""")
        }
        
        return parentPomFile
    }

    /**
     * 在临时仓库中创建父 POM 的父 POM 文件（spring-boot-dependencies）
     */
    private fun createGrandParentPomInRepo(repo: File): File {
        val groupId = "org.springframework.boot"
        val artifactId = "spring-boot-dependencies"
        val version = "2.5.15"
        
        val grandParentDir = File(repo, 
            "${groupId.replace('.', '/')}/$artifactId/$version")
        grandParentDir.mkdirs()
        
        val grandParentPomFile = File(grandParentDir, "$artifactId-$version.pom")
        val sourcePomFile = getTestResourceFile("pom/spring-boot-dependencies-2.5.15.xml")
        
        if (sourcePomFile != null && sourcePomFile.exists()) {
            Files.copy(sourcePomFile.toPath(), grandParentPomFile.toPath(), StandardCopyOption.REPLACE_EXISTING)
        } else {
            // 如果测试文件不存在，创建一个基本的 POM 文件
            grandParentPomFile.writeText("""<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0">
    <modelVersion>4.0.0</modelVersion>
    <groupId>$groupId</groupId>
    <artifactId>$artifactId</artifactId>
    <version>$version</version>
    <packaging>pom</packaging>
</project>""")
        }
        
        return grandParentPomFile
    }

    /**
     * 测试 parentToDependencyInfo 方法
     * 由于 PomParser.buildLocalPomPath 使用系统属性，我们需要创建一个能够指定仓库路径的版本
     */
    private fun testParentToDependencyInfo(
        parent: PomParserHelper.ParentInfo,
        expectedPomFile: File,
        tempRepo: File
    ): com.maven.privateuploader.model.DependencyInfo? {
        // 直接使用 PomParser 的方法，但需要确保文件存在
        // 由于 PomParser.buildLocalPomPath 使用系统属性，我们需要临时修改系统属性
        val originalMavenRepo = System.getProperty("maven.repo.local")
        
        try {
            // 设置临时仓库路径
            System.setProperty("maven.repo.local", tempRepo.absolutePath)
            
            // 验证文件确实存在于预期位置
            val expectedPath = File(tempRepo,
                "${parent.groupId.replace('.', '/')}/${parent.artifactId}/${parent.version}/${parent.artifactId}-${parent.version}.pom")
            assertTrue("POM 文件应该存在于预期位置: ${expectedPath.absolutePath}", expectedPath.exists())
            
            // 调用 parentToDependencyInfo
            val dependencyInfo = pomParser.parentToDependencyInfo(parent)
            
            return dependencyInfo
        } finally {
            // 恢复原始系统属性
            if (originalMavenRepo != null) {
                System.setProperty("maven.repo.local", originalMavenRepo)
            } else {
                System.clearProperty("maven.repo.local")
            }
        }
    }

    /**
     * 删除临时目录
     */
    private fun deleteTempDirectory(dir: File) {
        if (dir.exists()) {
            dir.deleteRecursively()
        }
    }
}

