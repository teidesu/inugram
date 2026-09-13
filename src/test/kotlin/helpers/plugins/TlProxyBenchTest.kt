package desu.inugram.helpers.plugins

import android.util.Log
import org.json.JSONObject
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.telegram.messenger.MessagesController
import org.telegram.tgnet.TLRPC
import kotlin.test.assertEquals

/**
 * Not a test of behaviour: a benchmark of the TL view path a plugin pays for, on a real engine and
 * the real `TlHandles`, so a change to the proxies is measured rather than guessed at. It prints
 * to logcat under `InuBench` and asserts only that the reads answered what was seeded.
 *
 * Timed from inside the engine with `performance.now()`, so a number covers the JNI crossing and
 * the kotlin side of a read the way a plugin sees it.
 */
class TlProxyBenchTest {
    private val selfId = 100L
    private val sessions = ArrayList<PluginSession>()

    @Before
    fun setUp() {
        resetBridge()
        TestApp.signInAs(0, TLRPC.TL_user().apply { id = selfId; access_hash = selfId * 10 })
    }

    @After
    fun tearDown() {
        for (session in sessions) {
            session.engine.stopCallbacks()
            desu.inugram.helpers.plugins.platform.PluginJvm.detach(session)
            session.engine.close()
        }
        sessions.clear()
    }

    private fun engineFor(): Plugin {
        val plugin = startPlugin("tl-bench", "account.read(self,peers,dialogs)")
        val engine = QuickJs()
        plugin.session = PluginSession(plugin, engine)
        attachBridge(
            plugin.session!!,
            object : CoreListener {
                override fun onConsole(level: Int, message: String) {
                    Log.d("InuBench", message)
                }
                override fun onTimerSchedule(delayMs: Long) = Unit
            },
            accountsJson = { """[{"id":0,"userId":$selfId,"isCurrent":true,"isPremium":false}]""" },
        )
        sessions.add(plugin.session!!)
        return plugin
    }

    private fun Plugin.js(code: String): String = engine!!.evaluate(code.trimIndent()) ?: "null"

    private fun dialog(id: Long, date: Int) = TLRPC.TL_dialog().apply {
        this.id = id
        peer = if (id > 0) TLRPC.TL_peerUser().apply { user_id = id } else TLRPC.TL_peerChat().apply { chat_id = -id }
        last_message_date = date
        top_message = date * 2
        unread_count = 1
    }

    private fun seed(count: Int) = onUi {
        val controller = MessagesController.getInstance(0)
        val dialogs = (1..count).map { dialog(if (it % 4 == 0) -it.toLong() else it.toLong(), 1_000_000 + it) }
        controller.allDialogs.clear()
        controller.dialogsByFolder.put(0, ArrayList(dialogs))
        controller.dialogsByFolder.put(1, ArrayList())
        controller.allDialogs.addAll(dialogs)
        for (d in dialogs) controller.dialogs_dict.put(d.id, d)
    }

    /** runs a fetch to completion: the ui hop, the answer, and the promise jobs it queues */
    private fun Plugin.await(code: String) {
        js("globalThis.done = false; $code")
        repeat(20) {
            settle()
            if (js("String(globalThis.done)") == "true") return
        }
        error("promise never settled")
    }

    private fun Plugin.round(count: Int): JSONObject {
        await(
            """
            (() => {
              globalThis.t = {};
              const t0 = performance.now();
              inu.account(0).getDialogsCached({ archive: 'keep' }).then((ds) => {
                t.fetch = performance.now() - t0;
                globalThis.ds = ds;
                globalThis.done = true;
              });
            })()
            """,
        )
        js(
            """
            (() => {
              let t1 = performance.now();
              let sum = 0n;
              for (const d of ds) { sum += BigInt(d.id); sum += BigInt(d.last_message_date); }
              t.coldReads = performance.now() - t1;
              t1 = performance.now();
              for (const d of ds) { sum += BigInt(d.id); sum += BigInt(d.last_message_date); }
              t.cachedReads = performance.now() - t1;
              t1 = performance.now();
              let kinds = 0;
              for (const d of ds) { if (d._ === 'dialog') kinds++; }
              t.typeReads = performance.now() - t1;
              t1 = performance.now();
              let peers = 0;
              for (const d of ds) { if (d.peer) peers++; }
              t.childReads = performance.now() - t1;
              t1 = performance.now();
              let users = 0;
              for (const d of ds) { if (d.peer._ === 'peerUser') users++; }
              t.nestedReads = performance.now() - t1;
              t.count = ds.length;
              t.kinds = kinds;
              t.users = users;
              t.sum = sum.toString();
            })()
            """,
        )
        return JSONObject(js("JSON.stringify(t)"))
    }

