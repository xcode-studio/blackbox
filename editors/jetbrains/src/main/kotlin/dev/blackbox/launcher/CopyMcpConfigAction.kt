// Tools | Blackbox: Copy MCP Config — the entry to add under "mcpServers"
// in an MCP client's config (same as the VS Code command).

package dev.blackbox.launcher

import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.openapi.project.DumbAware
import java.awt.datatransfer.StringSelection

class CopyMcpConfigAction : AnAction(), DumbAware {
    override fun getActionUpdateThread() = ActionUpdateThread.BGT

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project
        // Finding node runs the login shell; keep it off the UI thread.
        ApplicationManager.getApplication().executeOnPooledThread {
            val config = mapOf("blackbox" to mapOf("command" to McpInstall.findNode(), "args" to listOf(McpInstall.launcher().toString())))
            val text = com.google.gson.GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create().toJson(config)
            ApplicationManager.getApplication().invokeLater {
                CopyPasteManager.getInstance().setContents(StringSelection(text))
                NotificationGroupManager.getInstance().getNotificationGroup("Blackbox")
                    .createNotification(
                        "Blackbox MCP server configuration copied",
                        "Add it under \"mcpServers\" in your MCP client's config (for Claude Code: ~/.claude.json), then reconnect it.",
                        NotificationType.INFORMATION,
                    )
                    .notify(project)
            }
        }
    }
}
