package desu.inugram.core.plugins

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ObfuscationDetectorTest {
    private val readablePlugin = """
        // ==InuPlugin==
        // @name Test
        // ==/InuPlugin==
        function greet(name) {
            console.log('hello ' + name)
        }
        inu.onUpdate('updateNewMessage', (update) => {
            greet(update.message)
        })
    """.trimIndent()

    @Test
    fun readableSourcePasses() {
        assertNull(ObfuscationDetector.detect(readablePlugin))
    }

    @Test
    fun readableSourceWithOneLongAssetLinePasses() {
        val withAsset = readablePlugin + "\nconst icon = '" + "A".repeat(20000) + "'\n" + readablePlugin.lines().joinToString("\n")
        assertNull(ObfuscationDetector.detect(withAsset))
    }

    @Test
    fun detectsObfuscatorIoHexIdentifiers() {
        val source = (0 until 12).joinToString("\n") { "var _0x${(0x1000 + it).toString(16)} = _0xab${it}f['shift']();" }
        assertEquals(SourceObfuscation.OBFUSCATED, ObfuscationDetector.detect(source))
    }

    @Test
    fun detectsObfuscatorIoBooleanIdiom() {
        val source = "function a(){while(!![]){b();}}"
        assertEquals(SourceObfuscation.OBFUSCATED, ObfuscationDetector.detect(source))
    }

    @Test
    fun detectsDeanEdwardsPacker() {
        val source = "eval(function(p,a,c,k,e,d){e=function(c){return c};return p}('x',1,1,'y'.split('|'),0,{}))"
        assertEquals(SourceObfuscation.OBFUSCATED, ObfuscationDetector.detect(source))
    }

    @Test
    fun detectsJsfuck() {
        val unit = "[+!+[]]+(!![]+[])[+[]]+([][[]]+[])[+!+[]]+"
        val source = unit.repeat(20)
        assertEquals(SourceObfuscation.OBFUSCATED, ObfuscationDetector.detect(source))
    }

    @Test
    fun detectsJjencode() {
        val source = "\$=~[];\$={___:++\$,\$\$\$\$:(![]+\"\")[\$]};"
        assertEquals(SourceObfuscation.OBFUSCATED, ObfuscationDetector.detect(source))
    }

    @Test
    fun detectsMinifiedSingleLine() {
        val statements = (0 until 400).joinToString("") { "var v$it=$it;" }
        val source = "// ==InuPlugin==\n// @name Test\n// ==/InuPlugin==\n$statements"
        assertEquals(SourceObfuscation.MINIFIED, ObfuscationDetector.detect(source))
    }

    @Test
    fun detectsMinifiedLongLines() {
        val line = (0 until 40).joinToString("") { "callSomething(v$it);" }
        val source = (0 until 10).joinToString("\n") { line }
        assertEquals(SourceObfuscation.MINIFIED, ObfuscationDetector.detect(source))
    }

    @Test
    fun shortSourceIsNeverMinified() {
        assertNull(ObfuscationDetector.detect("var a=1;var b=2;var c=a+b;"))
    }

    private fun fixture(name: String): String {
        val header = "// ==InuPlugin==\n// @name Sample\n// ==/InuPlugin==\n"
        return header + checkNotNull(javaClass.getResourceAsStream("/$name")) { "missing fixture $name" }
            .readBytes().toString(Charsets.UTF_8)
    }

    /** real `javascript-obfuscator` output (default string-array settings) */
    @Test
    fun detectsRealJavascriptObfuscatorOutput() {
        assertEquals(SourceObfuscation.OBFUSCATED, ObfuscationDetector.detect(fixture("obfuscated-sample.js")))
    }

    /** real `terser --compress --mangle` output */
    @Test
    fun detectsRealTerserOutput() {
        assertEquals(SourceObfuscation.MINIFIED, ObfuscationDetector.detect(fixture("minified-sample.js")))
    }

    /** real `javascript-obfuscator --identifier-names-generator mangled --string-array-rotate false` output: no `_0x` names, no `while(!![])` */
    @Test
    fun detectsRealObfuscatorOutputWithMangledNaming() {
        assertEquals(SourceObfuscation.OBFUSCATED, ObfuscationDetector.detect(fixture("obfuscated-mangled-sample.js")))
    }

    @Test
    fun tinyPluginThatIsMostlyOneAssetStringPasses() {
        val source = "const icon = '" + "A".repeat(8000) + "'\ninu.ui.setIcon(icon)\n"
        assertNull(ObfuscationDetector.detect(source))
    }

    @Test
    fun severalLongAssetLinesAmongRegularCodePass() {
        val assets = (0 until 3).joinToString("\n") { "const asset$it = 'data:image/png;base64,${"QUJD".repeat(3000)}'" }
        assertNull(ObfuscationDetector.detect(readablePlugin + "\n" + assets + "\n" + readablePlugin.lines().joinToString("\n")))
    }

    @Test
    fun minifiedChunkHiddenInReadableFileIsFlagged() {
        val hidden = (0 until 100).joinToString("") { "if(v$it>${it * 3}){handle$it(v$it)}else{skip$it()};" }
        val padding = readablePlugin.lines().joinToString("\n").repeat(10)
        assertEquals(
            SourceObfuscation.MINIFIED,
            ObfuscationDetector.detect(padding + "\n" + hidden + "\n" + padding),
        )
    }

    @Test
    fun hexLookupTableAloneIsNotObfuscation() {
        val table = (0 until 64).chunked(8).joinToString("\n") { row ->
            row.joinToString(" ") { "0x${(it * 31 + 7).toString(16)}," }
        }
        val source = "$readablePlugin\nconst CRC_TABLE = [\n$table\n]\n"
        assertNull(ObfuscationDetector.detect(source))
    }
}
