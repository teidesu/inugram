import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.eclipse.equinox.app.IApplication;
import org.eclipse.equinox.app.IApplicationContext;
import org.eclipse.mat.util.IProgressListener;
import org.eclipse.mat.util.VoidProgressListener;
import org.eclipse.mat.snapshot.ISnapshot;
import org.eclipse.mat.snapshot.SnapshotFactory;
import org.eclipse.mat.snapshot.IPathsFromGCRootsComputer;
import org.eclipse.mat.snapshot.model.IClass;
import org.eclipse.mat.snapshot.model.IObject;
import org.eclipse.mat.snapshot.model.NamedReference;

/**
 * Generic Eclipse MAT probe, packaged as an OSGi IApplication so it runs inside
 * MAT's equinox runtime (the parser plugins need a real OSGi context; running
 * SnapshotFactory from a plain `java -jar` fails with ParserPlugin.getDefault()==null).
 *
 * Why this exists: MAT's HTML "Leak Suspects / Details" renderer can crash
 * (AIOOBE in MultiplePath2GCRootsQuery) on corrupt strings, and the HTML reports
 * don't answer the questions that actually matter for multiply-held cohorts:
 * "who are ALL the strong holders", "is this path strong or does it die at a weak
 * ref", "how many of X are stuck as NotificationCenter observers". This does.
 *
 * Usage (see build_and_run.sh):
 *   heapprobe <dump.ec.hprof> <command> <args...>
 *
 * Commands:
 *   counts    <class> [<class>...]              instance count per class
 *   histogram <class> [<class>...]              inbound-referrer class histogram
 *   inbound   <class> [limit=6]                 per-instance inbound refs, sorted by retained
 *   paths     <class> [objs=3] [paths=4]        STRONG GC-root paths (weak/soft/phantom excluded)
 *   tally     <class> [kw1,kw2,...]             one shortest strong path per instance +
 *                                               first-strong-holder distribution
 *   observers <class>                           how many instances sit in NotificationCenter
 *                                               observer lists, grouped by eventId
 */
public class HeapProbeApp implements IApplication {

    private static final IProgressListener PROGRESS = new VoidProgressListener();
    private ISnapshot snapshot;

    @Override
    public Object start(IApplicationContext ctx) throws Exception {
        String[] args = (String[]) ctx.getArguments().get("application.args");
        if (args == null || args.length < 2) {
            System.out.println("usage: heapprobe <dump> <command> <args...>");
            System.out.println("commands: counts|histogram|inbound|paths|tally|observers");
            return IApplication.EXIT_OK;
        }
        File dump = new File(args[0]);
        String cmd = args[1];
        String[] rest = Arrays.copyOfRange(args, 2, args.length);

        snapshot = SnapshotFactory.openSnapshot(dump, new HashMap<String, String>(), PROGRESS);
        System.out.println("PROBE heap=" + snapshot.getSnapshotInfo().getUsedHeapSize()
            + " objects=" + snapshot.getSnapshotInfo().getNumberOfObjects());

        try {
            switch (cmd) {
                case "counts":    for (String c : rest) counts(c); break;
                case "histogram": for (String c : rest) inboundHistogram(c); break;
                case "inbound":   inboundDetail(rest[0], rest.length > 1 ? Integer.parseInt(rest[1]) : 6); break;
                case "paths":     gcPaths(rest[0],
                                      rest.length > 1 ? Integer.parseInt(rest[1]) : 3,
                                      rest.length > 2 ? Integer.parseInt(rest[2]) : 4); break;
                case "tally":     tally(rest[0], rest.length > 1 ? rest[1] : ""); break;
                case "observers": countObservers(rest[0]); break;
                default: System.out.println("unknown command: " + cmd);
            }
        } finally {
            snapshot.dispose();
        }
        return IApplication.EXIT_OK;
    }

    @Override
    public void stop() {}

    // ---- commands -----------------------------------------------------------

    private void counts(String className) throws Exception {
        int[] ids = idsFor(className);
        long shallow = 0;
        for (int id : ids) shallow += snapshot.getHeapSize(id);
        System.out.println("\n==== COUNT " + className + " instances=" + ids.length
            + " shallowTotal=" + shallow + " ====");
    }

    private void inboundHistogram(String className) throws Exception {
        int[] ids = idsFor(className);
        System.out.println("\n==== INBOUND HISTOGRAM " + className + " count=" + ids.length + " ====");
        Map<String, int[]> hist = new HashMap<>();
        for (int id : ids)
            for (int from : snapshot.getInboundRefererIds(id))
                hist.computeIfAbsent(snapshot.getObject(from).getClazz().getName(), k -> new int[1])[0]++;
        hist.entrySet().stream()
            .sorted((a, b) -> b.getValue()[0] - a.getValue()[0]).limit(30)
            .forEach(e -> System.out.println("  " + e.getValue()[0] + "  " + e.getKey()));
    }

