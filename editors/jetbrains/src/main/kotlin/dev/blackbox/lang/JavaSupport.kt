// Java (and other JVM languages debugged by the Java debugger): function
// breakpoints are method breakpoints, exception filters are the "Any
// exception" breakpoint's caught/uncaught switches.

package dev.blackbox.lang

import com.intellij.debugger.ui.breakpoints.JavaExceptionBreakpointType
import com.intellij.debugger.ui.breakpoints.JavaMethodBreakpointType
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.Project
import com.intellij.psi.JavaPsiFacade
import com.intellij.openapi.project.ProjectLocator
import com.intellij.psi.PsiClass
import com.intellij.psi.PsiManager
import com.intellij.psi.PsiMethod
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.search.PsiShortNamesCache
import com.intellij.xdebugger.XDebuggerManager
import com.intellij.xdebugger.XDebuggerUtil
import com.intellij.xdebugger.breakpoints.XBreakpoint
import com.intellij.xdebugger.breakpoints.XLineBreakpointType
import org.jetbrains.java.debugger.breakpoints.properties.JavaExceptionBreakpointProperties
import org.jetbrains.java.debugger.breakpoints.properties.JavaMethodBreakpointProperties

class JavaSupport : LanguageSupport {
    override fun addFunctionBreakpoint(project: Project, name: String): XBreakpoint<*>? {
        val className = name.substringBeforeLast('.', "")
        val methodName = name.substringAfterLast('.')
        if (className.isEmpty()) return null // Java methods need their class: "App.add".
        val method = findClasses(project, className)
            .firstNotNullOfOrNull { it.findMethodsByName(methodName, false).firstOrNull() } ?: return null
        val cls = method.containingClass ?: return null
        val file = method.containingFile?.virtualFile ?: return null
        val document = FileDocumentManager.getInstance().getDocument(file) ?: return null
        val line = document.getLineNumber((method.nameIdentifier ?: method).textOffset)

        val type = XDebuggerUtil.getInstance().findBreakpointType(JavaMethodBreakpointType::class.java)
        @Suppress("UNCHECKED_CAST")
        return XDebuggerManager.getInstance(project).breakpointManager.addLineBreakpoint(
            type as XLineBreakpointType<JavaMethodBreakpointProperties>,
            file.url,
            line,
            JavaMethodBreakpointProperties(cls.qualifiedName ?: className, methodName),
        )
    }

    private fun findClasses(project: Project, name: String): List<PsiClass> {
        val scope = GlobalSearchScope.allScope(project)
        JavaPsiFacade.getInstance(project).findClass(name, scope)?.let { return listOf(it) }
        // A short name ("App") or a tail of the qualified one.
        return PsiShortNamesCache.getInstance(project).getClassesByName(name.substringAfterLast('.'), scope)
            .filter { cls -> cls.qualifiedName?.let { LanguageSupport.nameMatches(it, name) } == true }
    }

    override fun functionName(breakpoint: XBreakpoint<*>): String? {
        val properties = breakpoint.properties as? JavaMethodBreakpointProperties ?: return null
        if (breakpoint.type !is JavaMethodBreakpointType) return null
        val className = properties.myClassPattern
        val methodName = properties.myMethodName
        if (className != null && methodName != null) return "$className.$methodName"
        // The Java debugger refills these from the source in the background;
        // until then, read them from the method on the breakpoint's line.
        val method = methodAt(breakpoint) ?: return "$className.$methodName"
        return "${method.containingClass?.qualifiedName}.${method.name}"
    }

    private fun methodAt(breakpoint: XBreakpoint<*>): PsiMethod? {
        val position = breakpoint.sourcePosition ?: return null
        val project = ProjectLocator.getInstance().guessProjectForFile(position.file) ?: return null
        val document = FileDocumentManager.getInstance().getDocument(position.file) ?: return null
        val psi = PsiManager.getInstance(project).findFile(position.file) ?: return null
        if (position.line >= document.lineCount) return null
        val start = document.getLineStartOffset(position.line)
        val text = document.charsSequence
        var offset = start
        while (offset < document.getLineEndOffset(position.line) && text[offset].isWhitespace()) offset++
        return PsiTreeUtil.getParentOfType(psi.findElementAt(offset), PsiMethod::class.java, false)
    }

    private fun anyException(project: Project): XBreakpoint<JavaExceptionBreakpointProperties>? {
        val type = XDebuggerUtil.getInstance().findBreakpointType(JavaExceptionBreakpointType::class.java)
        return XDebuggerManager.getInstance(project).breakpointManager.getDefaultBreakpoints(type).firstOrNull()
    }

    override fun exceptionFilters(project: Project): List<ExceptionFilter> {
        val any = anyException(project) ?: return emptyList()
        val on = any.isEnabled
        return listOf(
            ExceptionFilter(CAUGHT, "Caught exceptions (Java)", on && any.properties.NOTIFY_CAUGHT),
            ExceptionFilter(UNCAUGHT, "Uncaught exceptions (Java)", on && any.properties.NOTIFY_UNCAUGHT),
        )
    }

    override fun setExceptionFilters(project: Project, filters: Collection<String>) {
        val any = anyException(project) ?: return
        any.properties.NOTIFY_CAUGHT = CAUGHT in filters
        any.properties.NOTIFY_UNCAUGHT = UNCAUGHT in filters
        any.isEnabled = CAUGHT in filters || UNCAUGHT in filters
    }

    companion object {
        const val CAUGHT = "java-caught"
        const val UNCAUGHT = "java-uncaught"
    }
}
