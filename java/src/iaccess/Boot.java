package iaccess;

import java.lang.instrument.Instrumentation;
import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Tiny, stable bootstrap loaded by the Attach API into the ACS JVM. It keeps no emulator logic:
 * it loads {@link AgentImpl} from the given jar copy in its own class loader and starts it,
 * stopping any previously started version first. That way a newer jar can replace the agent
 * without restarting ACS. Must not reference other classes of this package.
 */
public final class Boot {
    private static Object impl; // AgentImpl class of the running version
    private static Method stop;

    public static synchronized void agentmain(String args, Instrumentation inst) {
        try {
            Path jar = Paths.get(args);
            if (stop != null) {
                try { stop.invoke(null); } catch (Throwable ignored) { }
                stop = null;
            }
            URLClassLoader cl = new URLClassLoader(new URL[] { jar.toUri().toURL() }, ClassLoader.getPlatformClassLoader());
            Class<?> c = cl.loadClass("iaccess.AgentImpl");
            c.getMethod("start", String.class).invoke(null, jar.getFileName().toString());
            stop = c.getMethod("stop");
            impl = c;
        } catch (Throwable t) {
            System.err.println("iaccess-mcp agent failed to start: " + t);
            t.printStackTrace();
        }
    }
}