    /**
     * what the projection costs on the far side: the same payload parsed by quickjs, timed in js,
     * against a handle read whole. It says whether a wire format is worth changing or whether the
     * cost is all in building the object a view reads from.
     */
    @Test
    fun bench_projection_parse() {
        val count = 200
        seed(count)
        val plugin = engineFor()
        val handles = plugin.session!!.tl
        val one = handles.project(handles.mintForPlugin(dialog(1L, 1_000_001), readOnly = true))
        val payload = (1..count).joinToString(",", "[", "]") { one }
        val fewKeys = "{\"a\":${JSONObject.quote("x".repeat(one.length - 12))}}"
        val numbers = (1..20).joinToString(",", "{", "}") { "\"f$it\":$it" }
        plugin.js(
            "globalThis.one = ${JSONObject.quote(one)};" +
                " globalThis.many = ${JSONObject.quote(payload)};" +
                " globalThis.fewKeys = ${JSONObject.quote(fewKeys)};" +
                " globalThis.numbers = ${JSONObject.quote(numbers)}",
        )
        val timings = JSONObject(
            plugin.js(
                """
                (() => {
                  const t = {};
                  for (const round of [0, 1, 2, 3, 4, 5]) {
                    let start = performance.now();
                    for (let i = 0; i < $count; i++) JSON.parse(one);
                    t.perObject = performance.now() - start;
                    start = performance.now();
                    JSON.parse(many);
                    t.wholePage = performance.now() - start;
                    // the same bytes with a tenth of the properties: if this is much faster, the
                    // cost is per key and per value, which no wire format can take away
                    start = performance.now();
                    for (let i = 0; i < $count; i++) JSON.parse(fewKeys);
                    t.fewKeys = performance.now() - start;
                    // the same properties with no string values to intern
                    start = performance.now();
                    for (let i = 0; i < $count; i++) JSON.parse(numbers);
                    t.numbers = performance.now() - start;
                  }
                  t.bytes = one.length;
                  return JSON.stringify(t);
                })()
                """,
            ),
        )
        Log.i(
            "InuBench",
            "projection parse, $count objects of ${timings.getInt("bytes")} chars:" +
                " perObject=${"%.2f".format(timings.getDouble("perObject"))}ms" +
                " (${"%.1f".format(timings.getDouble("perObject") / count * 1000)}us each)" +
                " wholePageAsOneArray=${"%.2f".format(timings.getDouble("wholePage"))}ms" +
                " sameBytesOneKey=${"%.1f".format(timings.getDouble("fewKeys") / count * 1000)}us" +
                " twentyNumbers=${"%.1f".format(timings.getDouble("numbers") / count * 1000)}us",
        )
        assertEquals(count, payload.split("},{").size)
    }

    /** the host halves on their own, so a slow round says which side it is: the mint or the projection */
    @Test
    fun bench_host_mint_and_projection() {
        val count = 200
        seed(count)
        val plugin = engineFor()
        val handles = plugin.session!!.tl
        val dialogs = (1..count).map { dialog(it.toLong(), 1_000_000 + it) }
        fun rounds(round: (org.telegram.tgnet.TLObject) -> Unit): List<Double> = (1..6).map {
            val start = System.nanoTime()
            for (d in dialogs) round(d)
            (System.nanoTime() - start) / 1_000_000.0
        }

        val mint = rounds { handles.mintForPlugin(it, readOnly = true) }
        // what a plugin's own read pays: the handle, then the projection that rides on it. The
        // first round is the one production gets: this runs once per sheet, interpreted
        val project = rounds { handles.project(handles.mintForPlugin(it, readOnly = true)) }
        Log.i(
            "InuBench",
            "host halves, $count dialogs: mint=${"%.2f".format(mint.drop(1).sorted()[2])}ms" +
                " mint+project cold=${"%.2f".format(project[0])}ms (${"%.1f".format(project[0] / count * 1000)}us each)" +
                " warm=${"%.2f".format(project.drop(1).sorted()[2])}ms (${"%.1f".format(project.drop(1).sorted()[2] / count * 1000)}us each)" +
                " rounds=${project.map { "%.1f".format(it) }}",
        )
        run {
            val cls = dialogs[0].javaClass
            val infos = desu.inugram.helpers.plugins.tl.TlReflect.fieldInfos(cls)
            val scalars = infos.values.count { it.isScalar && !it.isFlagWord }
            val present = infos.values.count { !it.isFlagWord && it.isPresent(dialogs[0]) }
            fun timed(what: String, body: () -> Unit) {
                val rounds = (1..6).map {
                    val start = System.nanoTime()
                    for (d in dialogs) body()
                    (System.nanoTime() - start) / 1_000_000.0
                }
                Log.i("InuBench", "  $what cold=${"%.1f".format(rounds[0])}ms warm=${"%.1f".format(rounds.drop(1).sorted()[2])}ms")
            }
            Log.i("InuBench", "TL_dialog: ${infos.size} fields, $scalars scalar, $present present on the sample")
            val pre = dialogs.map { handles.mintForPlugin(it, readOnly = true) }
            var sink = 0
            timed("reflection only (isPresent + get)") {
                for ((_, info) in infos) {
                    if (info.isFlagWord) continue
                    if (info.isPresent(dialogs[0])) sink += info.field.get(dialogs[0])?.hashCode() ?: 0
                }
            }
            timed("project on a minted handle") { handles.project(pre[0]) }
            timed("mintForPlugin only") { handles.mintForPlugin(dialogs[0], readOnly = true) }
            assertEquals(sink, sink)
        }
        val minted = dialogs.map { handles.mintForPlugin(it, readOnly = true) }
        // 40 rounds, reported as first and last: a read this small is interpreted until ART's jit
        // has seen it enough times, and the difference says whether a number is the code or the jit
        fun readRounds(round: (Long) -> Unit): List<Double> = (1..40).map {
            val start = System.nanoTime()
            for (handle in minted) round(handle)
            (System.nanoTime() - start) / 1_000_000.0
        }
        fun readMedian(round: (Long) -> Unit): Double {
            val rounds = readRounds(round)
            val early = rounds.take(5).sorted()[2]
            val late = rounds.takeLast(10).sorted()[5]
            Log.i("InuBench", "  early=${"%.2f".format(early)}ms late=${"%.2f".format(late)}ms (${"%.1f".format(late / count * 1000)}us each warm)")
            return late
        }

        // `id` is a long the projection already carries; `peer` is the object it cannot, and the
        // difference between them is what minting a child handle costs on this side
        val scalar = readMedian { handles.tlGet(it, "id") }
        val nested = readMedian { handles.tlGet(it, "peer") }
        Log.i(
            "InuBench",
            "host reads, $count dialogs: tlGet(id)=${"%.2f".format(scalar)}ms tlGet(peer)=${"%.2f".format(nested)}ms" +
                " (${"%.1f".format(scalar / count * 1000)}us / ${"%.1f".format(nested / count * 1000)}us each)",
        )
        assertEquals(count, dialogs.size)
    }

