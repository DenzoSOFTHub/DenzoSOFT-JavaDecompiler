/*
 * This project is distributed under the GPLv3 license.
 */
package it.denzosoft.javadecompiler.api.classmodel;

import it.denzosoft.javadecompiler.DenzoDecompiler;
import it.denzosoft.javadecompiler.api.loader.Loader;
import it.denzosoft.javadecompiler.api.printer.Printer;
import it.denzosoft.javadecompiler.model.classfile.ClassFile;
import it.denzosoft.javadecompiler.model.classfile.FieldInfo;
import it.denzosoft.javadecompiler.model.classfile.MethodInfo;
import it.denzosoft.javadecompiler.model.classfile.ConstantPool;
import it.denzosoft.javadecompiler.model.classfile.attribute.*;
import it.denzosoft.javadecompiler.util.SignatureParser;
import it.denzosoft.javadecompiler.util.StringConstants;
import it.denzosoft.javadecompiler.util.TypeNameUtil;

import java.util.ArrayList;
import java.util.List;

/**
 * Represents a Java class loaded from a .class file.
 * Similar to javassist.CtClass - provides read-only access to class metadata.
 */
public class CtClass {
    private final ClassFile classFile;
    private final ClassPool pool;

    CtClass(ClassFile classFile, ClassPool pool) {
        this.classFile = classFile;
        this.pool = pool;
    }

    // Identity
    public String getName() { return TypeNameUtil.internalToQualified(classFile.getThisClassName()); }
    public String getInternalName() { return classFile.getThisClassName(); }
    public String getSimpleName() { return TypeNameUtil.simpleNameFromInternal(classFile.getThisClassName()); }
    public String getPackageName() { return TypeNameUtil.packageFromInternal(classFile.getThisClassName()); }

    // Version
    public int getMajorVersion() { return classFile.getMajorVersion(); }
    public int getMinorVersion() { return classFile.getMinorVersion(); }
    public String getJavaVersion() { return StringConstants.javaVersionFromMajor(classFile.getMajorVersion()); }
    public boolean isPreviewFeatures() { return classFile.getMinorVersion() == 0xFFFF; }

    // Access
    public int getModifiers() { return classFile.getAccessFlags(); }
    public boolean isPublic() { return classFile.isPublic(); }
    public boolean isAbstract() { return classFile.isAbstract(); }
    public boolean isFinal() { return classFile.isFinal(); }
    public boolean isInterface() { return classFile.isInterface(); }
    public boolean isEnum() { return classFile.isEnum(); }
    public boolean isAnnotation() { return classFile.isAnnotation(); }
    public boolean isRecord() { return classFile.isRecord(); }
    public boolean isSealed() { return classFile.isSealed(); }
    public boolean isModule() { return classFile.isModule(); }
    public boolean isSynthetic() { return classFile.isSynthetic(); }

    // Hierarchy
    public String getSuperclassName() {
        String s = classFile.getSuperClassName();
        return s != null ? TypeNameUtil.internalToQualified(s) : null;
    }

    public CtClass getSuperclass() throws NotFoundException {
        String s = classFile.getSuperClassName();
        if (s == null) return null;
        return pool.get(s);
    }

    public String[] getInterfaceNames() {
        String[] ifaces = classFile.getInterfaces();
        if (ifaces == null) return new String[0];
        String[] result = new String[ifaces.length];
        for (int i = 0; i < ifaces.length; i++) {
            result[i] = TypeNameUtil.internalToQualified(ifaces[i]);
        }
        return result;
    }

    public CtClass[] getInterfaces() throws NotFoundException {
        String[] ifaces = classFile.getInterfaces();
        if (ifaces == null) return new CtClass[0];
        CtClass[] result = new CtClass[ifaces.length];
        for (int i = 0; i < ifaces.length; i++) {
            result[i] = pool.get(ifaces[i]);
        }
        return result;
    }

