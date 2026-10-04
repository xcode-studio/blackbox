// Editor and workspace tools, plus window_status (mirrors
// editors/vscode/src/tools/impl/editor.ts, workspace.ts and window.ts).

package dev.blackbox.tools

import com.google.gson.JsonObject
import com.intellij.codeInsight.daemon.impl.DaemonCodeAnalyzerEx
import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.openapi.application.EDT
import com.intellij.openapi.application.readAction
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ProjectFileIndex
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.VirtualFileVisitor
import dev.blackbox.ipc.ProjectBridge
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.nio.file.FileSystems
import java.nio.file.Path

class Editor(private val project: Project, private val debug: Debug, private val breakpoints: Breakpoints) {
    suspend fun openFile(args: JsonObject): String {
        val file = args.str("file")?.takeIf { it.isNotEmpty() } ?: fail("file path is required")
        val line = args.int("line")
        val vfile = findFile(file) ?: fail("Error opening file: no such file $file")
        withContext(Dispatchers.EDT) {
            OpenFileDescriptor(project, vfile, ((line ?: 1) - 1).coerceAtLeast(0), 0).navigate(true)
        }
        return "Opened $file" + (line?.let { ":$it" } ?: "")
    }

    suspend fun openFiles(): String = withContext(Dispatchers.EDT) {
        val editors = FileEditorManager.getInstance(project)
        val selected = editors.selectedFiles.toSet()
        val documents = FileDocumentManager.getInstance()
        json(editors.openFiles.filter { it.isInLocalFileSystem }.map {
            obj("file" to it.path, "active" to (it in selected), "dirty" to documents.isFileModified(it))
        })
    }

    suspend fun findFile(args: JsonObject): String {
        val glob = args.str("pattern")?.takeIf { it.isNotEmpty() } ?: fail("glob pattern is required")
        val limit = args.int("maxResults") ?: 20
        val matcher = FileSystems.getDefault().getPathMatcher("glob:$glob")
        val found = mutableListOf<String>()
        readAction {
            val index = ProjectFileIndex.getInstance(project)
            for (root in ProjectBridge.roots(project)) {
                if (found.size >= limit) break
                VfsUtilCore.visitChildrenRecursively(root, object : VirtualFileVisitor<Unit>() {
                    override fun visitFile(file: VirtualFile): Boolean {
                        if (found.size >= limit) return false
                        if (file.isDirectory) return file.name != "node_modules" && file.name != ".git" && !index.isExcluded(file)
                        val relative = VfsUtilCore.getRelativePath(file, root) ?: return true
                        // "**/x" also matches "x" at the root, as in VS Code.
                        if (matcher.matches(Path.of(relative)) || matcher.matches(Path.of("_", relative))) {
                            if (file.path !in found) found += file.path
                        }
                        return true
                    }
                })
            }
        }
        return json(found)
    }

    suspend fun diagnostics(args: JsonObject): String {
        val min = when (args.str("severity")) {
            "error" -> HighlightSeverity.ERROR
            "warning" -> HighlightSeverity.WARNING
            else -> HighlightSeverity.WEAK_WARNING
        }
        val files = args.str("file")?.let { path -> listOfNotNull(findFile(path)) }
            ?: withContext(Dispatchers.EDT) { FileEditorManager.getInstance(project).openFiles.toList() }
        val result = readAction {
            files.mapNotNull { file ->
                val document = FileDocumentManager.getInstance().getDocument(file) ?: return@mapNotNull null
                val issues = mutableListOf<JsonObject>()
                DaemonCodeAnalyzerEx.processHighlights(document, project, min, 0, document.textLength) { info ->
                    issues += obj(
                        "line" to document.getLineNumber(info.startOffset) + 1,
                        "severity" to when {
                            info.severity >= HighlightSeverity.ERROR -> "error"
                            info.severity >= HighlightSeverity.WARNING -> "warning"
                            else -> "info"
                        },
                        "message" to info.description,
                        "source" to info.inspectionToolId,
                    )
                    true
                }
                issues.filter { it.has("message") }.takeIf { it.isNotEmpty() }?.let { obj("file" to file.path, "issues" to it) }
            }
        }
        return if (result.isEmpty()) "No diagnostics found" else json(result)
    }

    suspend fun windowStatus(): String {
        val session = debug.findSession()
        val stop = debug.events.currentStop()
        val debugView = session?.let {
            val position = if (stop != null) it.currentPosition else null
            obj(
                "name" to it.sessionName,
                "type" to (it.runProfile as? com.intellij.execution.configurations.RunConfiguration)?.type?.id.orEmpty(),
                "state" to if (stop != null) "stopped" else "running",
                "stoppedAt" to position?.let { p -> obj("file" to p.file.path, "line" to p.line + 1) },
            )
        }
        val bridge = ProjectBridge.of(project)
        return json(obj(
            "folders" to bridge.folders(),
            "focused" to bridge.isFocused(),
            "breakpoints" to readAction { breakpoints.count() },
            "debug" to (debugView ?: com.google.gson.JsonNull.INSTANCE),
        ))
    }
}
