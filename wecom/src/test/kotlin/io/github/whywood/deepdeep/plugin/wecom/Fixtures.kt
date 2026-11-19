package io.github.whywood.superpi.plugin.wecom

internal fun wecomFixture(name: String): String =
    Thread.currentThread().contextClassLoader?.getResourceAsStream("fixtures/cli/$name")
        ?.bufferedReader()
        ?.use { it.readText() }
        ?: error("missing fixture $name")
