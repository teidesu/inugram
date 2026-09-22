package desu.inugram.helpers.plugins

import desu.inugram.helpers.plugins.platform.PluginJvm
import desu.inugram.jvmfixture.JvmFixture
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.Before
import org.junit.Test

class PluginJvmClassTest {
    @Before fun setUp() = resetBridge()

    private fun runWithEngine(code: String, after: (QuickJs, PluginSession) -> Unit = { _, _ -> }) {
        val plugin = startPlugin("defined class", "unsafe.jvm")
        val engine = QuickJs()
        plugin.session = PluginSession(plugin, engine)
        attachBridge(plugin.session!!, object : CoreListener {
            override fun onConsole(level: Int, message: String) = Unit
            override fun onTimerSchedule(delayMs: Long) = Unit
        })
        try {
            assertEquals("ok", engine.evaluate(code.trimIndent()))
            after(engine, plugin.session!!)
        } finally {
            engine.stopCallbacks()
            PluginJvm.detach(plugin.session!!)
            engine.close()
            JvmFixture.task = null
            plugin.session = null
        }
    }

    @Test fun fields_constructors_methods_and_static_methods_execute_routines() = runWithEngine("""
        const type = inu.jvm.defineClass('inu.test.DefinedMethods', {
            fields: { count: 'int' }, staticFields: { tag: 'java.lang.String' },
            constructors: [{ params: ['int'], init: inu.jvm.routine({ v: 1, source: '', captures: [], slots: 0, tries: [], code: [['this'], ['arg', [0]], ['set', 0, ['count'], 1]] }) }],
            methods: { add: { params: ['int'], returns: 'int', body: inu.jvm.routine({ v: 1, source: '', captures: [], slots: 0, tries: [], code: [['this'], ['get', 0, ['count']], ['arg', [0]], ['add', 1, 2], ['return', 3]] }) } },
            staticMethods: { getTag: { returns: 'java.lang.String', body: inu.jvm.routine({ v: 1, source: '', captures: [], slots: 0, tries: [], code: [['this'], ['get', 0, ['tag']], ['return', 1]] }) } },
        });
        const instance = new type(40);
        if (instance.getField('count') !== 40 || instance.call('add', 2) !== 42) throw Error('instance dispatch');
        type.setStaticField('tag', 'static');
        if (type.callStatic('getTag') !== 'static') throw Error('static dispatch');
        'ok';
    """)

    @Test fun constructors_forward_widened_arguments_and_constants_and_covariant_overrides_dispatch() = runWithEngine("""
        const base = inu.jvm.cls('desu.inugram.jvmfixture.JvmClassFixture');
        const type = inu.jvm.defineClass('inu.test.DefinedSubclass', {
            superclass: base,
            constructors: [{ params: ['int'], super: [{ arg: 0 }, { value: 'base' }] }],
            methods: { getText: { params: [], returns: 'java.lang.String', body: inu.jvm.routine({ v: 1, source: '', captures: [], slots: 0, tries: [], code: [['return', ['port']]] }) } },
        });
        const instance = new type(42);
        if (instance.call('getNumber') !== 42 || instance.call('getLabel') !== 'base') throw Error('super arguments');
        if (base.getDeclaredMethod('getText()Ljava/lang/CharSequence;').invoke(instance) !== 'port') throw Error('covariant dispatch');
        'ok';
    """)

    @Test fun interfaces_and_void_callbacks_survive_plugin_unload() = runWithEngine("""
        const type = inu.jvm.defineClass('inu.test.DefinedRunnable', {
            interfaces: [inu.jvm.cls('java.lang.Runnable')],
            methods: { run: { body: inu.jvm.routine({ v: 1, source: '', captures: [], slots: 0, tries: [], code: [['return']] }) } },
        });
        const instance = new type();
        instance.call('run');
        inu.jvm.cls('desu.inugram.jvmfixture.JvmFixture').setStaticField('task', instance);
        'ok';
    """) { engine, session ->
        val task = JvmFixture.task!!
        task.run()
        engine.stopCallbacks()
        PluginJvm.detach(session)
        task.run()
    }

    @Test fun final_superclasses_and_missing_abstract_methods_are_refused() = runWithEngine("""
        for (const parent of ['java.lang.String', 'desu.inugram.jvmfixture.JvmAbstractClassFixture']) {
            let refused = false;
            try { inu.jvm.defineClass('inu.test.InvalidClass', { superclass: inu.jvm.cls(parent), constructors: [] }); }
            catch (error) { refused = error.code === 'invalid-argument'; }
            if (!refused) throw Error('invalid superclass accepted');
        }
        'ok';
    """)

    @Test fun cancelled_preparations_release_their_quota() {
        val plugin = startPlugin("class preparation", "unsafe.jvm")
        val definition = """{"name":"inu.test.Pending","superclass":null,"interfaces":[],"fields":[],"methods":[]}"""
        repeat(130) {
            val wire = plugin.js.listener!!.jvm(PluginJvm.OP_PREPARE_CLASS, 0, definition, emptyArray())
            assertTrue(wire.startsWith("S"), wire)
            val ticket = org.json.JSONObject(wire.substring(1)).getString("ticket").toLong()
            assertEquals("N", plugin.js.listener!!.jvm(PluginJvm.OP_CANCEL_CLASS, ticket, "", emptyArray()))
        }
    }
    @Test fun failed_loads_consume_preparations() {
        val plugin = startPlugin("class load failure", "unsafe.jvm")
        val definition = """{"name":"inu.test.Pending","superclass":null,"interfaces":[],"fields":[],"methods":[]}"""
        repeat(130) {
            val wire = plugin.js.listener!!.jvm(PluginJvm.OP_PREPARE_CLASS, 0, definition, emptyArray())
            assertTrue(wire.startsWith("S"), wire)
            val ticket = org.json.JSONObject(wire.substring(1)).getString("ticket").toLong()
            val failed = plugin.js.listener!!.jvm(PluginJvm.OP_LOAD_CLASS, ticket, "", arrayOf("Y"))
            assertTrue(failed.startsWith("E"), failed)
            val expired = plugin.js.listener!!.jvm(PluginJvm.OP_LOAD_CLASS, ticket, "", arrayOf("Y"))
            assertTrue(expired.startsWith("Phandle-expired"), expired)
        }
    }

}
