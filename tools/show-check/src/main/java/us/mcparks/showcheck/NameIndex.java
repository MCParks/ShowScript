package us.mcparks.showcheck;

import groovyjarjarasm.asm.ClassReader;
import groovyjarjarasm.asm.ClassVisitor;
import groovyjarjarasm.asm.MethodVisitor;
import groovyjarjarasm.asm.Opcodes;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

/**
 * Every method name declared by any class on the classpath (plus the JDK and the Groovy GDK).
 *
 * Used for the "unknown method" check: a call whose name no class declares can never succeed,
 * whatever the receiver's runtime type is (e.g. Kotlin's minByOrNull called on a Groovy list,
 * issue #52). Classes under a {@code kotlin/} package segment are skipped on purpose: their
 * collection helpers are static extension functions that Groovy never dispatches to.
 */
final class NameIndex {
    private final Set<String> names = ConcurrentHashMap.newKeySet();
    private int classes;

    boolean has(String name) {
        return names.contains(name);
    }

    int size() {
        return names.size();
    }

    int classes() {
        return classes;
    }

    static NameIndex build(List<String> classpath) {
        NameIndex idx = new NameIndex();
        for (String e : classpath) {
            File f = new File(e);
            try {
                if (f.isFile() && e.endsWith(".jar")) idx.scanJar(f);
                else if (f.isDirectory()) idx.scanDir(f.toPath());
            } catch (IOException ex) {
                System.err.println("[show-check] can't index " + e + ": " + ex);
            }
        }
        idx.scanJdk();
        // Groovy MOP / GroovyObject methods every Groovy object answers to
        idx.names.addAll(Arrays.asList("invokeMethod", "getProperty", "setProperty", "getMetaClass", "setMetaClass",
                "methodMissing", "propertyMissing", "call", "doCall"));
        return idx;
    }

    private void scanJar(File f) throws IOException {
        try (JarFile jf = new JarFile(f)) {
            for (Enumeration<JarEntry> en = jf.entries(); en.hasMoreElements(); ) {
                JarEntry je = en.nextElement();
                String n = je.getName();
                if (!wanted(n)) continue;
                try (InputStream in = jf.getInputStream(je)) {
                    read(in);
                } catch (Exception ignored) {
                    // unreadable/odd class file: skip
                }
            }
        }
    }

    private void scanDir(Path root) throws IOException {
        try (java.util.stream.Stream<Path> s = Files.walk(root)) {
            s.filter(p -> wanted(root.relativize(p).toString().replace('\\', '/'))).forEach(p -> {
                try (InputStream in = Files.newInputStream(p)) {
                    read(in);
                } catch (Exception ignored) {
                }
            });
        }
    }

    private void scanJdk() {
        try {
            FileSystem jrt = FileSystems.getFileSystem(URI.create("jrt:/"));
            Path mods = jrt.getPath("/modules");
            try (java.util.stream.Stream<Path> s = Files.walk(mods)) {
                s.filter(p -> p.toString().endsWith(".class") && !p.toString().endsWith("module-info.class")).forEach(p -> {
                    try (InputStream in = Files.newInputStream(p)) {
                        read(in);
                    } catch (Exception ignored) {
                    }
                });
            }
            return;
        } catch (Exception notJava9) {
            // Java 8: rt.jar etc.
        }
        String boot = System.getProperty("sun.boot.class.path");
        if (boot != null) for (String e : boot.split(File.pathSeparator)) {
            File f = new File(e);
            if (f.isFile()) try {
                scanJar(f);
            } catch (IOException ignored) {
            }
        }
    }

    private static boolean wanted(String n) {
        if (!n.endsWith(".class") || n.endsWith("module-info.class") || n.startsWith("META-INF/")) return false;
        return !(n.startsWith("kotlin/") || n.contains("/kotlin/"));
    }

    private void read(InputStream in) throws IOException {
        ClassReader cr = new ClassReader(in);
        classes++;
        cr.accept(new ClassVisitor(Opcodes.ASM9) {
            @Override
            public MethodVisitor visitMethod(int access, String name, String desc, String sig, String[] ex) {
                if ((access & Opcodes.ACC_SYNTHETIC) == 0 && !name.startsWith("<")) names.add(name);
                return null;
            }
        }, ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
    }
}