    @Test
    fun bench_dialog_views() {
        val count = 200
        seed(count)
        val plugin = engineFor()
        val rounds = (1..6).map { plugin.round(count) }
        val warm = rounds.drop(1)
        fun median(key: String): Double = warm.map { it.getDouble(key) }.sorted()[warm.size / 2]
        val line = listOf("fetch", "coldReads", "cachedReads", "typeReads", "childReads", "nestedReads")
            .joinToString(" ") { "$it=${"%.2f".format(median(it))}ms" }
        Log.i("InuBench", "tl views, $count dialogs, median of ${warm.size}: $line")
        // `childReads` is one crossing and a child view; `nestedReads` repeats it and then reads the
        // child's own type name, so the difference is what a second crossing costs on a warm view
        Log.i(
            "InuBench",
            "per crossing: child=${"%.1f".format(median("childReads") / count * 1000)}us" +
                " child+type=${"%.1f".format(median("nestedReads") / count * 1000)}us",
        )
        Log.i("InuBench", "tl views per dialog: coldRead=${"%.1f".format(median("coldReads") / count / 2 * 1000)}us cachedRead=${"%.1f".format(median("cachedReads") / count / 2 * 1000)}us fetch=${"%.1f".format(median("fetch") / count * 1000)}us")
        val last = rounds.last()
        assertEquals(count, last.getInt("count"))
        assertEquals(count, last.getInt("kinds"))
        assertEquals(count - count / 4, last.getInt("users"))
    }
    /**
     * many small async reads at once, so what dominates is the settle path each one takes - the host
     * call, the queue hop back, the native settle and the promise job - rather than the payload
     */
    @Test
    fun bench_settle_round_trips() {
        val count = 200
        seed(1)
        val plugin = engineFor()
        val rounds = (1..6).map {
            val start = System.nanoTime()
            plugin.await(
                """
                (() => {
                  const t0 = performance.now();
                  const all = [];
                  for (let i = 0; i < $count; i++) all.push(inu.account(0).getDialogsCached({ archive: 'keep' }));
                  globalThis.submit = performance.now() - t0;
                  Promise.all(all).then((lists) => {
                    globalThis.settled = lists.length;
                    globalThis.done = true;
                  });
                })()
                """,
            )
            val total = (System.nanoTime() - start) / 1_000_000.0
            assertEquals(count, plugin.js("globalThis.settled").toInt())
            total to plugin.js("globalThis.submit").toDouble()
        }
        val warm = rounds.drop(1)
        val total = warm.map { it.first }.sorted()[warm.size / 2]
        val submit = warm.map { it.second }.sorted()[warm.size / 2]
        Log.i(
            "InuBench",
            "settle round trips, $count at once, median of ${warm.size}: total=${"%.2f".format(total)}ms" +
                " submit=${"%.2f".format(submit)}ms perSettle=${"%.1f".format(total / count * 1000)}us",
        )
    }
}
