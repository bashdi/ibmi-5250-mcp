package iaccess;

import com.sun.tools.attach.VirtualMachine;
import com.sun.tools.attach.VirtualMachineDescriptor;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.PrintStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * MCP server (stdio). Loads the agent into the running ACS JVM on first use and
 * forwards tool calls to it over the loopback port the agent opened.
 */
public final class McpServer {
    private static PrintStream out;
    private static Path jarPath;
    private static int agentPort;
    private static String agentToken;

    public static void main(String[] args) throws Exception {
        out = new PrintStream(System.out, true, "UTF-8");
        jarPath = Paths.get(McpServer.class.getProtectionDomain().getCodeSource().getLocation().toURI());
        System.err.println("iaccess-mcp: started (stdio)");
        BufferedReader in = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
        String line;
        while ((line = in.readLine()) != null) {
            if (line.trim().isEmpty()) continue;
            Map<String, Object> reply;
            try {
                reply = handle(Json.obj(Json.parse(line)));
            } catch (Exception e) {
                System.err.println("iaccess-mcp: bad message: " + e);
                reply = error(null, -32700, "Parse error");
            }
            if (reply != null) { out.print(Json.write(reply) + "\n"); out.flush(); }
        }
    }

    private static Map<String, Object> handle(Map<String, Object> msg) {
        Object id = msg.get("id");
        String method = (String) msg.get("method");
        if (method == null || id == null) return null; // notifications / responses
        Map<String, Object> p = Json.obj(msg.get("params"));
        try {
            switch (method) {
                case "initialize": {
                    Map<String, Object> r = new LinkedHashMap<>();
                    Object pv = p == null ? null : p.get("protocolVersion");
                    r.put("protocolVersion", pv instanceof String ? pv : "2025-03-26");
                    r.put("capabilities", map("tools", new LinkedHashMap<>()));
                    r.put("serverInfo", map("name", "iaccess-mcp", "version", "0.2.0"));
                    r.put("instructions", "Controls a running IBM i Access Client Solutions 5250 emulator session. "
                        + "Call get_screen first to see the screen and its input fields, then fill_fields / send_keys. "
                        + "Never invent passwords; ask the user for credentials.");
                    return result(id, r);
                }
                case "ping":
                    return result(id, new LinkedHashMap<>());
                case "tools/list":
                    return result(id, map("tools", Defs.tools()));
                case "tools/call":
                    return result(id, callTool(p));
                default:
                    return error(id, -32601, "Method not found: " + method);
            }
        } catch (Exception e) {
            return error(id, -32603, String.valueOf(e.getMessage()));
        }
    }

    private static Map<String, Object> callTool(Map<String, Object> p) {
        String name = (String) p.get("name");
        Map<String, Object> args = Json.obj(p.get("arguments"));
        if (args == null) args = new LinkedHashMap<>();
        if (Defs.EXECUTING.contains(name) && !Boolean.TRUE.equals(args.get("confirmed")))
            return text(Defs.NOT_CONFIRMED, true);
        try {
            Map<String, Object> req = new LinkedHashMap<>();
            req.put("tool", name);
            req.put("arguments", args);
            Map<String, Object> r = post(Json.write(req));
            return text((String) r.get("text"), Boolean.TRUE.equals(r.get("isError")));
        } catch (Exception e) {
            System.err.println("iaccess-mcp: tool failure: " + e);
            return text(String.valueOf(e.getMessage()), true);
        }
    }

    // ---------------------------------------------------------------- agent connection

