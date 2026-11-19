package io.github.whywood.superpi.plugin.sysu

internal object SysuFixtures {
    fun text(name: String): String =
        SysuFixtures::class.java.getResource("/fixtures/$name")?.readText()
            ?: error("missing fixture: $name")

    fun bashOutput(name: String, exitCode: Int = 0): String =
        text(name).trimEnd() + "\n\n[exit: $exitCode]"
}
