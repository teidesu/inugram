package desu.inugram.core.plugins

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
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
    fun parses_all_descriptive_fields() {
        val m = PluginManifestParser.parse(full)
        assertEquals("My awesome plugin", m.name)
        assertEquals("teidesu", m.author)
        assertEquals("1.0", m.version)
        assertEquals("This script rocks.", m.description)
        assertEquals("tg://addstickers?set=my_set&idx=0", m.icon)
        assertEquals(1, m.pluginApi)
        assertEquals("android", m.platform)
        assertEquals(listOf("http://example.com"), m.raw["namespace"])
        assertTrue("@grant none is no grants", m.grants.isEmpty())
    }

    private fun parseDeclaredId(declared: String): String? = PluginManifestParser.parse(
        buildString {
            appendLine("// ==InuPlugin==")
            appendLine("// @name My Plugin")
            appendLine("// @author teidesu")
            appendLine("// @id $declared")
            appendLine("// ==/InuPlugin==")
        },
    ).id

    private fun parseManifest(name: String, author: String?): PluginManifest = PluginManifestParser.parse(
        buildString {
            appendLine("// ==InuPlugin==")
            appendLine("// @name $name")
            if (author != null) appendLine("// @author $author")
            appendLine("// ==/InuPlugin==")
        },
    )

    @Test
    fun a_derived_id_slugs_the_author_and_name_and_needs_both() {
        assertEquals("teidesu.my-plugin", parseManifest("My Plugin", "teidesu").id)
        assertEquals(parseManifest("My Plugin", "teidesu").id, parseManifest("my   plugin", "  TEIDESU ").id)
        assertNotEquals(parseManifest("a", "b").id, parseManifest("b", "a").id)
        assertNotEquals(parseManifest("plugin", "one").id, parseManifest("plugin", "two").id)
        assertNull("no author is no derived id", parseManifest("nameless", null).id)
    }

    /** a name nothing can be seen in is a name of its own, so it installs beside what it apes */
    @Test
    fun derived_id_does_not_let_an_invisible_character_ape_another_plugin() {
        assertNotEquals(
            parseManifest("plugin", "teidesu").id,
            parseManifest("plu\u0000gin", "teide\u0007su").id,
        )
    }

    @Test
    fun a_declared_id_wins_over_the_derived_one() {
        assertEquals("com.github.teidesu.my-plugin", parseDeclaredId("com.github.teidesu.my-plugin"))
    }

    @Test
    fun a_declared_id_is_compared_verbatim() {
        assertNotEquals(parseDeclaredId("Hello.World"), parseDeclaredId("hello.world"))
    }

    @Test
    fun an_id_nothing_can_be_seen_in_falls_back_to_the_derived_one() {
        assertEquals("teidesu.my-plugin", parseDeclaredId("two tokens"))
        assertEquals("teidesu.my-plugin", parseDeclaredId("bell\u0007id"))
        assertEquals("teidesu.my-plugin", parseDeclaredId(""))
    }

    @Test
    fun localized_description_fallback() {
        val m = PluginManifestParser.parse(full)
        assertEquals("这个脚本很棒！", m.description("zh-CN"))
        assertEquals("这个脚本很棒！", m.description("zh")) // primary subtag
        assertEquals("This script rocks.", m.description("ru")) // base fallback
        assertEquals("This script rocks.", m.description(null))
    }

    @Test
    fun grants_parsed_and_deduped() {
        val m = PluginManifestParser.parse(
            """
            // ==InuPlugin==
            // @name g
            // @grant openUrl, fetch
            // @grant openUrl
            // @grant clipboard.read
            // ==/InuPlugin==
            """.trimIndent(),
        )
        assertEquals(listOf("openUrl", "fetch", "clipboard.read"), m.grants)
    }

    @Test
    fun scoped_grants_keep_parenthesized_commas() {
        val m = PluginManifestParser.parse(
            """
            // ==InuPlugin==
            // @name g
            // @grant interceptRpc(users.getUsers,channels.getChannels), openUrl
            // @grant fetch(google.com,bing.com)
            // ==/InuPlugin==
            """.trimIndent(),
        )
        assertEquals(
            listOf("interceptRpc(users.getUsers,channels.getChannels)", "openUrl", "fetch(google.com,bing.com)"),
            m.grants,
        )
    }

    @Test
    fun a_header_that_does_not_parse_throws() {
        val bad = listOf(
            "// ==InuPlugin==\n// @author nobody\n// ==/InuPlugin==",
            "console.log('no header')",
            "// ==InuPlugin==\n// @name x\nconsole.log('unterminated')",
        )
        for (source in bad) assertFailsWith<PluginManifestException>(source) { PluginManifestParser.parse(source) }
    }

    @Test
    fun parse_or_null_swallows_errors() {
        assertNull(PluginManifestParser.parseOrNull("nope"))
    }

    @Test
    fun directive_keys_are_case_insensitive() {
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
    fun tolerates_whitespace_and_blank_comment_lines() {
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
