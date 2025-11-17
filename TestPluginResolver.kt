import com.maven.privateuploader.analyzer.PluginResolver
import org.apache.maven.model.Plugin
import java.io.File

/**
 * 简单测试 PluginResolver 的修复效果
 */
fun main() {
    val resolver = PluginResolver()

    println("测试 PluginResolver 修复效果:")
    println("=" * 50)

    // 测试1: 正常插件
    println("测试1: 正常插件")
    val normalPlugin = Plugin()
    normalPlugin.groupId = "org.apache.maven.plugins"
    normalPlugin.artifactId = "maven-compiler-plugin"
    normalPlugin.version = "3.8.1"

    val result1 = resolver.resolve(normalPlugin)
    println("结果: ${result1}")
    println()

    // 测试2: null groupId
    println("测试2: null groupId")
    val nullGroupIdPlugin = Plugin()
    nullGroupIdPlugin.groupId = null
    nullGroupIdPlugin.artifactId = "maven-surefire-plugin"
    nullGroupIdPlugin.version = "2.22.2"

    val result2 = resolver.resolve(nullGroupIdPlugin)
    println("结果: ${result2}")
    println()

    // 测试3: null 插件对象
    println("测试3: null 插件对象")
    val result3 = resolver.resolve(null)
    println("结果: ${result3}")
    println()

    // 测试4: null artifactId
    println("测试4: null artifactId")
    val nullArtifactIdPlugin = Plugin()
    nullArtifactIdPlugin.groupId = "org.apache.maven.plugins"
    nullArtifactIdPlugin.artifactId = null
    nullArtifactIdPlugin.version = "3.8.1"

    val result4 = resolver.resolve(nullArtifactIdPlugin)
    println("结果: ${result4}")
    println()

    // 测试5: null version
    println("测试5: null version")
    val nullVersionPlugin = Plugin()
    nullVersionPlugin.groupId = "org.apache.maven.plugins"
    nullVersionPlugin.artifactId = "maven-compiler-plugin"
    nullVersionPlugin.version = null

    val result5 = resolver.resolve(nullVersionPlugin)
    println("结果: ${result5}")
    println()

    println("所有测试完成！")
}

private operator fun String.times(n: Int) = this.repeat(n)