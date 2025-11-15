package com.maven.privateuploader

import com.maven.privateuploader.model.CheckStatus
import com.maven.privateuploader.model.DependencyInfo
import org.junit.Test
import org.junit.Assert.*

/**
 * 依赖信息测试
 */
class DependencyInfoTest {

    @Test
    fun `test dependency info creation`() {
        val dependency = DependencyInfo(
            groupId = "org.junit.jupiter",
            artifactId = "junit-jupiter",
            version = "5.8.2",
            packaging = "jar",
            localPath = "/path/to/junit-jupiter-5.8.2.jar"
        )

        assertEquals("org.junit.jupiter", dependency.groupId)
        assertEquals("junit-jupiter", dependency.artifactId)
        assertEquals("5.8.2", dependency.version)
        assertEquals("jar", dependency.packaging)
        assertEquals("/path/to/junit-jupiter-5.8.2.jar", dependency.localPath)
        assertEquals(CheckStatus.UNKNOWN, dependency.checkStatus)
        assertFalse(dependency.selected)
    }

    @Test
    fun `test GAV coordinate`() {
        val dependency = DependencyInfo(
            groupId = "org.springframework",
            artifactId = "spring-core",
            version = "5.3.21"
        )

        assertEquals("org.springframework:spring-core:5.3.21", dependency.getGAV())
    }

    @Test
    fun `test full coordinate`() {
        val dependency = DependencyInfo(
            groupId = "org.springframework",
            artifactId = "spring-core",
            version = "5.3.21",
            packaging = "jar"
        )

        assertEquals("org.springframework:spring-core:5.3.21:jar", dependency.getCoordinate())
    }

    @Test
    fun `test equals and hashCode`() {
        val dep1 = DependencyInfo(
            groupId = "org.example",
            artifactId = "example-lib",
            version = "1.0.0",
            packaging = "jar"
        )

        val dep2 = DependencyInfo(
            groupId = "org.example",
            artifactId = "example-lib",
            version = "1.0.0",
            packaging = "jar"
        )

        val dep3 = DependencyInfo(
            groupId = "org.example",
            artifactId = "example-lib",
            version = "2.0.0",
            packaging = "jar"
        )

        assertEquals(dep1, dep2)
        assertEquals(dep1.hashCode(), dep2.hashCode())
        assertNotEquals(dep1, dep3)
    }

    @Test
    fun `test toString returns GAV`() {
        val dependency = DependencyInfo(
            groupId = "org.example",
            artifactId = "example-lib",
            version = "1.0.0"
        )

        assertEquals("org.example:example-lib:1.0.0", dependency.toString())
    }
}