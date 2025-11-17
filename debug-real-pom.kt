import java.io.File

/**
 * 调试脚本：解析真实的项目POM文件
 */
fun main() {
    val realPomPath = "D:\\code\\java\\stock-recommendations\\pom.xml"
    val pomFile = File(realPomPath)

    println("=== 解析真实项目POM文件 ===")
    println("POM文件路径: $realPomPath")
    println("POM文件存在: ${pomFile.exists()}")
    println("POM文件大小: ${pomFile.length()} bytes")

    if (pomFile.exists()) {
        println("\n=== POM文件内容（MySQL驱动部分）===")
        val lines = pomFile.readLines()
        lines.forEachIndexed { index, line ->
            if (line.contains("mysql") || line.contains("MySQL")) {
                println("第${index + 1}行: $line")
            }
        }

        // 显示依赖部分
        println("\n=== 依赖声明部分 ===")
        var inDependencies = false
        lines.forEachIndexed { index, line ->
            if (line.contains("<dependencies>")) {
                inDependencies = true
            } else if (line.contains("</dependencies>")) {
                inDependencies = false
            }

            if (inDependencies && line.contains("mysql")) {
                println("第${index + 1}行: $line")
            }
        }

        println("\n=== 接下来使用GavParser解析 ===")
        println("请检查插件日志输出，特别关注MySQL驱动的发现信息")
        println("寻找 🔥 emoji 表示MySQL驱动被找到")
    } else {
        println("❌ POM文件不存在！")
    }
}