// Run configurations and debug session control.

package dev.blackbox.tools

import com.google.gson.JsonObject
import com.intellij.execution.ExecutionException
import com.intellij.execution.ExecutionListener
import com.intellij.execution.ExecutionManager
import com.intellij.execution.RunManager
import com.intellij.execution.RunnerAndConfigurationSettings
import com.intellij.execution.configurations.RuntimeConfigurationError
import com.intellij.execution.configurations.RuntimeConfigurationException
import com.intellij.execution.executors.DefaultDebugExecutor
import com.intellij.execution.runners.ExecutionEnvironment
import com.intellij.execution.runners.ExecutionEnvironmentBuilder
import com.intellij.execution.runners.ExecutionUtil
import com.intellij.openapi.application.EDT
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.xdebugger.XDebugProcess
import com.intellij.xdebugger.XDebugSession
import com.intellij.xdebugger.XDebuggerManager
import com.intellij.xdebugger.XDebuggerManagerListener
import dev.blackbox.lang.DebugListener
import dev.blackbox.lang.LanguageSupport
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

class Sessions(private val project: Project, private val debug: Debug) {
    private val runManager get() = RunManager.getInstance(project)

    private fun runConfigs() = runManager.allSettings.filter { !it.isTemplate }

    // Run configurations, then ways to wait for a program to connect (PHP).
    fun launchConfigs(): String {
        val configs = runConfigs().map { obj("name" to it.name, "type" to it.type.displayName, "request" to "launch") } +
            LanguageSupport.listeners().map { obj("name" to it.name, "type" to "listener", "request" to "attach") }
        if (configs.isEmpty()) return "No run configurations found (Run | Edit Configurations…)"
        return json(listOf(obj("folder" to (project.basePath ?: project.name), "configurations" to configs)))
    }

    private fun find(name: String): RunnerAndConfigurationSettings {
        runConfigs().firstOrNull { it.name == name }?.let { return it }
        val names = (runConfigs().map { it.name } + LanguageSupport.listeners().map { it.name }).joinToString(", ").ifEmpty { "(none)" }
        fail("no launch configuration named \"$name\". Available: $names")
    }

    private suspend fun listen(listener: DebugListener): String {
        withContext(Dispatchers.EDT) { listener.setListening(project, true) }
        if (!listener.isListening(project)) fail("the IDE did not start listening; check its Debug settings (e.g. the Xdebug port)")
        listenersStarted += listener
        return "${listener.name}: on. ${listener.hint}"
    }

    // Listeners Blackbox turned on, turned off again by debug_stop.
    private val listenersStarted = mutableSetOf<DebugListener>()

    private sealed interface Outcome {
        data object Started : Outcome
        data object Slow : Outcome
        data object Stalled : Outcome
        data class Failed(val message: String) : Outcome
    }

