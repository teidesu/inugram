package desu.inugram.helpers.plugins

import android.util.Log
import desu.inugram.helpers.plugins.api.PluginLocalStorage
import org.json.JSONObject
import org.junit.After
import org.junit.Before
import org.junit.Test

class LocalStorageBenchTest {
    private val install = freshInstallId()
    private var plugin: Plugin? = null

    @Before
    fun setUp() = resetBridge()

    @After
    fun tearDown() {
        plugin?.let {
            closeEngine(it)
            PluginLocalStorage.wipe(install)
        }
    }

    @Test
    fun bench_local_storage_operations() {
        val plugin = startEngine("storage-bench", localStoragePath = PluginLocalStorage.pathFor(install)).also { plugin = it }
        val names = listOf("set", "get", "prop", "del", "bigSet", "keys", "walk", "setItems")
        val rounds = (1..6).map {
            JSONObject(
                plugin.engine!!.evaluate(
                    """
                    (() => {
                      localStorage.clear();
                      const time = (run) => { const t = performance.now(); run(); return performance.now() - t };
                      const value = 'v'.repeat(32), big = 'b'.repeat(10000);
                      const set = time(() => { for (let i = 0; i < 1000; i++) localStorage.setItem('k' + i, value) });
                      const get = time(() => { for (let i = 0; i < 1000; i++) localStorage.getItem('k' + i) });
                      const prop = time(() => { for (let i = 0; i < 1000; i++) localStorage['k' + i] });
                      const bigSet = time(() => { for (let i = 0; i < 50; i++) localStorage.setItem('big' + i, big) });
                      const keys = time(() => { for (let i = 0; i < 20; i++) Object.keys(localStorage) });
                      const walk = time(() => { for (let i = 0; i < 20; i++) for (let j = 0; j < localStorage.length; j++) localStorage.key(j) });
                      const batch = {};
                      for (let i = 0; i < 200; i++) batch['ins' + i] = value;
                      const setItems = time(() => { for (let i = 0; i < 20; i++) localStorage.setItems(batch) });
                      const del = time(() => { for (let i = 0; i < 1000; i++) localStorage.removeItem('k' + i) });
                      localStorage.clear();
                      return JSON.stringify({ set, get, prop, del, bigSet, keys, walk, setItems });
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
            "localStorage, median of ${rounds.size}: set=${us("set", 1000)}us get=${us("get", 1000)}us prop=${us("prop", 1000)}us del=${us("del", 1000)}us " +
                "set10k=${us("bigSet", 50)}us | ~1.5k entries: keys=${ms("keys", 20)}ms walk=${ms("walk", 20)}ms setItems200=${ms("setItems", 20)}ms",
        )
    }
}
