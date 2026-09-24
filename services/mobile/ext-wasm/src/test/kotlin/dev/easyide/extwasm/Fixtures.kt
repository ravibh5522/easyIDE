package dev.easyide.extwasm

import dev.easyide.extwasm.testing.WasmFixtures

/** JVM tests read the compiled fixtures from the test classpath (Gradle `compileWatFixtures`). */
object Fixtures : WasmFixtures({ name -> Fixtures::class.java.getResourceAsStream("/$name")!!.use { it.readBytes() } })