    suspend fun start(args: JsonObject): String {
        val name = args.str("configName")?.takeIf { it.isNotEmpty() }
            ?: fail("JetBrains IDEs start run configurations by name: pass \"configName\" (see debug_get_launch_configs)")
        LanguageSupport.listeners().firstOrNull { it.name == name && runConfigs().none { c -> c.name == name } }
            ?.let { return listen(it) }
        val settings = find(name)
        val executor = DefaultDebugExecutor.getDebugExecutorInstance()
        try {
            settings.checkSettings(executor)
        } catch (e: RuntimeConfigurationError) {
            fail("the IDE refused to launch \"$name\": ${e.localizedMessage}")
        } catch (_: RuntimeConfigurationException) {
            // A warning; the IDE runs it anyway.
        }

        val outcome = CompletableDeferred<Outcome>()
        var scheduled = false
        val disposable = Disposer.newDisposable("blackbox start")
        try {
            val connection = project.messageBus.connect(disposable)
            fun ours(env: ExecutionEnvironment) = env.runnerAndConfigurationSettings == settings
            connection.subscribe(ExecutionManager.EXECUTION_TOPIC, object : ExecutionListener {
                override fun processStartScheduled(executorId: String, env: ExecutionEnvironment) {
                    if (ours(env)) scheduled = true
                }

                override fun processNotStarted(executorId: String, env: ExecutionEnvironment, cause: Throwable?) {
                    if (ours(env)) outcome.complete(Outcome.Failed(cause?.message ?: "see the IDE's Run/Debug tool window"))
                }

                override fun processTerminated(executorId: String, env: ExecutionEnvironment, handler: com.intellij.execution.process.ProcessHandler, exitCode: Int) {
                    if (ours(env)) outcome.complete(Outcome.Failed("the program exited with code $exitCode before the debugger attached"))
                }
            })
            connection.subscribe(XDebuggerManager.TOPIC, object : XDebuggerManagerListener {
                override fun processStarted(process: XDebugProcess) {
                    if (process.session.runProfile == settings.configuration) outcome.complete(Outcome.Started)
                }
            })

            withContext(Dispatchers.EDT) {
                val builder = ExecutionEnvironmentBuilder.createOrNull(executor, settings)
                    ?: fail("\"$name\" (${settings.type.displayName}) can't be debugged in this IDE")
                try {
                    builder.buildAndExecute()
                } catch (e: ExecutionException) {
                    outcome.complete(Outcome.Failed(e.message ?: e.toString()))
                }
            }

            // Before-launch tasks (a build) can take a while; a start that was
            // never even scheduled is stuck, e.g. on a dialog.
            val result = withTimeoutOrNull(START_PROMPT_MS) { outcome.await() }
                ?: if (!scheduled) Outcome.Stalled
                else withTimeoutOrNull(LAUNCH_MS - START_PROMPT_MS) { outcome.await() } ?: Outcome.Slow

            return when (result) {
                Outcome.Started -> "Debug session \"$name\" started from its launch configuration"
                Outcome.Slow -> "Debug session \"$name\" started from its launch configuration " +
                    "(still building or launching; call debug_wait_for_stop)"
                Outcome.Stalled -> fail(
                    "the debugger has not started after ${START_PROMPT_MS / 1000}s. Check the IDE for a dialog or prompt; " +
                        "if it starts later, call debug_wait_for_stop rather than starting again.",
                )
                is Outcome.Failed -> fail("the IDE refused to launch \"$name\": ${result.message}")
            }
        } finally {
            Disposer.dispose(disposable)
        }
    }

    suspend fun stop(): String {
        val stopped = stopListeners()
        val session = debug.findSession()
            ?: return stopped.ifEmpty { null }?.let { "Stopped: ${it.joinToString(", ")}" } ?: "No active debug session"
        withContext(Dispatchers.EDT) { session.stop() }
        waitStopped(session, 5_000)
        return "Debug session \"${session.sessionName}\" stopped" + stopped.joinToString("") { "; stopped $it" }
    }

    private suspend fun stopListeners(): List<String> {
        val on = listenersStarted.filter { it.isListening(project) }
        listenersStarted.clear()
        if (on.isNotEmpty()) withContext(Dispatchers.EDT) { on.forEach { it.setListening(project, false) } }
        return on.map { it.name }
    }

    private suspend fun waitStopped(session: XDebugSession, timeoutMs: Long) {
        withTimeoutOrNull(timeoutMs) {
            while (!session.isStopped) delay(50)
        }
    }

    suspend fun restart(): String {
        val session = debug.findSession() ?: fail("no active debug session")
        val descriptor = session.runContentDescriptor
        withContext(Dispatchers.EDT) { ExecutionUtil.restart(descriptor) }
        return "Debug session \"${session.sessionName}\" restarted"
    }

    companion object {
        private const val START_PROMPT_MS = 20_000L
        // Below the MCP server's 60s limit for debug_start, so the note arrives.
        private const val LAUNCH_MS = 45_000L
    }
}