    // Fields
    public CtField[] getDeclaredFields() {
        FieldInfo[] fields = classFile.getFields();
        CtField[] result = new CtField[fields.length];
        for (int i = 0; i < fields.length; i++) {
            result[i] = new CtField(fields[i], this);
        }
        return result;
    }

    public CtField getDeclaredField(String name) throws NotFoundException {
        for (FieldInfo f : classFile.getFields()) {
            if (f.getName().equals(name)) return new CtField(f, this);
        }
        throw new NotFoundException("Field not found: " + name);
    }

    // Methods
    public CtMethod[] getDeclaredMethods() {
        MethodInfo[] methods = classFile.getMethods();
        List<CtMethod> result = new ArrayList<CtMethod>();
        for (MethodInfo m : methods) {
            if (!"<init>".equals(m.getName()) && !"<clinit>".equals(m.getName())) {
                result.add(new CtMethod(m, this));
            }
        }
        return result.toArray(new CtMethod[result.size()]);
    }

    public CtMethod getDeclaredMethod(String name) throws NotFoundException {
        for (MethodInfo m : classFile.getMethods()) {
            if (m.getName().equals(name)) return new CtMethod(m, this);
        }
        throw new NotFoundException("Method not found: " + name);
    }

    public CtMethod getDeclaredMethod(String name, String descriptor) throws NotFoundException {
        for (MethodInfo m : classFile.getMethods()) {
            if (m.getName().equals(name) && m.getDescriptor().equals(descriptor)) {
                return new CtMethod(m, this);
            }
        }
        throw new NotFoundException("Method not found: " + name + descriptor);
    }

    // Constructors
    public CtConstructor[] getDeclaredConstructors() {
        MethodInfo[] methods = classFile.getMethods();
        List<CtConstructor> result = new ArrayList<CtConstructor>();
        for (MethodInfo m : methods) {
            if ("<init>".equals(m.getName()) || "<clinit>".equals(m.getName())) {
                result.add(new CtConstructor(m, this));
            }
        }
        return result.toArray(new CtConstructor[result.size()]);
    }

    // Annotations
    public AnnotationInfo[] getAnnotations() {
        List<AnnotationInfo> result = new ArrayList<AnnotationInfo>();
        for (Attribute attr : classFile.getAttributes()) {
            if (attr instanceof RuntimeAnnotationsAttribute) {
                RuntimeAnnotationsAttribute raa = (RuntimeAnnotationsAttribute) attr;
                for (AnnotationInfo ai : raa.getAnnotations()) {
                    result.add(ai);
                }
            }
        }
        return result.toArray(new AnnotationInfo[result.size()]);
    }

    public boolean hasAnnotation(String annotationType) {
        String desc = "L" + annotationType.replace('.', '/') + ";";
        for (AnnotationInfo ai : getAnnotations()) {
            if (desc.equals(ai.getTypeDescriptor())) return true;
        }
        return false;
    }

    // Generics
    public String getGenericSignature() {
        SignatureAttribute sig = classFile.findAttribute("Signature");
        return sig != null ? sig.getSignature() : null;
    }

    public String[] getTypeParameters() {
        String sig = getGenericSignature();
        if (sig == null) return new String[0];
        String params = SignatureParser.parseClassTypeParameters(sig);
        if (params == null || params.length() <= 2) return new String[0];
        // Strip < and >, split by comma
        String inner = params.substring(1, params.length() - 1);
        return inner.split(", ");
    }

    // Inner classes
    public String[] getInnerClassNames() {
        InnerClassesAttribute ica = classFile.findAttribute("InnerClasses");
        if (ica == null) return new String[0];
        List<String> names = new ArrayList<String>();
        for (InnerClassesAttribute.InnerClass ic : ica.getClasses()) {
            if (ic.outerClassName != null && ic.outerClassName.equals(classFile.getThisClassName())) {
                if (ic.innerClassName != null) names.add(TypeNameUtil.internalToQualified(ic.innerClassName));
            }
        }
        return names.toArray(new String[names.size()]);
    }

