package dev.easyide.app.ui.screens.home

import androidx.annotation.StringRes
import dev.easyide.app.R

/**
 * The language a project is mostly written in, guessed cheaply for the dot on a
 * card. Declared in precedence order: the first language whose marker file sits
 * at the project root wins, so a TypeScript project (which also has a
 * `package.json`) is not reported as JavaScript.
 *
 * [tint] indexes the theme's categorical lane colours, so the dot recolours with
 * the theme and the palette needs no per-language hex values.
 */
enum class ProjectLanguage(
    @StringRes val label: Int,
    val tint: Int,
    val markers: Set<String>,
    val extensions: Set<String>,
) {
    KOTLIN(R.string.language_kotlin, 0, setOf("build.gradle.kts", "settings.gradle.kts"), setOf("kt", "kts")),
    JAVA(R.string.language_java, 1, setOf("pom.xml", "build.gradle", "settings.gradle"), setOf("java")),
    TYPESCRIPT(R.string.language_typescript, 2, setOf("tsconfig.json"), setOf("ts", "tsx")),
    JAVASCRIPT(R.string.language_javascript, 3, setOf("package.json"), setOf("js", "jsx", "mjs", "cjs")),
    PYTHON(R.string.language_python, 4, setOf("pyproject.toml", "requirements.txt", "setup.py", "Pipfile"), setOf("py")),
    RUST(R.string.language_rust, 5, setOf("Cargo.toml"), setOf("rs")),
    GO(R.string.language_go, 6, setOf("go.mod"), setOf("go")),
    C_CPP(R.string.language_c_cpp, 7, setOf("CMakeLists.txt"), setOf("c", "h", "cc", "cpp", "hpp")),
    RUBY(R.string.language_ruby, 0, setOf("Gemfile"), setOf("rb")),
    PHP(R.string.language_php, 1, setOf("composer.json"), setOf("php")),
    ;

    companion object {
        /**
         * @param rootNames file names at the project root.
         * @param sourceNames file names one level down in `src`, where a project without a
         *   build file (a folder of scripts) still shows what it is written in.
         * @return null when nothing identifies a language, in which case no dot is shown.
         */
        fun detect(rootNames: Collection<String>, sourceNames: Collection<String> = emptyList()): ProjectLanguage? {
            val byMarker = entries.firstOrNull { language -> language.markers.any { it in rootNames } }
            if (byMarker != null) return byMarker

            val counts = (rootNames + sourceNames)
                .mapNotNull { name -> name.substringAfterLast('.', "").lowercase().takeIf { it.isNotEmpty() } }
                .mapNotNull { ext -> entries.firstOrNull { ext in it.extensions } }
                .groupingBy { it }
                .eachCount()
            // Ties resolve to declaration order: maxByOrNull keeps the first maximum.
            return entries.filter { it in counts }.maxByOrNull { counts.getValue(it) }
        }
    }
}