    private static Map<String, Object> post(String body) throws Exception {
        for (int attempt = 0; attempt < 2; attempt++) {
            if (agentToken == null) connect();
            try {
                HttpURLConnection c = (HttpURLConnection) new URL("http://127.0.0.1:" + agentPort + "/call").openConnection();
                c.setRequestMethod("POST");
                c.setDoOutput(true);
                c.setConnectTimeout(3000);
                c.setReadTimeout(120000);
                c.setRequestProperty("X-Token", agentToken);
                c.setRequestProperty("Content-Type", "application/json; charset=utf-8");
                try (OutputStream os = c.getOutputStream()) { os.write(body.getBytes(StandardCharsets.UTF_8)); }
                if (c.getResponseCode() != 200) throw new java.io.IOException("agent HTTP " + c.getResponseCode());
                String resp = new String(c.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
                return Json.obj(Json.parse(resp));
            } catch (java.net.ConnectException | java.io.FileNotFoundException e) {
                agentToken = null; // ACS restarted or agent gone: attach again
                if (attempt == 1) throw e;
            }
        }
        throw new IllegalStateException("unreachable");
    }

    /** Copy of this jar that the ACS JVM loads, so the jar in bin\ is never locked by ACS. */
    private static Path agentCopy() throws Exception {
        Path dir = Dirs.ensure();
        String ver = Long.toHexString(Files.size(jarPath)) + "-" + Long.toHexString(Files.getLastModifiedTime(jarPath).toMillis());
        Path copy = dir.resolve("agent-" + ver + ".jar");
        if (!Files.exists(copy)) Files.copy(jarPath, copy);
        try (java.nio.file.DirectoryStream<Path> old = Files.newDirectoryStream(dir, "agent-*.jar")) {
            for (Path o : old) {
                if (!o.equals(copy)) {
                    try { Files.deleteIfExists(o); } catch (Exception inUse) { /* still loaded by ACS; next time */ }
                }
            }
        }
        return copy;
    }

    private static long findAcsPid() {
        String forced = System.getenv("IACCESS_PID");
        if (forced != null && !forced.isEmpty()) return Long.parseLong(forced.trim());
        List<VirtualMachineDescriptor> hits = new ArrayList<>();
        for (VirtualMachineDescriptor d : VirtualMachine.list()) {
            String n = d.displayName().toLowerCase();
            if (n.contains("acsbundle") && !d.id().equals(String.valueOf(ProcessHandle.current().pid()))) hits.add(d);
        }
        if (hits.isEmpty())
            throw new IllegalStateException("IBM i Access Client Solutions is not running (no JVM with acsbundle.jar found). Start ACS and open a 5250 session.");
        return Long.parseLong(hits.get(0).id());
    }

    private static String agentVersion;

    private static boolean readInfo(long pid) {
        try {
            Path f = Dirs.infoDir().resolve("agent-" + pid + ".json");
            if (!Files.exists(f)) return false;
            Map<String, Object> info = Json.obj(Json.parse(new String(Files.readAllBytes(f), StandardCharsets.UTF_8)));
            agentPort = ((Number) info.get("port")).intValue();
            agentToken = (String) info.get("token");
            agentVersion = (String) info.get("version");
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private static synchronized void connect() throws Exception {
        long pid = findAcsPid();
        Path copy = agentCopy();
        if (readInfo(pid) && copy.getFileName().toString().equals(agentVersion) && alive()) return;
        Files.deleteIfExists(Dirs.infoDir().resolve("agent-" + pid + ".json"));
        agentToken = null;
        System.err.println("iaccess-mcp: attaching agent to ACS JVM " + pid);
        VirtualMachine vm = VirtualMachine.attach(String.valueOf(pid));
        try {
            vm.loadAgent(copy.toString(), copy.toString());
        } finally {
            vm.detach();
        }
        for (int i = 0; i < 50; i++) {
            if (readInfo(pid)) return;
            Thread.sleep(200);
        }
        throw new IllegalStateException("Agent was loaded but did not report its port.");
    }

    private static boolean alive() {
        try {
            HttpURLConnection c = (HttpURLConnection) new URL("http://127.0.0.1:" + agentPort + "/call").openConnection();
            c.setConnectTimeout(1000);
            c.setReadTimeout(2000);
            c.setRequestMethod("POST");
            c.setRequestProperty("X-Token", agentToken);
            c.setDoOutput(true);
            c.getOutputStream().write("{\"tool\":\"ping\"}".getBytes(StandardCharsets.UTF_8));
            return c.getResponseCode() == 200;
        } catch (Exception e) {
            return false;
        }
    }

    // ---------------------------------------------------------------- json-rpc helpers

    @SuppressWarnings("unchecked")
    static Map<String, Object> map(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) m.put((String) kv[i], kv[i + 1]);
        return m;
    }

    private static Map<String, Object> text(String t, boolean isError) {
        List<Object> c = new ArrayList<>();
        c.add(map("type", "text", "text", t));
        return map("content", c, "isError", isError);
    }

    private static Map<String, Object> result(Object id, Object r) { return map("jsonrpc", "2.0", "id", id, "result", r); }

    private static Map<String, Object> error(Object id, int code, String m) {
        return map("jsonrpc", "2.0", "id", id, "error", map("code", code, "message", m));
    }

    /** Tool definitions advertised to the MCP client. */
    static final class Defs {
        static final String KEYS = "Emulator key mnemonics in brackets, e.g. [enter] [tab] [backtab] [pf1]..[pf24] [pa1] [pagedn] [pageup] [clear] [reset] [attn] [sysreq] [fldext] [home] [eraseeof] [up] [down] [left] [right] ([pagedown] and [fieldexit] are accepted as aliases). For scrolling prefer the page_down / page_up tools. Plain characters are typed as-is.";

        /** Tools that type into the session or press keys, i.e. can execute commands on the host. */
        static final java.util.Set<String> EXECUTING = new java.util.HashSet<>(java.util.Arrays.asList("fill_fields", "send_keys", "type_text"));

        static final String NOT_CONFIRMED = "NOT EXECUTED: 'confirmed' is not true, so nothing was done in the session. "
            + "Analyze the command first. If it deletes, overwrites or modifies data, warn the user, obtain their confirmation, "
            + "and only then call this tool again with confirmed=true.";

        static final String CONFIRM_RULE = " Analyze the command before execution. If it deletes, overwrites or modifies data, "
            + "you must first warn the user, obtain confirmation, and only then call the tool with confirmed=True. "
            + "If confirmed is false, nothing is done.";

        static Map<String, Object> p(String type, String desc) { return map("type", type, "description", desc); }

        static Map<String, Object> schema(Map<String, Object> props, String... required) {
            Map<String, Object> o = map("type", "object", "properties", props);
            if (required.length > 0) { List<Object> r = new ArrayList<>(); for (String s : required) r.add(s); o.put("required", r); }
            return o;
        }

        static Map<String, Object> tool(String name, String desc, Map<String, Object> schema) {
            return map("name", name, "description", desc, "inputSchema", schema);
        }

        static List<Object> tools() {
            Map<String, Object> session = p("string", "Session name (e.g. \"A\"). Optional if exactly one session is open.");
            Map<String, Object> timeout = p("integer", "Max time in ms to wait for the host. Default 10000.");
            Map<String, Object> confirmed = p("boolean", "Must be true to execute. If false or missing, nothing is done. Set to true only after the command was analyzed and, for anything that deletes, overwrites or modifies data, the user has explicitly confirmed it.");
            Map<String, Object> fieldItem = schema(map("index", p("integer", "Field index from get_screen"), "text", p("string", "Text to enter (replaces the field content)")), "index", "text");
            List<Object> l = new ArrayList<>();
            l.add(tool("list_sessions", "List the open emulator sessions.", schema(map())));
            l.add(tool("get_screen", "Return the current 5250 screen as text (rows), the cursor position, keyboard state and all input fields with index, row/col and length. Rows and columns are 1-based. Use the field index with fill_fields.",
                schema(map("session", session, "include_protected", p("boolean", "Also list protected (read-only) fields. Default false.")))));
            l.add(tool("fill_fields", "Write text into input fields (by index from get_screen) and optionally press a key afterwards (e.g. \"[enter]\"). Returns the resulting screen. Each field's content is replaced. Hidden/password fields are never echoed back." + CONFIRM_RULE,
                schema(map("session", session,
                    "fields", map("type", "array", "description", "Fields to fill.", "items", fieldItem),
                    "key", p("string", "Optional key pressed after filling, e.g. [enter] or [pf4]. " + KEYS),
                    "timeout_ms", timeout, "confirmed", confirmed), "fields", "confirmed")));
            l.add(tool("send_keys", "Press keys (AID/function keys, tab, etc.) and return the resulting screen. " + KEYS + CONFIRM_RULE,
                schema(map("session", session, "keys", p("string", "Key sequence"), "timeout_ms", timeout, "confirmed", confirmed), "keys", "confirmed")));
            l.add(tool("page_down", "Scroll the green screen forward (Page Down / Roll Up) and return the new screen. Pure navigation, no confirmation needed. Use this to see further list entries, subfile pages or long output.",
                schema(map("session", session, "times", p("integer", "How many pages to scroll (1-20). Default 1. Only the last screen is returned."), "timeout_ms", timeout))));
            l.add(tool("page_up", "Scroll the green screen backward (Page Up / Roll Down) and return the new screen. Pure navigation, no confirmation needed.",
                schema(map("session", session, "times", p("integer", "How many pages to scroll (1-20). Default 1. Only the last screen is returned."), "timeout_ms", timeout))));
            l.add(tool("type_text", "Type literal text at a screen position (or at the cursor if row/col omitted) without pressing any key. Prefer fill_fields." + CONFIRM_RULE,
                schema(map("session", session, "text", p("string", "Text"), "row", p("integer", "1-based row"), "col", p("integer", "1-based column"), "confirmed", confirmed), "text", "confirmed")));
            l.add(tool("set_cursor", "Move the cursor to a position and return the screen.",
                schema(map("session", session, "row", p("integer", "1-based row"), "col", p("integer", "1-based column")), "row", "col")));
            l.add(tool("wait_for_text", "Wait until the given text appears anywhere on the screen (case-insensitive). Returns the screen either way and says whether it was found.",
                schema(map("session", session, "text", p("string", "Text to wait for"), "timeout_ms", timeout), "text")));
            return l;
        }
    }
}