    public String getOuterClassName() {
        InnerClassesAttribute ica = classFile.findAttribute("InnerClasses");
        if (ica == null) return null;
        for (InnerClassesAttribute.InnerClass ic : ica.getClasses()) {
            if (ic.innerClassName != null && ic.innerClassName.equals(classFile.getThisClassName())) {
                return ic.outerClassName != null ? TypeNameUtil.internalToQualified(ic.outerClassName) : null;
            }
        }
        return null;
    }

    public String getEnclosingMethodName() {
        EnclosingMethodAttribute ema = classFile.findAttribute("EnclosingMethod");
        if (ema == null) return null;
        return ema.getMethodName();
    }

    // Records
    public CtRecordComponent[] getRecordComponents() {
        RecordAttribute ra = classFile.findAttribute("Record");
        if (ra == null) return null;
        RecordAttribute.RecordComponent[] comps = ra.getComponents();
        CtRecordComponent[] result = new CtRecordComponent[comps.length];
        for (int i = 0; i < comps.length; i++) {
            result[i] = new CtRecordComponent(comps[i].name, comps[i].descriptor, comps[i].attributes);
        }
        return result;
    }

    // Sealed
    public String[] getPermittedSubclasses() {
        PermittedSubclassesAttribute psa = classFile.findAttribute("PermittedSubclasses");
        if (psa == null) return new String[0];
        String[] raw = psa.getPermittedSubclasses();
        String[] result = new String[raw.length];
        for (int i = 0; i < raw.length; i++) {
            result[i] = TypeNameUtil.internalToQualified(raw[i]);
        }
        return result;
    }

    // Source
    public String getSourceFileName() {
        SourceFileAttribute sfa = classFile.findAttribute("SourceFile");
        return sfa != null ? sfa.getSourceFile() : null;
    }

    // Decompile
    public String decompile() throws Exception {
        final ClassFile cf = this.classFile;
        final byte[] data = serializeClassFile();
        final String name = classFile.getThisClassName();

        DenzoDecompiler decompiler = new DenzoDecompiler();
        final StringBuilder sb = new StringBuilder();

        Loader loader = new Loader() {
            public boolean canLoad(String n) { return name.equals(n); }
            public byte[] load(String n) { return data; }
        };

        Printer printer = new SimplePrinter(sb);
        decompiler.decompile(loader, printer, name);
        return sb.toString();
    }

    // Raw access
    public ConstantPool getConstantPool() { return classFile.getConstantPool(); }
    public ClassFile getClassFile() { return classFile; }
    ClassPool getPool() { return pool; }

    /**
     * We don't have a serializer, so for decompile() we need the original bytes.
     * Store them when creating the CtClass.
     */
    private byte[] originalBytes;
    void setOriginalBytes(byte[] bytes) { this.originalBytes = bytes; }
    private byte[] serializeClassFile() {
        return originalBytes != null ? originalBytes : new byte[0];
    }

    public String toString() {
        return "CtClass[" + getName() + "]";
    }

    /**
     * Simple printer for decompile().
     */
    private static class SimplePrinter implements Printer {
        private final StringBuilder sb;
        private int indent;
        SimplePrinter(StringBuilder sb) { this.sb = sb; }
        public void start(int a, int b, int c) {}
        public void end() {}
        public void printText(String t) { sb.append(t); }
        public void printNumericConstant(String c) { sb.append(c); }
        public void printStringConstant(String c, String o) { sb.append(c); }
        public void printKeyword(String k) { sb.append(k); }
        public void printDeclaration(int t, String i, String n, String d) { sb.append(n); }
        public void printReference(int t, String i, String n, String d, String o) { sb.append(n); }
        public void indent() { indent++; }
        public void unindent() { indent--; }
        public void startLine(int l) { for (int i = 0; i < indent; i++) sb.append("    "); }
        public void endLine() { sb.append("\n"); }
        public void extraLine(int c) { for (int i = 0; i < c; i++) sb.append("\n"); }
        public void startMarker(int t) {}
        public void endMarker(int t) {}
    }
}
