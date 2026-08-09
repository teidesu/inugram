package desu.inugram.sources

import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Reading the fork's own sources as text, for the wiring that goes *silent* rather than red when it
 * breaks: a hook in stock java (nothing but the text says it is still there) and the boot sequence
 * in a file no test target compiles.
 *
 * Nothing here is a plugin test. These run wherever a checkout exists, and only there - the device
 * suite has no repo to read.
 */
internal fun forkRoot(): File {
    var dir: File? = File({}.javaClass.protectionDomain.codeSource.location.toURI()).canonicalFile
    while (dir != null && !File(dir, "src/fork/helpers/plugins").isDirectory) dir = dir.parentFile
    return dir ?: error("could not locate the repo root")
}

internal fun stockRoot(): File = File(forkRoot(), "worktree/TMessagesProj/src/main/java")

internal fun stock(path: String): String = File(stockRoot(), path).readText()

/** the bridge is a package tree, so one of its files is found by name rather than by path */
internal fun forkSource(name: String): File =
    File(forkRoot(), "src/fork/helpers/plugins").walkTopDown().firstOrNull { it.name == name }
        ?: error("no bridge source named $name")

private fun skipQuoted(source: String, start: Int, quote: Char): Int {
    var i = start + 1
    while (i < source.length) {
        when (source[i]) {
            '\\' -> i += 2
            quote -> return i + 1
            else -> i++
        }
    }
    return i
}

internal fun blockAt(source: String, open: Int): IntRange {
    var i = open + 1
    var depth = 1
    while (i < source.length) {
        when (source[i]) {
            '"' -> i = skipQuoted(source, i, '"')
            '\'' -> i = skipQuoted(source, i, '\'')
            '/' -> when (source.getOrNull(i + 1)) {
                '/' -> i = source.indexOf('\n', i).let { if (it < 0) source.length else it }
                '*' -> i = source.indexOf("*/", i).let { if (it < 0) source.length else it + 2 }
                else -> i++
            }
            '{' -> { depth++; i++ }
            '}' -> { depth--; if (depth == 0) return open..i; i++ }
            else -> i++
        }
    }
    error("unbalanced braces from offset $open")
}

internal fun bodyOf(source: String, declaration: String): String {
    val at = source.indexOf(declaration)
    assertTrue(at >= 0, "stock no longer declares `$declaration`")
    assertEquals(at, source.lastIndexOf(declaration), "`$declaration` is declared more than once")
    val open = source.indexOf('{', source.indexOf(')', at + declaration.length))
    return source.substring(blockAt(source, open))
}

internal fun assertOpensWith(body: String, statement: String, why: String) =
    assertTrue(body.removePrefix("{").trimStart().startsWith(statement), why)
