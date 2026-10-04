// AI tools started in the IDE's terminal (claude, ...) inherit
// BLACKBOX_WINDOW and so use this project's window (see BridgeSession in
// editors/vscode/src/mcp/session.ts). Registered only when the terminal
// plugin is present (terminal.xml).

package dev.blackbox.ipc

import com.intellij.openapi.project.Project
import org.jetbrains.plugins.terminal.LocalTerminalCustomizer

class TerminalWindowEnv : LocalTerminalCustomizer() {
    override fun customizeCommandAndEnvironment(
        project: Project,
        workingDirectory: String?,
        command: Array<String>,
        envs: MutableMap<String, String>,
    ): Array<String> {
        envs["BLACKBOX_WINDOW"] = ProjectBridge.of(project).id
        return command
    }
}
