package iaccess;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.awt.Component;
import java.awt.Container;
import java.awt.Window;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executors;

/**
 * Java agent loaded (via the Attach API) into the IBM i Access Client Solutions JVM.
 * It does not modify any ACS class. It looks up the live HOD ECLSession objects through
 * the AWT component tree and exposes a small tool API on a loopback-only HTTP port.
 * All access to HOD classes is reflective, so the agent has no compile-time dependency on ACS.
 */
public final class AgentImpl {
    private static HttpServer server;
    private static java.util.concurrent.ExecutorService executor;
    private static String token;
    private static Path infoFile;

    /** Called reflectively by the bootstrap {@link Boot}; version identifies the jar copy this code came from. */
    public static synchronized void start(String version) throws Exception {
        byte[] t = new byte[24];
        new SecureRandom().nextBytes(t);
        StringBuilder sb = new StringBuilder();
        for (byte b : t) sb.append(String.format("%02x", b));
        token = sb.toString();

        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/call", AgentImpl::handle);
        executor = Executors.newSingleThreadExecutor(r -> {
            Thread th = new Thread(r, "iaccess-mcp-agent");
            th.setDaemon(true);
            return th;
        });
        server.setExecutor(executor);
        server.start();

        long pid = ProcessHandle.current().pid();
        infoFile = Dirs.infoDir().resolve("agent-" + pid + ".json");
        Dirs.ensure();
        Map<String, Object> info = new LinkedHashMap<>();
        info.put("port", server.getAddress().getPort());
        info.put("token", token);
        info.put("pid", pid);
        info.put("version", version);
        Files.write(infoFile, Json.write(info).getBytes(StandardCharsets.UTF_8));
        final Path mine = infoFile;
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            try { Files.deleteIfExists(mine); } catch (Exception ignored) { }
        }));
    }

    /** Stops the HTTP endpoint (used when a newer agent version replaces this one). */
    public static synchronized void stop() {
        try { if (server != null) server.stop(0); } catch (Exception ignored) { }
        try { if (executor != null) executor.shutdownNow(); } catch (Exception ignored) { }
        try { if (infoFile != null) Files.deleteIfExists(infoFile); } catch (Exception ignored) { }
        server = null;
    }

    // ------------------------------------------------------------------ HTTP

    private static void handle(HttpExchange ex) {
        try {
            if (!token.equals(ex.getRequestHeaders().getFirst("X-Token"))) {
                reply(ex, 403, "{\"error\":\"forbidden\"}");
                return;
            }
            String body = new String(readAll(ex.getRequestBody()), StandardCharsets.UTF_8);
            Map<String, Object> req = Json.obj(Json.parse(body));
            String tool = (String) req.get("tool");
            Map<String, Object> args = Json.obj(req.get("arguments"));
            if (args == null) args = new LinkedHashMap<>();
            Map<String, Object> res = new LinkedHashMap<>();
            try {
                res.put("text", Tools.run(tool, args));
                res.put("isError", false);
            } catch (ToolException te) {
                res.put("text", te.getMessage());
                res.put("isError", true);
            } catch (Throwable t) {
                Throwable c = t instanceof InvocationTargetException && t.getCause() != null ? t.getCause() : t;
                res.put("text", c.getClass().getSimpleName() + ": " + c.getMessage());
                res.put("isError", true);
            }
            reply(ex, 200, Json.write(res));
        } catch (Throwable t) {
            try { reply(ex, 500, "{\"error\":\"" + t + "\"}"); } catch (Exception ignored) { }
        }
    }

    private static void reply(HttpExchange ex, int code, String json) throws Exception {
        byte[] b = json.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().add("Content-Type", "application/json; charset=utf-8");
        ex.sendResponseHeaders(code, b.length);
        try (OutputStream os = ex.getResponseBody()) { os.write(b); }
    }

    private static byte[] readAll(InputStream in) throws Exception {
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        byte[] buf = new byte[4096];
        int n;
        while ((n = in.read(buf)) > 0) o.write(buf, 0, n);
        return o.toByteArray();
    }

    // ------------------------------------------------------------------ tools

    static final class ToolException extends RuntimeException {
        ToolException(String m) { super(m); }
    }

    /** One live emulator session (reflective handles). */
    static final class Sess {
        String name, host, title;
        Object ecl, ps, oia;
    }

    static final class Tools {
        static String run(String tool, Map<String, Object> a) throws Exception {
            if (tool == null) throw new ToolException("Missing tool name.");
            if (tool.equals("debug_components")) return debugComponents();
            List<Sess> all = findSessions();
            if (tool.equals("list_sessions")) {
                if (all.isEmpty()) return "No emulator sessions are open.";
                StringBuilder sb = new StringBuilder();
                for (Sess s : all) {
                    sb.append(s.name).append(": host=").append(s.host).append(" window=\"").append(s.title)
                      .append("\" connected=").append(R.call(s.ecl, "isConnected")).append(" keyboard=").append(inhibit(s)).append('\n');
                }
                return sb.toString().trim();
            }
            Sess s = pick(all, str(a, "session"));
            int timeout = num(a, "timeout_ms", 10000);
            switch (tool) {
                case "get_screen":
                    return format(s, bool(a, "include_protected", false));
                case "fill_fields": {
                    requireReady(s);
                    List<Object> fields = Json.arr(a.get("fields"));
                    if (fields == null) throw new ToolException("Missing argument 'fields'.");
                    List<Object> fl = fieldList(s);
                    for (Object o : fields) {
                        Map<String, Object> m = Json.obj(o);
                        int idx = ((Number) m.get("index")).intValue();
                        String text = (String) m.get("text");
                        if (idx < 1 || idx > fl.size())
                            throw new ToolException("Field index " + idx + " out of range (1.." + fl.size() + "). Call get_screen again; the screen may have changed.");
                        Object f = fl.get(idx - 1);
                        if ((Boolean) R.call(f, "IsProtected")) throw new ToolException("Field " + idx + " is protected (read-only).");
                        int len = ((Number) R.call(f, "GetLength")).intValue();
                        if (text.length() > len)
                            throw new ToolException("Text of " + text.length() + " chars does not fit field " + idx + " (length " + len + ").");
                        R.call(f, "SetText", text);
                    }
                    String key = str(a, "key");
                    if (key != null && !key.isEmpty()) sendKeys(s, key, timeout);
                    return format(s, false);
                }
                case "send_keys": {
                    requireReady(s);
                    String keys = str(a, "keys");
                    if (keys == null) throw new ToolException("Missing argument 'keys'.");
                    sendKeys(s, keys, timeout);
                    return format(s, false);
                }
                case "type_text": {
                    requireReady(s);
                    String text = str(a, "text");
                    if (text == null) throw new ToolException("Missing argument 'text'.");
                    int row = num(a, "row", ((Number) R.call(s.ps, "GetCursorRow")).intValue());
                    int col = num(a, "col", ((Number) R.call(s.ps, "GetCursorCol")).intValue());
                    R.call(s.ps, "SetString", text, row, col);
                    return format(s, false);
                }
                case "set_cursor":
                    R.call(s.ps, "SetCursorPos", num(a, "row", 1), num(a, "col", 1));
                    return format(s, false);
                case "wait_for_text": {
                    String text = str(a, "text");
                    if (text == null) throw new ToolException("Missing argument 'text'.");
                    long end = System.currentTimeMillis() + timeout;
                    boolean found = false;
                    while (true) {
                        for (String l : lines(s)) if (l.toLowerCase().contains(text.toLowerCase())) { found = true; break; }
                        if (found || System.currentTimeMillis() >= end) break;
                        Thread.sleep(150);
                    }
                    return (found ? "FOUND\n" : "NOT FOUND (timeout)\n") + format(s, false);
                }
                default:
                    throw new ToolException("Unknown tool: " + tool);
            }
        }

        static String str(Map<String, Object> a, String k) { Object v = a.get(k); return v instanceof String ? (String) v : null; }
        static int num(Map<String, Object> a, String k, int d) { Object v = a.get(k); return v instanceof Number ? ((Number) v).intValue() : d; }
        static boolean bool(Map<String, Object> a, String k, boolean d) { Object v = a.get(k); return v instanceof Boolean ? (Boolean) v : d; }

        static Sess pick(List<Sess> all, String name) {
            if (all.isEmpty()) throw new ToolException("No emulator session found. Start a 5250 session in IBM i Access Client Solutions first.");
            StringBuilder names = new StringBuilder();
            for (Sess s : all) names.append(names.length() > 0 ? ", " : "").append(s.name);
            if (name != null && !name.isEmpty()) {
                for (Sess s : all) if (s.name.equalsIgnoreCase(name)) return s;
                throw new ToolException("Session '" + name + "' not found. Available: " + names);
            }
            if (all.size() > 1) throw new ToolException("Several sessions are open (" + names + "); pass the 'session' argument.");
            return all.get(0);
        }

        static String inhibit(Sess s) {
            try {
                int v = ((Number) R.call(s.oia, "InputInhibited")).intValue();
                return v == 0 ? "free" : "inhibited(" + v + ")";
            } catch (Exception e) { return "unknown"; }
        }

        static void requireReady(Sess s) throws Exception {
            boolean ok = (Boolean) R.call(s.oia, "WaitForInput", 3000L);
            if (!ok) throw new ToolException("Keyboard is locked (input inhibited), cannot type now: " + inhibit(s));
        }

        static void sendKeys(Sess s, String keys, int timeoutMs) throws Exception {
            R.call(s.ps, "SendKeys", keys);
            Thread.sleep(150); // let the keyboard lock before polling the OIA
            R.call(s.oia, "WaitForInput", (long) timeoutMs);
            Thread.sleep(60);
        }

        static List<String> lines(Sess s) throws Exception {
            int rows = ((Number) R.call(s.ps, "GetRows")).intValue();
            int cols = ((Number) R.call(s.ps, "GetCols")).intValue();
            char[] buf = new char[rows * cols];
            R.call(s.ps, "GetScreen", buf, buf.length, 1 /* text plane */);
            List<String> out = new ArrayList<>();
            for (int r = 0; r < rows; r++) {
                StringBuilder b = new StringBuilder(cols);
                for (int c = 0; c < cols; c++) {
                    char ch = buf[r * cols + c];
                    b.append(ch < 32 ? ' ' : ch);
                }
                out.add(b.toString().replaceAll("\\s+$", ""));
            }
            return out;
        }

        @SuppressWarnings("unchecked")
        static List<Object> fieldList(Sess s) throws Exception {
            Object fl = R.call(s.ps, "GetFieldList");
            R.call(fl, "Refresh");
            return new ArrayList<>((java.util.Collection<Object>) fl); // ECLFieldList extends Vector
        }

        static String format(Sess s, boolean includeProtected) throws Exception {
            int rows = ((Number) R.call(s.ps, "GetRows")).intValue();
            int cols = ((Number) R.call(s.ps, "GetCols")).intValue();
            StringBuilder sb = new StringBuilder();
            sb.append("Session ").append(s.name).append(" | ").append(rows).append('x').append(cols)
              .append(" | cursor row ").append(R.call(s.ps, "GetCursorRow")).append(" col ").append(R.call(s.ps, "GetCursorCol"))
              .append(" | keyboard ").append(inhibit(s)).append('\n');
            sb.append("--- screen (row| text) ---\n");
            List<String> ls = lines(s);
            for (int r = 0; r < ls.size(); r++) sb.append(String.format("%2d| %s\n", r + 1, ls.get(r)));
            sb.append("--- input fields (index, position, length) ---\n");
            List<Object> fl = fieldList(s);
            int shown = 0;
            for (int i = 0; i < fl.size(); i++) {
                Object f = fl.get(i);
                boolean prot = (Boolean) R.call(f, "IsProtected");
                if (prot && !includeProtected) continue;
                shown++;
                boolean hidden = !(Boolean) R.call(f, "IsDisplay");
                List<String> flags = new ArrayList<>();
                if (prot) flags.add("protected");
                if ((Boolean) R.call(f, "IsNumeric")) flags.add("numeric");
                if (hidden) flags.add("hidden/password");
                if ((Boolean) R.call(f, "IsHighIntensity")) flags.add("highlighted");
                if ((Boolean) R.call(f, "IsModified")) flags.add("modified");
                String value = "";
                if (!hidden) {
                    Object v = R.call(f, "getString");
                    value = v == null ? "" : v.toString().replace('\0', ' ').replaceAll("\\s+$", "");
                }
                int sr = ((Number) R.call(f, "GetStartRow")).intValue(), er = ((Number) R.call(f, "GetEndRow")).intValue();
                sb.append('#').append(i + 1).append(" row ").append(sr).append(" col ").append(R.call(f, "GetStartCol"));
                if (er != sr) sb.append(" -> row ").append(er).append(" col ").append(R.call(f, "GetEndCol"));
                sb.append(" len ").append(R.call(f, "GetLength"));
                if (!flags.isEmpty()) sb.append(" [").append(String.join(",", flags)).append(']');
                sb.append(" value=\"").append(value).append("\"\n");
            }
            if (shown == 0) sb.append("(none)\n");
            return sb.toString();
        }
    }

    // ------------------------------------------------------------------ session discovery

    static List<Sess> findSessions() {
        List<Sess> out = new ArrayList<>();
        Set<Object> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        for (Window w : Window.getWindows()) walk(w, titleOf(w), out, seen);
        for (int i = 0; i < out.size(); i++) {
            Sess s = out.get(i);
            if (s.name == null || s.name.isEmpty()) s.name = String.valueOf((char) ('A' + i));
        }
        return out;
    }

    private static String titleOf(Window w) {
        try { Object t = R.call(w, "getTitle"); return t == null ? "" : t.toString(); } catch (Exception e) { return ""; }
    }

    private static void walk(Component c, String title, List<Sess> out, Set<Object> seen) {
        try {
            Method m = R.find(c.getClass(), "getECLSession", 0);
            if (m != null) {
                Object ecl = m.invoke(c);
                if (ecl != null && seen.add(ecl)) {
                    Sess s = new Sess();
                    s.ecl = ecl;
                    s.ps = R.call(ecl, "GetPS");
                    s.oia = R.call(ecl, "GetOIA");
                    s.title = title;
                    Object n = R.call(ecl, "GetName");
                    s.name = n == null ? null : n.toString();
                    Object h = R.call(ecl, "GetHost");
                    s.host = h == null ? "" : h.toString();
                    if (s.ps != null && s.oia != null) out.add(s);
                }
            }
        } catch (Exception ignored) { }
        if (c instanceof Container) for (Component k : ((Container) c).getComponents()) walk(k, title, out, seen);
    }

    static String debugComponents() {
        StringBuilder sb = new StringBuilder();
        for (Window w : Window.getWindows()) dump(w, 0, sb);
        return sb.toString();
    }

    private static void dump(Component c, int depth, StringBuilder sb) {
        String cn = c.getClass().getName();
        if (cn.contains("eNetwork") || depth < 2) {
            for (int i = 0; i < depth; i++) sb.append("  ");
            sb.append(cn).append(R.find(c.getClass(), "getECLSession", 0) != null ? "  [getECLSession]" : "").append('\n');
        }
        if (c instanceof Container) for (Component k : ((Container) c).getComponents()) dump(k, depth + 1, sb);
    }

    // ------------------------------------------------------------------ reflection helpers

    static final class R {
        static Method find(Class<?> cls, String name, int argc) {
            for (Method m : cls.getMethods()) if (m.getName().equals(name) && m.getParameterCount() == argc) return m;
            return null;
        }

        static Object call(Object target, String name, Object... args) throws Exception {
            Method best = null;
            for (Method m : target.getClass().getMethods()) {
                if (!m.getName().equals(name) || m.getParameterCount() != args.length) continue;
                Class<?>[] p = m.getParameterTypes();
                boolean ok = true;
                for (int i = 0; i < p.length && ok; i++) ok = accepts(p[i], args[i]);
                if (ok) { best = m; break; }
            }
            if (best == null) throw new NoSuchMethodException(target.getClass().getName() + "." + name + "/" + args.length);
            try {
                return best.invoke(target, args);
            } catch (InvocationTargetException e) {
                Throwable c = e.getCause() == null ? e : e.getCause();
                if (c instanceof Exception) throw (Exception) c;
                throw e;
            }
        }

        private static boolean accepts(Class<?> p, Object a) {
            if (a == null) return !p.isPrimitive();
            if (p.isInstance(a)) return true;
            if (p == int.class) return a instanceof Integer;
            if (p == long.class) return a instanceof Long || a instanceof Integer;
            if (p == boolean.class) return a instanceof Boolean;
            if (p == char.class) return a instanceof Character;
            return false;
        }
    }
}
