package dev.rboza.android.opensyncforproton.build

/** What the module-rule check knows about one module. */
data class ModuleFacts(
    val path: String,
    val usesAndroidPlugin: Boolean,
    /** Project paths reachable through any of the module's classpaths. */
    val reachableProjects: Set<String>,
    /** `group:name` of every external module reachable through them. */
    val reachableModules: Set<String>,
    /** Dependencies that didn't resolve (for example, missing from the lock state); their graphs are unknown. */
    val unresolvedDependencies: Set<String>,
    /** Files added as dependencies (`files(...)`), relative to the module; nothing checks what they hold. */
    val fileDependencies: Set<String>,
    /** Source text by path relative to `src/`. */
    val sources: Map<String, String>,
)

// docs/architecture.md § Modules: the modules each module may depend on, directly or transitively.
// `:app` is the composition root and may depend on any of them.
private val allowedProjects = mapOf(
    ":app" to setOf(":sync", ":account", ":proton", ":crypto", ":mapping"),
    ":sync" to setOf(":account", ":proton", ":crypto", ":mapping"),
    ":account" to setOf(":proton", ":crypto"),
    ":proton" to emptySet(),
    ":crypto" to emptySet(),
    ":mapping" to emptySet(),
)
private val jvmOnly = setOf(":proton", ":mapping")

// Google Play, Play Services, Firebase, and ML Kit (D2).
private val forbiddenGroups = listOf("com.google.android.gms", "com.google.android.play", "com.google.firebase", "com.google.mlkit")
// `android.` as a name (imported, wildcard, or fully qualified, `android.R` included), outside comments and
// strings; `androidx.`, `myandroid.`, and "vnd.android." don't count. The Android API jars below are the backstop.
private val androidReference = Regex("""(?<![\w.])android\.[A-Za-z*]""")
// Strings first, so a "/*" inside a string doesn't start a comment; both are blanked before scanning.
private val stringsAndComments = Regex(""""{3}[\s\S]*?"{3}|"(?:\\.|[^"\\\n])*"|'(?:\\.|[^'\\\n])*'|//[^\n]*|/\*[\s\S]*?\*/""")
private val androidApiJars = setOf("com.google.android:android", "org.robolectric:android-all", "org.robolectric:android-all-instrumented")

/** Source text with strings and comments blanked. */
private fun code(text: String): String = stringsAndComments.replace(text, " ")
private val renameAccount = Regex("""\brenameAccount\b""")

fun moduleRuleViolations(facts: ModuleFacts): List<String> {
    val path = facts.path
    val allowed = allowedProjects[path] ?: return listOf("$path is not a module in docs/architecture.md § Modules")
    val found = mutableListOf<String>()
    (facts.reachableProjects - path - allowed).forEach { found += "$path depends on $it" }
    facts.reachableModules.filter(::isForbiddenModule).forEach { found += "$path depends on $it" }
    if (path in jvmOnly) facts.reachableModules.filter { it in androidApiJars }.forEach { found += "$path depends on $it" }
    facts.unresolvedDependencies.forEach { found += "$path has an unresolved dependency: $it" }
    facts.fileDependencies.forEach { found += "$path has a file dependency: $it" }
    if (path in jvmOnly && facts.usesAndroidPlugin) found += "$path is an Android module; it must be plain JVM"
    facts.sources.forEach { (file, text) ->
        if (path in jvmOnly && androidReference.containsMatchIn(code(text))) found += "$path uses android.* in $file"
        if (renameAccount.containsMatchIn(text)) found += "$path calls AccountManager.renameAccount in $file"
    }
    return found.sorted()
}

/** The Google groups above (and their subgroups), and Proton Core's auth, account, and network modules (D7). */
private fun isForbiddenModule(module: String): Boolean {
    val (group, name) = module.split(':', limit = 2)
    return forbiddenGroups.any { group == it || group.startsWith("$it.") } ||
        (group == "me.proton.core" && listOf("auth", "account", "network").any { name == it || name.startsWith("$it-") })
}

private val scriptPlugin = listOf(
    Regex("""\bapply\s*\([^)]*\bfrom\s*="""),
    Regex("""\bapply\s+from\s*:"""),
    Regex("""\bapply\s*\(\s*mapOf\s*\([^)]*"from""""),
    // A statement-level `apply { from(...) }` or `project.apply { ... }`, not `something.apply { from(...) }`.
    Regex("""(?m)^\s*(project\.)?apply\s*\{[^}]*\bfrom\s*\("""),
)

/**
 * Whether a build or settings script applies a script plugin, whose own plugin classpath Gradle can't lock
 * (D24). Best effort, on the script without comments; review is the other guard.
 */
fun usesScriptPlugin(script: String): Boolean {
    val withoutComments = Regex("""//[^\n]*|/\*[\s\S]*?\*/""").replace(script, " ")
    return scriptPlugin.any { it.containsMatchIn(withoutComments) }
}

/** `compileClasspath`, `releaseRuntimeClasspath`, `debugUnitTestRuntimeClasspath`, and so on, and the desugaring library. */
fun isCheckedClasspath(configurationName: String): Boolean =
    configurationName == "coreLibraryDesugaring" ||
        Regex("(^c|C)ompileClasspath$|(^r|R)untimeClasspath$").containsMatchIn(configurationName)
