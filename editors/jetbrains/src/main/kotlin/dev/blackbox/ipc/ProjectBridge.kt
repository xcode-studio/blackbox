// One Blackbox "window" per open project: its bridge server, folders and
// focus. Several projects share one IDE process, so the window id is the
// IDE's pid plus a hash of the project's location.

package dev.blackbox.ipc

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationInfo
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ApplicationNamesInfo
import com.intellij.openapi.application.EDT
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.guessProjectDir
import com.intellij.openapi.roots.ModuleRootEvent
import com.intellij.openapi.roots.ModuleRootListener
import com.intellij.openapi.roots.ProjectRootManager
import com.intellij.openapi.startup.ProjectActivity
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.wm.WindowManager
import dev.blackbox.debug.DebugEvents
import dev.blackbox.launcher.McpInstall
import dev.blackbox.tools.Catalog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.awt.event.WindowAdapter
import java.awt.event.WindowEvent
import java.nio.file.Path
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicBoolean

@Service(Service.Level.PROJECT)
class ProjectBridge(private val project: Project, private val scope: CoroutineScope) : Disposable {
    val id: String = "${ProcessHandle.current().pid()}-${shortHash(project.basePath ?: project.locationHash)}"

    @Volatile
    private var server: BridgeServer? = null

    @Volatile
    private var focused = true

    fun folders(): List<String> = roots(project).map { real(it.path) }.distinct()

    fun isFocused() = focused

    suspend fun start() {
        if (server != null) return
        DebugEvents.of(project) // Follows sessions from now on.
        val bridge = BridgeServer(
            scope = scope,
            id = id,
            folders = folders(),
            app = appInfo(),
            extensionVersion = PLUGIN_VERSION,
            handlers = Catalog.handlers(project),
        )
        try {
            bridge.start()
        } catch (e: Exception) {
            LOG.warn("blackbox: could not start the MCP bridge for ${project.name}", e)
            return
        }
        server = bridge

        project.messageBus.connect(this).subscribe(ModuleRootListener.TOPIC, object : ModuleRootListener {
            override fun rootsChanged(event: ModuleRootEvent) {
                scope.launch { bridge.setFolders(folders()) }
            }
        })
        withContext(Dispatchers.EDT) {
            val frame = WindowManager.getInstance().getFrame(project) ?: return@withContext
            focused = frame.isActive
            frame.addWindowListener(object : WindowAdapter() {
                override fun windowActivated(e: WindowEvent) {
                    focused = true
                    scope.launch { bridge.markFocused() }
                }

                override fun windowDeactivated(e: WindowEvent) {
                    focused = false
                }
            })
        }
    }

    override fun dispose() {
        server?.dispose()
        server = null
    }

    companion object {
        private val LOG = logger<ProjectBridge>()

        val PLUGIN_VERSION: String = ProjectBridge::class.java.getResourceAsStream("/blackbox-plugin-version.txt")
            ?.use { it.readBytes().toString(Charsets.UTF_8).trim() } ?: "dev"

        fun of(project: Project): ProjectBridge = project.service()

        // Where the project's files are: its content roots, else its folder.
        fun roots(project: Project): List<VirtualFile> =
            ProjectRootManager.getInstance(project).contentRoots.filter { it.isInLocalFileSystem }.ifEmpty {
                listOfNotNull(project.guessProjectDir())
            }

        private fun real(path: String) = runCatching { Path.of(path).toRealPath().toString() }.getOrDefault(path)

        private fun appInfo() = AppInfo(
            name = ApplicationNamesInfo.getInstance().fullProductName,
            scheme = "jetbrains",
            version = ApplicationInfo.getInstance().fullVersion,
        )

        private fun shortHash(text: String): String =
            MessageDigest.getInstance("SHA-1").digest(text.toByteArray()).take(4).joinToString("") { "%02x".format(it) }
    }
}

class BridgeStartup : ProjectActivity {
    override suspend fun execute(project: Project) {
        if (launcherChecked.compareAndSet(false, true) && !ApplicationManager.getApplication().isUnitTestMode) {
            withContext(Dispatchers.IO) { McpInstall.install(ProjectBridge.PLUGIN_VERSION) }
        }
        ProjectBridge.of(project).start()
    }

    private companion object {
        // Once per IDE run, by the first project opened.
        val launcherChecked = AtomicBoolean(false)
    }
}
