package desu.inugram.core.plugins

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.test.assertFailsWith

class PluginManifestParserTest {
    private val full = """
        // ==InuPlugin==
        // @name         My awesome plugin
        // @author       teidesu
        // @namespace    http://example.com
        // @version      1.0
        // @description         This script rocks.
        // @description:zh-CN   这个脚本很棒！
        // @icon tg://addstickers?set=my_set&idx=0
        // @grant        none
        // @plugin-api   1
        // @platform   android
        // ==/InuPlugin==
        /// <reference path="./index.d.ts" />
        console.log('hi')
    """.trimIndent()

    @Test
    fun parsesAllDescriptiveFields() {
        val m = PluginManifestParser.parse(full)
        assertEquals("My awesome plugin", m.name)
        assertEquals("teidesu", m.author)
        assertEquals("1.0", m.version)
        assertEquals("This script rocks.", m.description)
        assertEquals("tg://addstickers?set=my_set&idx=0", m.icon)
        assertEquals(1, m.pluginApi)
        assertEquals("android", m.platform)
    }

    @Test
    fun namespaceIsJustAnotherHeaderLine() {
        val m = PluginManifestParser.parse(full)
        assertEquals(listOf("http://example.com"), m.raw["namespace"])
    }

    @Test
    fun localizedDescriptionFallback() {
        val m = PluginManifestParser.parse(full)
        assertEquals("这个脚本很棒！", m.description("zh-CN"))
        assertEquals("这个脚本很棒！", m.description("zh")) // primary subtag
        assertEquals("This script rocks.", m.description("ru")) // base fallback
        assertEquals("This script rocks.", m.description(null))
    }

    @Test
    fun grantNoneIsEmpty() {
        assertTrue(PluginManifestParser.parse(full).grants.isEmpty())
    }

    @Test
    fun grantsParsedAndDeduped() {
        val m = PluginManifestParser.parse(
            """
            // ==InuPlugin==
            // @name g
            // @grant kv, fetch
            // @grant kv
            // @grant clipboard.read
            // ==/InuPlugin==
            """.trimIndent(),
        )
        assertEquals(listOf("kv", "fetch", "clipboard.read"), m.grants)
    }

    @Test
    fun scopedGrantsKeepParenthesizedCommas() {
        val m = PluginManifestParser.parse(
            """
            // ==InuPlugin==
            // @name g
            // @grant interceptRpc(users.getUsers,channels.getChannels), kv
            // @grant fetch(google.com,bing.com)
            // ==/InuPlugin==
            """.trimIndent(),
        )
        assertEquals(
            listOf("interceptRpc(users.getUsers,channels.getChannels)", "kv", "fetch(google.com,bing.com)"),
            m.grants,
        )
    }

    @Test
    fun missingNameThrows() {
        assertFailsWith<PluginManifestException> {
            PluginManifestParser.parse(
                """
                // ==InuPlugin==
                // @author nobody
                // ==/InuPlugin==
                """.trimIndent(),
            )
        }
    }

    @Test
    fun missingBlockThrows() {
        assertFailsWith<PluginManifestException> {
            PluginManifestParser.parse("console.log('no header')")
        }
    }

    @Test
    fun unterminatedBlockThrows() {
        assertFailsWith<PluginManifestException> {
            PluginManifestParser.parse(
                """
                // ==InuPlugin==
                // @name x
                console.log('oops')
                """.trimIndent(),
            )
        }
    }

    @Test
    fun parseOrNullSwallowsErrors() {
        assertNull(PluginManifestParser.parseOrNull("nope"))
    }

    @Test
    fun directiveKeysAreCaseInsensitive() {
        val m = PluginManifestParser.parse(
            """
            // ==InuPlugin==
            // @Name Cased
            // @PLUGIN-API 2
            // ==/InuPlugin==
            """.trimIndent(),
        )
        assertEquals("Cased", m.name)
        assertEquals(2, m.pluginApi)
    }

    @Test
    fun toleratesWhitespaceAndBlankCommentLines() {
        val m = PluginManifestParser.parse(
            """
              // ==InuPlugin==
              //
              //   @name   spaced   out
              // ==/InuPlugin==
            """.trimIndent(),
        )
        assertEquals("spaced   out", m.name)
    }
}
