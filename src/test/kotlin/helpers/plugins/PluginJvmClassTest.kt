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
        val plugin = startEngine("defined class", "unsafe.jvm")
        val engine = plugin.engine!!
        try {
            assertEquals("ok", engine.evaluate(code.trimIndent()))
            after(engine, plugin.session!!)
        } finally {
            closeEngine(plugin)
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

    @Test fun an_array_of_method_specs_defines_overloads() = runWithEngine("""
        const type = inu.jvm.defineClass('inu.test.DefinedOverloads', {
            methods: { pick: [
                { params: ['int'], returns: 'java.lang.String', body: inu.jvm.routine({ v: 1, source: '', captures: [], slots: 0, tries: [], code: [['arg', [0]], ['add', ['int '], 0], ['return', 1]] }) },
                { params: ['java.lang.String'], returns: 'java.lang.String', body: inu.jvm.routine({ v: 1, source: '', captures: [], slots: 0, tries: [], code: [['arg', [0]], ['add', ['string '], 0], ['return', 1]] }) },
            ] },
        });
        const instance = new type();
        if (type.getDeclaredMethod('pick(I)Ljava/lang/String;').invoke(instance, 1) !== 'int 1') throw Error('int overload');
        if (type.getDeclaredMethod('pick(Ljava/lang/String;)Ljava/lang/String;').invoke(instance, 'a') !== 'string a') throw Error('string overload');
        'ok';
    """)

    @Test fun a_super_routine_computes_the_super_arguments_from_the_constructors() = runWithEngine("""
        const base = inu.jvm.cls('desu.inugram.jvmfixture.JvmClassFixture');
        const type = inu.jvm.defineClass('inu.test.DefinedComputedSuper', {
            superclass: base,
            constructors: [{
                params: ['int'],
                super: inu.jvm.routine({ v: 1, source: '', captures: [], slots: 0, tries: [], code: [['arg', [0]], ['mul', 0, [2]], ['add', ['label '], 0], ['array', [1, 2]], ['return', 3]] }),
            }],
        });
        const instance = new type(21);
        if (instance.call('getNumber') !== 42 || instance.call('getLabel') !== 'label 21') throw Error('computed super arguments');
        'ok';
    """)

    @Test fun get_super_in_a_routine_body_calls_the_bound_classes_super() = runWithEngine("""
        const base = inu.jvm.cls('desu.inugram.jvmfixture.JvmClassFixture');
        const type = inu.jvm.defineClass('inu.test.DefinedGetSuper', {
            superclass: base,
            constructors: [{ params: ['int'], super: [{ arg: 0 }, { value: 'base' }] }],
            methods: { getText: { params: [], returns: 'java.lang.String', body: inu.jvm.routine({ v: 1, source: '', captures: [], slots: 0, tries: [], code: [['owner'], ['this'], ['callSuper', 0, 1, ['getText'], []], ['add', ['super said '], 2], ['return', 3]] }) } },
        });
        const instance = new type(1);
        if (instance.call('getNumber') !== 1) throw Error('super arguments');
        if (base.getDeclaredMethod('getText()Ljava/lang/CharSequence;').invoke(instance) !== 'super said base') throw Error('getSuper dispatch');
        const unbound = inu.jvm.routine({ v: 1, source: '', captures: [], slots: 0, tries: [], code: [['owner'], ['return', 0]] });
        let refused = false;
        try { unbound.call('run'); } catch (error) { refused = true; }
        if (!refused) throw Error('an unbound routine had an owner');
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

    @Test fun an_unnamed_class_is_named_by_the_host_and_never_twice() = runWithEngine("""
        const spec = {
            interfaces: [inu.jvm.cls('java.lang.Runnable')],
            methods: { run: { body: inu.jvm.routine({ v: 1, source: '', captures: [], slots: 0, tries: [], code: [['return']] }) } },
        };
        const first = inu.jvm.defineClass(spec);
        const second = inu.jvm.defineClass(spec);
        if (!first.name.startsWith('inu.plugins.')) throw Error('host did not name it: ' + first.name);
        if (first.name === second.name) throw Error('two classes were given one name');
        new first().call('run');
        'ok';
    """)

    @Test fun final_superclasses_and_missing_abstract_methods_are_refused() = runWithEngine("""
        for (const parent of ['java.lang.String', 'desu.inugram.jvmfixture.JvmAbstractClassFixture']) {
            let refused = false;
            try { inu.jvm.defineClass('inu.test.InvalidClass', { superclass: inu.jvm.cls(parent), constructors: [] }); }
            catch (error) { refused = error.code === 'invalid-argument'; }
            if (!refused) throw Error('invalid superclass accepted');
        }
        'ok';
    """)

    @Test fun a_cancelled_or_failed_preparation_releases_its_quota() {
        val plugin = startPlugin("class preparation", "unsafe.jvm")
        val listener = plugin.js.listener!!
        val definition = """{"name":"inu.test.Pending","superclass":null,"interfaces":[],"fields":[],"methods":[]}"""
        fun prepare(): Long {
            val wire = listener.jvm(PluginJvm.OP_PREPARE_CLASS, 0, definition, emptyArray())
            assertTrue(wire.startsWith("S"), wire)
            return org.json.JSONObject(wire.substring(1)).getString("ticket").toLong()
        }
        repeat(130) { assertEquals("N", listener.jvm(PluginJvm.OP_CANCEL_CLASS, prepare(), "", emptyArray())) }
        repeat(130) {
            val ticket = prepare()
            val failed = listener.jvm(PluginJvm.OP_LOAD_CLASS, ticket, "", arrayOf("Y"))
            assertTrue(failed.startsWith("E"), failed)
            val expired = listener.jvm(PluginJvm.OP_LOAD_CLASS, ticket, "", arrayOf("Y"))
            assertTrue(expired.startsWith("Phandle-expired"), expired)
        }
    }
}
