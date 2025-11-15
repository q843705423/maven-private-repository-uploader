package org.jetbrains.plugins.template.actions

import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.testFramework.TestDataPath
import com.intellij.util.ui.EDT
import org.jetbrains.plugins.template.MyBundle
import javax.swing.SwingUtilities

@TestDataPath("\$CONTENT_ROOT/src/test/testData")
class HelloWorldActionTest : BasePlatformTestCase() {

    private lateinit var helloWorldAction: HelloWorldAction

    override fun setUp() {
        super.setUp()
        helloWorldAction = HelloWorldAction()
    }

    fun testActionCreation() {
        assertNotNull(helloWorldAction)
    }

    fun testActionUpdateWithProject() {
        val actionEvent = createTestActionEvent()

        EDT.runBlocking {
            helloWorldAction.update(actionEvent)
            assertTrue(actionEvent.presentation.isEnabledAndVisible)
        }
    }

    fun testActionUpdateWithoutProject() {
        val actionEvent = AnActionEvent.createFromAnAction(
            helloWorldAction,
            null,
            "",
            com.intellij.openapi.actionSystem.DataContext { _ -> null }
        )

        EDT.runBlocking {
            helloWorldAction.update(actionEvent)
            assertFalse(actionEvent.presentation.isEnabledAndVisible)
        }
    }

    fun testBundleMessages() {
        assertEquals("Hello World", MyBundle.message("hello.world.title"))
        assertEquals("Hello, World!", MyBundle.message("hello.world.default.message"))
        assertEquals("Enter your message (default: {0}):", MyBundle.message("hello.world.prompt", "default"))
        assertEquals("Test Message", MyBundle.message("hello.world.display", "Test Message"))
    }

    private fun createTestActionEvent(): AnActionEvent {
        return AnActionEvent.createFromAnAction(
            helloWorldAction,
            null,
            "",
            com.intellij.openapi.actionSystem.DataContext { key ->
                when (key) {
                    com.intellij.openapi.actionSystem.CommonDataKeys.PROJECT -> project
                    else -> null
                }
            }
        )
    }
}