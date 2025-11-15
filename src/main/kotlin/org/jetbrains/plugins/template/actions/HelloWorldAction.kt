package org.jetbrains.plugins.template.actions

import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import org.jetbrains.plugins.template.MyBundle

class HelloWorldAction : AnAction() {

    override fun actionPerformed(e: AnActionEvent) {
        val project: Project? = e.project
        val defaultMessage = MyBundle.message("hello.world.default.message")
        val title = MyBundle.message("hello.world.title")

        val message = Messages.showInputDialog(
            project,
            MyBundle.message("hello.world.prompt", defaultMessage),
            title,
            Messages.getQuestionIcon(),
            defaultMessage,
            null
        )

        if (!message.isNullOrBlank()) {
            Messages.showInfoMessage(
                project,
                MyBundle.message("hello.world.display", message),
                title
            )
        }
    }

    override fun update(e: AnActionEvent) {
        val project = e.project
        e.presentation.isEnabledAndVisible = project != null
    }
}