    private void inboundDetail(String className, int limit) throws Exception {
        int[] ids = sortedByRetained(idsFor(className));
        System.out.println("\n==== INBOUND DETAIL " + className + " ====");
        for (int i = 0; i < Math.min(ids.length, limit); i++) {
            int id = ids[i];
            int[] inbound = snapshot.getInboundRefererIds(id);
            System.out.println("OBJECT " + obj(id) + " retained=" + retained(id) + " inbound=" + inbound.length);
            Integer[] boxed = Arrays.stream(inbound).boxed().toArray(Integer[]::new);
            Arrays.sort(boxed, Comparator.comparingLong((Integer v) -> retained(v)).reversed());
            for (int from : boxed)
                System.out.println("    <- " + refsTo(from, id) + " " + obj(from) + " retained=" + retained(from));
        }
    }

    private void gcPaths(String className, int limitObjects, int limitPaths) throws Exception {
        int[] ids = sortedByRetained(idsFor(className));
        Map<IClass, Set<String>> exc = excludeMap();
        System.out.println("\n==== STRONG GC-ROOT PATHS " + className + " (weak/soft/phantom excluded) ====");
        for (int i = 0; i < Math.min(ids.length, limitObjects); i++) {
            int id = ids[i];
            System.out.println("OBJECT " + obj(id) + " retained=" + retained(id));
            IPathsFromGCRootsComputer c = snapshot.getPathsFromGCRoots(id, exc);
            for (int p = 0; p < limitPaths; p++) {
                int[] path = c.getNextShortestPath();
                if (path == null) break;
                System.out.println("  PATH" + p + " len=" + path.length);
                int show = Math.min(path.length, 16);
                for (int k = 0; k < show; k++) {
                    String edge = k + 1 < path.length ? refsTo(path[k + 1], path[k]) : "[GCROOT]";
                    System.out.println("      " + edge + " " + obj(path[k]) + " retained=" + retained(path[k]));
                }
            }
        }
    }

    /**
     * One shortest STRONG path per instance, classified by its "first strong holder":
     * the first class along the path (object -> root) that matches one of the given
     * keyword substrings. Pass the candidate holder names you're testing, comma-separated
     * (e.g. "ChatBackgroundDrawable,LaunchActivity,ReactionsEffectOverlay"). With no
     * keywords, falls back to the first app-package class that isn't the target or a
     * generic container. The tally reveals a multiply-held cohort's root distribution.
     */
    private void tally(String className, String keywordCsv) throws Exception {
        int[] ids = idsFor(className);
        String[] keywords = keywordCsv.isEmpty() ? new String[0] : keywordCsv.split(",");
        Map<IClass, Set<String>> exc = excludeMap();
        System.out.println("\n==== TALLY " + className + " (n=" + ids.length + ") keywords=" + keywordCsv + " ====");
        Map<String, int[]> hist = new HashMap<>();
        for (int id : ids) {
            IPathsFromGCRootsComputer c = snapshot.getPathsFromGCRoots(id, exc);
            int[] path = c.getNextShortestPath();
            StringBuilder sb = new StringBuilder();
            String key = "?";
            if (path != null) {
                for (int k = 1; k < path.length; k++) {
                    String cn = snapshot.getObject(path[k]).getClazz().getName();
                    String edge = k + 1 < path.length ? refsTo(path[k + 1], path[k]) : "";
                    sb.append(" <- ").append(edge).append(cn);
                    if (key.equals("?") && matchesHolder(cn, className, keywords)) key = cn;
                }
            }
            System.out.println(obj(id) + sb);
            hist.computeIfAbsent(key, x -> new int[1])[0]++;
        }
        System.out.println("-- FIRST-STRONG-HOLDER TALLY --");
        hist.entrySet().stream().sorted((a, b) -> b.getValue()[0] - a.getValue()[0])
            .forEach(e -> System.out.println("  " + e.getValue()[0] + "  " + e.getKey()));
    }

    private boolean matchesHolder(String cn, String target, String[] keywords) {
        if (keywords.length > 0) {
            for (String kw : keywords) if (!kw.isEmpty() && cn.contains(kw)) return true;
            return false;
        }
        if (cn.equals(target)) return false;
        int dollar = target.indexOf('$');
        String targetOuter = dollar > 0 ? target.substring(0, dollar) : target;
        if (cn.startsWith(targetOuter + "$")) return false;
        if (cn.startsWith("java.") || cn.startsWith("javax.") || cn.startsWith("jdk.")
            || cn.startsWith("sun.") || cn.startsWith("android.") || cn.startsWith("androidx.")
            || cn.startsWith("[") || cn.endsWith("[]")) return false;
        return true;
    }

