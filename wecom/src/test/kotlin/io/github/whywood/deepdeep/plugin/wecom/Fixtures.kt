package io.github.whywood.deepdeep.plugin.wecom

internal fun wecomFixture(name: String): String =
    Thread.currentThread().contextClassLoader?.getResourceAsStream("fixtures/cli/$name")
        ?.bufferedReader()
        ?.use { it.readText() }
        ?: error("missing fixture $name")
