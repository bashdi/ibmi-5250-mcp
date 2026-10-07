# ibmi-5250-mcp

A local [MCP](https://modelcontextprotocol.io) server that lets an AI agent (e.g. Claude) see and operate a **5250 session that is already open in IBM i Access Client Solutions (ACS)**. The agent receives the current screen as text together with its input fields, can type into those fields, and can press keys such as Enter or F3.

## Architecture

```
AI client  --MCP over stdio-->  ibmi-5250-mcp.jar  --HTTP, 127.0.0.1 only-->  agent inside the ACS JVM
(Claude Code)                   (its own Java process)                         (reads and drives the session)
```

There are two processes and three small pieces of code:

| Piece | Runs in | Job |
|---|---|---|
| `McpServer` | its own JVM, started by the AI client | speaks MCP (JSON-RPC over stdin/stdout), finds ACS, loads the agent, forwards tool calls |
| `Boot` | the ACS JVM | tiny, stable bootstrap that loads and replaces the real agent |
| `AgentImpl` | the ACS JVM (own class loader) | finds the emulator sessions and executes the tools |

The MCP server contains no emulator logic at all. Everything that touches the terminal happens in the agent.

## How the agent works

### Why an agent at all?

ACS draws its terminal with Java Swing. There is no API, no accessible text, and no socket you could attach to from outside. But ACS is a normal Java program (`acsbundle.jar`), and it contains IBM's complete HOD emulator: `ECLSession`, `ECLPS` (presentation space), `ECLField`, `ECLOIA` (operator information area). Those objects hold the real screen buffer and know how to send keys. The only way to reach them is to run code **inside the ACS process**. A Java agent does exactly that.

### Loading the agent (Attach API)

1. The MCP server looks for a running JVM whose command line contains `acsbundle.jar` (`VirtualMachine.list()`).
2. It copies its own jar to a versioned file in the per-user state directory (`agent-<size>-<timestamp>.jar`).
3. It calls `VirtualMachine.attach(pid)` and `loadAgent(copy)`. The JVM then appends that jar to the target's class path and calls `Boot.agentmain(...)`. Nothing on disk in the ACS installation is touched, and nothing persists: when ACS exits, the agent is gone.

The copy is used (instead of the jar in `bin/`) so that ACS never keeps your build output locked, which matters on Windows.

### Bootstrap and hot replacement

`Boot` is deliberately tiny and never changes. It creates a fresh `URLClassLoader` on the jar copy, loads `AgentImpl` from it and calls `start`. If an older `AgentImpl` is already running, it is stopped first. A rebuilt jar therefore replaces the running agent **without restarting ACS**; the server compares the version in the agent's info file with its own and re-attaches when they differ.

### Finding the sessions

`AgentImpl` walks the AWT component tree (`Window.getWindows()` and all children). Any component that offers `getECLSession()` yields a live session object. Its name (`A`, `B`, ...), host and window title are used to build the `list_sessions` output and to pick the target for later calls.

All access to HOD classes uses reflection. The agent has no compile-time dependency on ACS, so it is not tied to an ACS version, and nothing in ACS has to be patched.

### What it does with a session

| Need | HOD call (via reflection) |
|---|---|
| Screen text | `ECLPS.GetScreen(char[], len, textPlane)` |
| Input fields | `ECLPS.GetFieldList()` → `ECLField` (start/end row and column, length, protected, numeric, display, modified) |
| Cursor | `ECLPS.GetCursorRow/Col`, `SetCursorPos` |
| Fill a field | `ECLField.SetText` (replaces the field content) |
| Press keys | `ECLPS.SendKeys("[enter]")` and similar mnemonics |
| "Is the host done?" | `ECLOIA.InputInhibited()` / `WaitForInput(timeout)` after every key |

After each action the agent waits until the keyboard is free again and then returns the new screen, so the AI always sees the result of what it just did.

### Talking to the MCP server

On start the agent opens an HTTP server on `127.0.0.1` with a random port and a random token, and writes both into `agent-<pid>.json` in the state directory. The MCP server reads that file and POSTs `{tool, arguments}` with the token in an `X-Token` header. On the next start it finds the running agent through the same file and does not attach again.

### What the AI gets back

```
Session A | 24x80 | cursor row 20 col 7 | keyboard free
--- screen (row| text) ---
 1|  MAIN                           IBM i Main Menu
 ...
20|  ===>
--- input fields (index, position, length) ---
#42 row 20 col 7 -> row 21 col 79 len 153 value=""
```

Fields are addressed by their **index** (`fill_fields`). The index is only valid for the current screen, so call `get_screen` again after every screen change.

## Safety

- No ACS file, class or setting is modified; the agent lives in memory only.
- The agent listens on `127.0.0.1` only and requires a random per-run token. The info file sits in a per-user directory (mode `700` on macOS/Linux).
- Hidden (password) fields are never read or returned.
- The AI performs real actions on the host with **your** authority in the open session. Treat it like handing someone your keyboard.

## Tools

| Tool | Purpose |
|---|---|
| `list_sessions` | open ACS sessions (A, B, ...) |
| `get_screen` | screen text, cursor, keyboard state, input fields |
| `fill_fields` | fill fields by index, optionally press a key afterwards (`[enter]`) |
| `send_keys` | send keys: `[enter] [pf3] [tab] [pagedown] ...` |
| `type_text` | type text at a position or the cursor |
| `set_cursor` | move the cursor |
| `wait_for_text` | wait until text appears on the screen |

Rows and columns are 1-based.

### Confirmation (`confirmed`)

`fill_fields`, `send_keys` and `type_text` can execute commands on the host, so each has a required boolean parameter `confirmed`. If it is `false` or missing, **nothing is done**: the call returns an error saying so and never reaches the session. The tool descriptions instruct the AI to analyze the command first, and, if it deletes, overwrites or modifies data, to warn the user, obtain confirmation, and only then call the tool with `confirmed=true`. The check is enforced twice, in the MCP server and again in the agent. Read-only tools (`list_sessions`, `get_screen`, `wait_for_text`) and `set_cursor` need no confirmation.

Note that the flag is a guard rail for the AI's behavior: the model sets it, so it is only as reliable as the model following its instructions. For hard protection, also use a restricted IBM i user profile or your MCP client's per-tool approval prompts.

## Platform support

The code is plain Java (no native code, no Windows APIs), so it is designed to run anywhere ACS runs:

| | Windows | macOS | Linux |
|---|---|---|---|
| State directory | `%LOCALAPPDATA%\iaccess-mcp` | `~/Library/Application Support/iaccess-mcp` | `$XDG_STATE_HOME` or `~/.local/state/iaccess-mcp` |
| Build script | `java\build.cmd` | `java/build.sh` | `java/build.sh` |

**Status:** tested on Windows 11 with a 2020 ACS build (`acsbundle.jar`) and JDK 17. macOS and Linux are expected to work but have **not been tested yet**. Things to check there:

- Run the MCP server with a **JDK** (not just a JRE); the Attach API is the `jdk.attach` module.
- The MCP server must run as the **same user** as ACS (a Java Attach requirement on all platforms).
- ACS must run on a JVM that allows attaching (not started with `-XX:+DisableAttachMechanism`).
- On Linux, sandboxed ACS installs (Snap/Flatpak) use a private `/tmp`, which breaks Attach. Use a normal install.
- If several ACS instances run, set `IACCESS_PID=<pid>` to choose one.

## Requirements

- ACS with an open 5250 session
- JDK 11 or newer to build and run the MCP server

## Build and register

Windows:

```
java\build.cmd
```

macOS / Linux:

```
java/build.sh
```

Result: `java/bin/ibmi-5250-mcp.jar`. Register it in Claude Code:

```
claude mcp add ibmi-5250 -- java -jar /path/to/iaccess_mcp/java/bin/ibmi-5250-mcp.jar
```

or in `.mcp.json`:

```json
{ "mcpServers": { "ibmi-5250": { "command": "java", "args": ["-jar", "/path/to/iaccess_mcp/java/bin/ibmi-5250-mcp.jar"] } } }
```

## Project layout

```
java/
  build.cmd / build.sh      build bin/ibmi-5250-mcp.jar
  src/iaccess/
    McpServer.java          MCP over stdio, attach, forwarding, tool definitions
    Boot.java               tiny bootstrap inside ACS; loads AgentImpl in its own class loader
    AgentImpl.java          the agent: HTTP endpoint, session lookup, tool logic (reflection on the ECL API)
    Dirs.java               per-user state directory for Windows / macOS / Linux
    Json.java               minimal JSON parser/writer
```
