package com.maven.privateuploader.analyzer

import org.apache.maven.model.Plugin
import org.junit.Test
import org.junit.Assert.*
import java.io.File

/**
 * PluginResolver 测试类
 */
class PluginResolverTest {

    @Test
    fun testResolveWithValidPlugin() {
        val resolver = PluginResolver()
        val plugin = Plugin()
        plugin.groupId = "org.apache.maven.plugins"
        plugin.artifactId = "maven-compiler-plugin"
        plugin.version = "3.8.1"

        val result = resolver.resolve(plugin)

        assertNotNull(result)
        assertEquals("org.apache.maven.plugins", result?.groupId)
        assertEquals("maven-compiler-plugin", result?.artifactId)
        assertEquals("3.8.1", result?.version)
        assertEquals("jar", result?.type)
        assertTrue(result?.path?.endsWith("maven-compiler-plugin-3.8.1.jar") ?: false)
    }

    @Test
    fun testResolveWithNullGroupId() {
        val resolver = PluginResolver()
        val plugin = Plugin()
        plugin.groupId = null
        plugin.artifactId = "maven-surefire-plugin"
        plugin.version = "2.22.2"

        val result = resolver.resolve(plugin)

        assertNotNull(result)
        assertEquals("org.apache.maven.plugins", result?.groupId) // 应该使用默认值
        assertEquals("maven-surefire-plugin", result?.artifactId)
        assertEquals("2.22.2", result?.version)
    }

    @Test
    fun testResolveWithEmptyGroupId() {
        val resolver = PluginResolver()
        val plugin = Plugin()
        plugin.groupId = ""
        plugin.artifactId = "maven-surefire-plugin"
        plugin.version = "2.22.2"

        val result = resolver.resolve(plugin)

        assertNotNull(result)
        assertEquals("org.apache.maven.plugins", result?.groupId) // 应该使用默认值
        assertEquals("maven-surefire-plugin", result?.artifactId)
        assertEquals("2.22.2", result?.version)
    }

    @Test
    fun testResolveWithNullPlugin() {
        val resolver = PluginResolver()

        val result = resolver.resolve(null)

        assertNull(result) // 应该返回 null
    }

    @Test
    fun testResolveWithNullArtifactId() {
        val resolver = PluginResolver()
        val plugin = Plugin()
        plugin.groupId = "org.apache.maven.plugins"
        plugin.artifactId = null
        plugin.version = "3.8.1"

        val result = resolver.resolve(plugin)

        assertNull(result) // 应该返回 null，因为 artifactId 是必需的
    }

    @Test
    fun testResolveWithNullVersion() {
        val resolver = PluginResolver()
        val plugin = Plugin()
        plugin.groupId = "org.apache.maven.plugins"
        plugin.artifactId = "maven-compiler-plugin"
        plugin.version = null

        val result = resolver.resolve(plugin)

        assertNull(result) // 应该返回 null，因为 version 是必需的
    }

    @Test
    fun testResolveWithEmptyArtifactId() {
        val resolver = PluginResolver()
        val plugin = Plugin()
        plugin.groupId = "org.apache.maven.plugins"
        plugin.artifactId = ""
        plugin.version = "3.8.1"

        val result = resolver.resolve(plugin)

        assertNull(result) // 应该返回 null，因为 artifactId 是必需的
    }

    @Test
    fun testResolveWithEmptyVersion() {
        val resolver = PluginResolver()
        val plugin = Plugin()
        plugin.groupId = "org.apache.maven.plugins"
        plugin.artifactId = "maven-compiler-plugin"
        plugin.version = ""

        val result = resolver.resolve(plugin)

        assertNull(result) // 应该返回 null，因为 version 是必需的
    }
}