    private void countObservers(String className) throws Exception {
        Set<Long> targets = new HashSet<>();
        for (int id : idsFor(className)) targets.add(snapshot.mapIdToAddress(id));
        System.out.println("\n==== OBSERVER OCCURRENCES OF " + className + " totalInstances=" + targets.size() + " ====");
        Map<Integer, Integer> perEvent = new HashMap<>();
        int grand = 0;
        for (int ncId : idsFor("org.telegram.messenger.NotificationCenter")) {
            IObject nc = snapshot.getObject(ncId);
            Object keysO = nc.resolveValue("observers.mKeys");
            Object valsO = nc.resolveValue("observers.mValues");
            Object sizeO = nc.resolveValue("observers.mSize");
            if (!(keysO instanceof org.eclipse.mat.snapshot.model.IPrimitiveArray keys)) continue;
            if (!(valsO instanceof org.eclipse.mat.snapshot.model.IObjectArray vals)) continue;
            int size = sizeO instanceof Integer ? (Integer) sizeO : keys.getLength();
            long[] valAddr = vals.getReferenceArray();
            for (int i = 0; i < size && i < valAddr.length; i++) {
                if (valAddr[i] == 0) continue;
                IObject list = snapshot.getObject(snapshot.mapAddressToId(valAddr[i]));
                Object edO = list.resolveValue("elementData");
                if (!(edO instanceof org.eclipse.mat.snapshot.model.IObjectArray ed)) continue;
                int cnt = 0;
                for (long a : ed.getReferenceArray()) if (a != 0 && targets.contains(a)) cnt++;
                if (cnt > 0) {
                    int key = ((Number) keys.getValueAt(i)).intValue();
                    perEvent.merge(key, cnt, Integer::sum);
                    grand += cnt;
                }
            }
        }
        System.out.println("  grandTotalObserverSlots=" + grand
            + "  (NotificationCenter event id -> occurrences)");
        perEvent.entrySet().stream().sorted((a, b) -> b.getValue() - a.getValue())
            .forEach(e -> System.out.println("    eventId=" + e.getKey() + " occurrences=" + e.getValue()));
    }

    // ---- helpers ------------------------------------------------------------

    private Map<IClass, Set<String>> excludeMap() throws Exception {
        Map<IClass, Set<String>> m = new HashMap<>();
        String[] refs = {"java.lang.ref.WeakReference", "java.lang.ref.SoftReference",
            "java.lang.ref.PhantomReference", "java.lang.ref.FinalizerReference"};
        for (String r : refs)
            for (IClass c : snapshot.getClassesByName(r, true)) {
                Set<String> s = new HashSet<>();
                s.add("referent");
                m.put(c, s);
            }
        return m;
    }

    private int[] idsFor(String className) throws Exception {
        Collection<IClass> classes = snapshot.getClassesByName(className, false);
        if (classes == null || classes.isEmpty()) {
            System.out.println("  (no class named " + className + ")");
            return new int[0];
        }
        List<Integer> ids = new ArrayList<>();
        for (IClass c : classes) for (int id : c.getObjectIds()) ids.add(id);
        int[] r = new int[ids.size()];
        for (int i = 0; i < r.length; i++) r[i] = ids.get(i);
        return r;
    }

    private long retained(int id) {
        try { return snapshot.getRetainedHeapSize(id); } catch (Exception e) { return 0; }
    }

    private String refsTo(int from, int to) throws Exception {
        long target = snapshot.mapIdToAddress(to);
        List<String> names = new ArrayList<>();
        for (NamedReference ref : snapshot.getObject(from).getOutboundReferences())
            if (ref.getObjectAddress() == target) names.add(ref.getName());
        return names.toString();
    }

    private String obj(int id) {
        if (id < 0) return "<none>";
        try { return snapshot.getObject(id).getTechnicalName(); } catch (Exception e) { return "#" + id; }
    }

    private int[] sortedByRetained(int[] ids) {
        Integer[] boxed = Arrays.stream(ids).boxed().toArray(Integer[]::new);
        Arrays.sort(boxed, Comparator.comparingLong((Integer id) -> retained(id)).reversed());
        int[] sorted = new int[boxed.length];
        for (int i = 0; i < boxed.length; i++) sorted[i] = boxed[i];
        return sorted;
    }
}
