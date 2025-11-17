package com.maven.privateuploader.action

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.vfs.VirtualFile
import com.maven.privateuploader.service.DependencyUploadService
import com.maven.privateuploader.ui.DependencyUploadDialog
import org.jetbrains.idea.maven.project.MavenProjectsManager

/**
 * 上传Maven依赖到私仓的Action
 * 在项目根目录右键菜单中显示
 */
class UploadMavenDependenciesAction : AnAction() {

    init {
        templatePresentation.text = "上传Maven依赖到私仓..."
        templatePresentation.description = "分析项目Maven依赖并上传到私有仓库"
    }

    override fun getActionUpdateThread(): ActionUpdateThread {
        // 在EDT线程中更新Action状态
        return ActionUpdateThread.EDT
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project: Project = e.getRequiredData(CommonDataKeys.PROJECT)
        val uploadService = project.service<DependencyUploadService>()

        // 🔍 调试信息：显示当前项目信息
        println("=== 🔍 UploadMavenDependenciesAction 调试信息 ===")
        println("当前项目: ${project.name}")
        println("项目路径: ${project.basePath}")

        // 检查是否为Maven项目
        val mavenManager = MavenProjectsManager.getInstance(project)
        val isMavenProject = mavenManager.hasProjects()

        println("是否为Maven项目: $isMavenProject")

        if (isMavenProject) {
            val mavenProjects = mavenManager.projects
            println("检测到的Maven项目数量: ${mavenProjects.size}")

            mavenProjects.forEachIndexed { index, mavenProject ->
                println("Maven项目 #$index:")
                println("  - GroupId: ${mavenProject.mavenId.groupId}")
                println("  - ArtifactId: ${mavenProject.mavenId.artifactId}")
                println("  - Version: ${mavenProject.mavenId.version}")
                println("  - POM路径: ${mavenProject.file.path}")
                println("  - 打包类型: ${mavenProject.packaging}")

                // 检查是否包含MySQL驱动
                val dependencies = mavenProject.dependencies
                val mysqlDeps = dependencies.filter {
                    it.groupId?.contains("mysql", ignoreCase = true) == true ||
                    it.artifactId?.contains("mysql", ignoreCase = true) == true
                }

                if (mysqlDeps.isNotEmpty()) {
                    println("  - ✅ 发现MySQL依赖: ${mysqlDeps.size}个")
                    mysqlDeps.forEach { dep ->
                        println("    * ${dep.groupId}:${dep.artifactId}:${dep.version} [scope: ${dep.scope}]")
                    }
                } else {
                    println("  - ❌ 没有发现MySQL依赖")
                }
            }
        }
        println("=== 调试信息结束 ===")

        if (!uploadService.isMavenProject(project)) {
            Messages.showErrorDialog(
                project,
                "当前项目不是Maven项目，无法使用此功能。\n\n请确保当前项目包含pom.xml文件或已在IDEA中正确导入为Maven项目。",
                "不是Maven项目"
            )
            return
        }

        // 打开上传对话框
        val dialog = DependencyUploadDialog(project)
        dialog.show()
    }

    override fun update(e: AnActionEvent) {
        val project = e.project
        val presentation = e.presentation

        // 只有在有项目时才启用
        if (project == null) {
            presentation.isEnabledAndVisible = false
            return
        }

        // 检查是否为Maven项目
        val mavenManager = MavenProjectsManager.getInstance(project)
        val isMavenProject = mavenManager.hasProjects()

        presentation.isEnabledAndVisible = isMavenProject

        // 更新描述文本
        if (isMavenProject) {
            val moduleCount = mavenManager.projects.size
            presentation.description = "分析项目的 $moduleCount 个Maven模块依赖并上传到私有仓库"
        }
    }
}