package desu.inugram.helpers.plugins

import android.util.Log
import desu.inugram.helpers.plugins.api.PluginKv
import org.json.JSONObject
import org.junit.After
import org.junit.Before
import org.junit.Test

class KvBenchTest {
    private val install = freshInstallId()
    private var plugin: Plugin? = null

    @Before
    fun setUp() = resetBridge()

    @After
    fun tearDown() {
        plugin?.let {
            closeEngine(it)
            PluginKv.wipe(install)
        }
    }

    @Test
    fun bench_kv_operations() {
        val plugin = startEngine("kv-bench", "kv", kvPath = PluginKv.pathFor(install)).also { plugin = it }
        val names = listOf("set", "get", "has", "del", "bigSet", "getAll", "keys", "usage", "insertAll")
        val rounds = (1..6).map {
            JSONObject(
                plugin.engine!!.evaluate(
                    """
                    (() => {
                      inu.kv.clear();
                      const time = (run) => { const t = performance.now(); run(); return performance.now() - t };
                      const value = 'v'.repeat(32), big = 'b'.repeat(10000);
                      const set = time(() => { for (let i = 0; i < 1000; i++) inu.kv.set('k' + i, value) });
                      const get = time(() => { for (let i = 0; i < 1000; i++) inu.kv.get('k' + i) });
                      const has = time(() => { for (let i = 0; i < 1000; i++) inu.kv.has('k' + i) });
                      const bigSet = time(() => { for (let i = 0; i < 50; i++) inu.kv.set('big' + i, big) });
                      const getAll = time(() => { for (let i = 0; i < 20; i++) inu.kv.getAll() });
                      const keys = time(() => { for (let i = 0; i < 20; i++) inu.kv.keys() });
                      const usage = time(() => { for (let i = 0; i < 20; i++) inu.kv.usage() });
                      const batch = {};
                      for (let i = 0; i < 200; i++) batch['ins' + i] = value;
                      const insertAll = time(() => { for (let i = 0; i < 20; i++) inu.kv.insertAll(batch) });
                      const del = time(() => { for (let i = 0; i < 1000; i++) inu.kv.del('k' + i) });
                      inu.kv.clear();
                      return JSON.stringify({ set, get, has, del, bigSet, getAll, keys, usage, insertAll });
                    })()
                    """.trimIndent(),
                )!!,
            )
        }.drop(1)
        val median = names.associateWith { name -> rounds.map { it.getDouble(name) }.sorted()[rounds.size / 2] }
        fun us(name: String, count: Int) = "%.1f".format(median.getValue(name) * 1000 / count)
        fun ms(name: String, count: Int) = "%.2f".format(median.getValue(name) / count)
        Log.i(
            "InuBench",
            "kv, median of ${rounds.size}: set=${us("set", 1000)}us get=${us("get", 1000)}us has=${us("has", 1000)}us del=${us("del", 1000)}us " +
                "set10k=${us("bigSet", 50)}us | ~1.5k entries: getAll=${ms("getAll", 20)}ms keys=${ms("keys", 20)}ms usage=${ms("usage", 20)}ms insertAll200=${ms("insertAll", 20)}ms",
        )
    }
}
