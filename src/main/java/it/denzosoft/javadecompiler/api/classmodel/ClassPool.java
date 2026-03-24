/*
 * This project is distributed under the GPLv3 license.
 */
package it.denzosoft.javadecompiler.api.classmodel;

import it.denzosoft.javadecompiler.model.classfile.ClassFile;
import it.denzosoft.javadecompiler.service.deserializer.ClassFileDeserializer;

import java.io.*;
import java.util.*;
import java.util.jar.*;

/**
 * Pool of CtClass objects. Entry point for loading and navigating class files.
 * Similar to javassist.ClassPool.
 *
 * Usage:
 * <pre>
 *   ClassPool pool = new ClassPool();
 *   pool.appendClassPath("target/classes");
 *   pool.appendJarPath("lib/mylib.jar");
 *   CtClass cls = pool.get("com.example.MyClass");
 * </pre>
 */
public class ClassPool {
    private final Map<String, CtClass> cache = new HashMap<String, CtClass>();
    private final Map<String, byte[]> byteCache = new HashMap<String, byte[]>();
    private final List<ClassPathEntry> classPaths = new ArrayList<ClassPathEntry>();
    private final ClassFileDeserializer deserializer = new ClassFileDeserializer();

    public ClassPool() {}

    /**
     * Add a directory to the class search path.
     */
    public void appendClassPath(String dirPath) {
        classPaths.add(new DirectoryClassPath(new File(dirPath)));
    }

    /**
     * Add a JAR file to the class search path.
     */
    public void appendJarPath(String jarPath) throws IOException {
        classPaths.add(new JarClassPath(new File(jarPath)));
    }

    /**
     * Insert a class from raw bytes.
     */
    public void insertClass(String internalName, byte[] bytecode) {
        byteCache.put(normalize(internalName), bytecode);
    }

    /**
     * Get a CtClass by name. Accepts both "com.example.MyClass" and "com/example/MyClass".
     */
    public CtClass get(String className) throws NotFoundException {
        String key = normalize(className);
        CtClass cached = cache.get(key);
        if (cached != null) return cached;

        byte[] data = findBytes(key);
        if (data == null) {
            throw new NotFoundException("Class not found: " + className);
        }

        CtClass cls = parseAndCache(key, data);
        return cls;
    }

    /**
     * Create a CtClass from raw bytes (not cached in pool by default).
     */
    public CtClass makeClass(byte[] bytecode) {
        ClassFile cf = deserializer.deserialize(bytecode);
        String key = cf.getThisClassName();
        CtClass cls = new CtClass(cf, this);
        cls.setOriginalBytes(bytecode);
        cache.put(key, cls);
        return cls;
    }

    /**
     * Create a CtClass from a .class file.
     */
    public CtClass makeClass(File classFile) throws IOException {
        byte[] data = readFile(classFile);
        return makeClass(data);
    }

    /**
     * List all loaded class names (qualified format).
     */
    public Set<String> getLoadedClassNames() {
        Set<String> result = new HashSet<String>();
        for (String key : cache.keySet()) {
            result.add(key.replace('/', '.'));
        }
        return result;
    }

    // Internal

    private String normalize(String className) {
        return className.replace('.', '/');
    }

    private byte[] findBytes(String internalName) {
        // Check byte cache first
        byte[] data = byteCache.get(internalName);
        if (data != null) return data;

        // Search class paths
        for (ClassPathEntry cp : classPaths) {
            data = cp.find(internalName);
            if (data != null) return data;
        }
        return null;
    }

    private CtClass parseAndCache(String internalName, byte[] data) {
        ClassFile cf = deserializer.deserialize(data);
        CtClass cls = new CtClass(cf, this);
        cls.setOriginalBytes(data);
        cache.put(internalName, cls);
        return cls;
    }

    // Class path entries

    private interface ClassPathEntry {
        byte[] find(String internalName);
    }

    private static class DirectoryClassPath implements ClassPathEntry {
        private final File dir;
        DirectoryClassPath(File dir) { this.dir = dir; }

        public byte[] find(String internalName) {
            File f = new File(dir, internalName + ".class");
            if (!f.exists()) return null;
            try {
                return readFile(f);
            } catch (IOException e) {
                return null;
            }
        }
    }

    private static class JarClassPath implements ClassPathEntry {
        private final File jarFile;
        JarClassPath(File jarFile) { this.jarFile = jarFile; }

        public byte[] find(String internalName) {
            JarFile jar = null;
            try {
                jar = new JarFile(jarFile);
                JarEntry entry = jar.getJarEntry(internalName + ".class");
                if (entry == null) return null;
                InputStream is = jar.getInputStream(entry);
                try {
                    return readStream(is);
                } finally {
                    is.close();
                }
            } catch (IOException e) {
                return null;
            } finally {
                if (jar != null) {
                    try { jar.close(); } catch (IOException ignore) {}
                }
            }
        }
    }

    private static byte[] readFile(File f) throws IOException {
        FileInputStream fis = new FileInputStream(f);
        try {
            return readStream(fis);
        } finally {
            fis.close();
        }
    }

    private static byte[] readStream(InputStream is) throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        byte[] buf = new byte[4096];
        int n;
        while ((n = is.read(buf)) != -1) {
            baos.write(buf, 0, n);
        }
        return baos.toByteArray();
    }
}
