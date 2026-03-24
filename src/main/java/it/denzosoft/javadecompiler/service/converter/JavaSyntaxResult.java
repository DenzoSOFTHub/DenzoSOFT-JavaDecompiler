/*
 * This project is distributed under the GPLv3 license.
 * This is a Copyleft license that gives the user the right to use,
 * copy and modify the code freely for non-commercial purposes.
 */
package it.denzosoft.javadecompiler.service.converter;

import it.denzosoft.javadecompiler.model.classfile.attribute.AnnotationInfo;
import it.denzosoft.javadecompiler.model.javasyntax.expression.Expression;
import it.denzosoft.javadecompiler.model.javasyntax.statement.Statement;
import it.denzosoft.javadecompiler.model.javasyntax.type.Type;

import java.util.ArrayList;
import java.util.List;

/**
 * Result of the ClassFile to Java syntax conversion.
 * Contains all the information needed to generate Java source code.
 */
public class JavaSyntaxResult {
    private int majorVersion;
    private int minorVersion;
    private int accessFlags;
    private String internalName;
    private String superName;
    private String[] interfaces;
    private String sourceFile;
    private String signature;
    private List<FieldDeclaration> fields = new ArrayList<FieldDeclaration>();
    private List<MethodDeclaration> methods = new ArrayList<MethodDeclaration>();
    private List<RecordComponentInfo> recordComponents;
    private List<String> permittedSubclasses;
    private List<InnerClassInfo> innerClasses;
    private List<AnnotationInfo> classAnnotations;
    private List<JavaSyntaxResult> innerClassResults = new ArrayList<JavaSyntaxResult>();
    private boolean isInnerClass;
    private int innerClassAccessFlags;

    // Module info fields
    private String moduleName;
    private int moduleFlags;
    private String moduleVersion;
    private List<String[]> moduleRequires;  // [name, version]
    private List<String[]> moduleExports;   // [name, to...]
    private List<String[]> moduleOpens;     // [name, to...]
    private List<String> moduleUses;
    private List<String[]> moduleProvides;  // [service, impl...]

    // Getters and setters
    public int getMajorVersion() { return majorVersion; }
    public void setMajorVersion(int majorVersion) { this.majorVersion = majorVersion; }
    public int getMinorVersion() { return minorVersion; }
    public void setMinorVersion(int minorVersion) { this.minorVersion = minorVersion; }
    public int getAccessFlags() { return accessFlags; }
    public void setAccessFlags(int accessFlags) { this.accessFlags = accessFlags; }
    public String getInternalName() { return internalName; }
    public void setInternalName(String internalName) { this.internalName = internalName; }
    public String getSuperName() { return superName; }
    public void setSuperName(String superName) { this.superName = superName; }
    public String[] getInterfaces() { return interfaces; }
    public void setInterfaces(String[] interfaces) { this.interfaces = interfaces; }
    public String getSourceFile() { return sourceFile; }
    public void setSourceFile(String sourceFile) { this.sourceFile = sourceFile; }
    public String getSignature() { return signature; }
    public void setSignature(String signature) { this.signature = signature; }
    public List<FieldDeclaration> getFields() { return fields; }
    public void addField(FieldDeclaration field) { this.fields.add(field); }
    public List<MethodDeclaration> getMethods() { return methods; }
    public void addMethod(MethodDeclaration method) { this.methods.add(method); }
    public List<RecordComponentInfo> getRecordComponents() { return recordComponents; }
    public void setRecordComponents(List<RecordComponentInfo> recordComponents) { this.recordComponents = recordComponents; }
    public List<String> getPermittedSubclasses() { return permittedSubclasses; }
    public void setPermittedSubclasses(List<String> permittedSubclasses) { this.permittedSubclasses = permittedSubclasses; }
    public List<InnerClassInfo> getInnerClasses() { return innerClasses; }
    public void setInnerClasses(List<InnerClassInfo> innerClasses) { this.innerClasses = innerClasses; }
    public List<AnnotationInfo> getClassAnnotations() { return classAnnotations; }
    public void setClassAnnotations(List<AnnotationInfo> classAnnotations) { this.classAnnotations = classAnnotations; }

    public List<JavaSyntaxResult> getInnerClassResults() { return innerClassResults; }
    public void addInnerClassResult(JavaSyntaxResult inner) { innerClassResults.add(inner); }
    public boolean isInnerClass() { return isInnerClass; }
    public void setInnerClass(boolean isInnerClass) { this.isInnerClass = isInnerClass; }
    public int getInnerClassAccessFlags() { return innerClassAccessFlags; }
    public void setInnerClassAccessFlags(int innerClassAccessFlags) { this.innerClassAccessFlags = innerClassAccessFlags; }

    public boolean isInterface() { return (accessFlags & 0x0200) != 0; }
    public boolean isAbstract() { return (accessFlags & 0x0400) != 0; }
    public boolean isEnum() { return (accessFlags & 0x4000) != 0; }
    public boolean isAnnotation() { return (accessFlags & 0x2000) != 0; }
    public boolean isRecord() { return "java/lang/Record".equals(superName); }
    public boolean isSealed() { return permittedSubclasses != null && !permittedSubclasses.isEmpty(); }
    public boolean isModule() { return (accessFlags & 0x8000) != 0; }

    // Module getters and setters
    public String getModuleName() { return moduleName; }
    public void setModuleName(String moduleName) { this.moduleName = moduleName; }
    public int getModuleFlags() { return moduleFlags; }
    public void setModuleFlags(int moduleFlags) { this.moduleFlags = moduleFlags; }
    public String getModuleVersion() { return moduleVersion; }
    public void setModuleVersion(String moduleVersion) { this.moduleVersion = moduleVersion; }
    public List<String[]> getModuleRequires() { return moduleRequires; }
    public void setModuleRequires(List<String[]> moduleRequires) { this.moduleRequires = moduleRequires; }
    public List<String[]> getModuleExports() { return moduleExports; }
    public void setModuleExports(List<String[]> moduleExports) { this.moduleExports = moduleExports; }
    public List<String[]> getModuleOpens() { return moduleOpens; }
    public void setModuleOpens(List<String[]> moduleOpens) { this.moduleOpens = moduleOpens; }
    public List<String> getModuleUses() { return moduleUses; }
    public void setModuleUses(List<String> moduleUses) { this.moduleUses = moduleUses; }
    public List<String[]> getModuleProvides() { return moduleProvides; }
    public void setModuleProvides(List<String[]> moduleProvides) { this.moduleProvides = moduleProvides; }

    public static class FieldDeclaration {
        public final int accessFlags;
        public final String name;
        public final String descriptor;
        public final Type type;
        public final Expression initialValue;
        public final String signature;
        public final List<AnnotationInfo> annotations;

        public FieldDeclaration(int accessFlags, String name, String descriptor,
                                 Type type, Expression initialValue, String signature,
                                 List<AnnotationInfo> annotations) {
            this.accessFlags = accessFlags;
            this.name = name;
            this.descriptor = descriptor;
            this.type = type;
            this.initialValue = initialValue;
            this.signature = signature;
            this.annotations = annotations;
        }

        public boolean isPublic() { return (accessFlags & 0x0001) != 0; }
        public boolean isPrivate() { return (accessFlags & 0x0002) != 0; }
        public boolean isProtected() { return (accessFlags & 0x0004) != 0; }
        public boolean isStatic() { return (accessFlags & 0x0008) != 0; }
        public boolean isFinal() { return (accessFlags & 0x0010) != 0; }
        public boolean isVolatile() { return (accessFlags & 0x0040) != 0; }
        public boolean isTransient() { return (accessFlags & 0x0080) != 0; }
        public boolean isEnum() { return (accessFlags & 0x4000) != 0; }
        // START_CHANGE: ISS-2026-0011-20260323-7 - Add isSynthetic check for field declarations
        public boolean isSynthetic() { return (accessFlags & 0x1000) != 0; }
        // END_CHANGE: ISS-2026-0011-7
    }

    public static class MethodDeclaration {
        public final int accessFlags;
        public final String name;
        public final String descriptor;
        public final Type returnType;
        public final List<Type> parameterTypes;
        public final List<String> parameterNames;
        public final List<String> thrownExceptions;
        public final List<Statement> body;
        public final int maxLineNumber;
        public final String signature;
        public final List<AnnotationInfo> annotations;
        public final List<List<AnnotationInfo>> parameterAnnotations;

        public MethodDeclaration(int accessFlags, String name, String descriptor,
                                   Type returnType, List<Type> parameterTypes,
                                   List<String> parameterNames, List<String> thrownExceptions,
                                   List<Statement> body, int maxLineNumber, String signature,
                                   List<AnnotationInfo> annotations,
                                   List<List<AnnotationInfo>> parameterAnnotations) {
            this.accessFlags = accessFlags;
            this.name = name;
            this.descriptor = descriptor;
            this.returnType = returnType;
            this.parameterTypes = parameterTypes;
            this.parameterNames = parameterNames;
            this.thrownExceptions = thrownExceptions;
            this.body = body;
            this.maxLineNumber = maxLineNumber;
            this.signature = signature;
            this.annotations = annotations;
            this.parameterAnnotations = parameterAnnotations;
        }

        public boolean isPublic() { return (accessFlags & 0x0001) != 0; }
        public boolean isPrivate() { return (accessFlags & 0x0002) != 0; }
        public boolean isProtected() { return (accessFlags & 0x0004) != 0; }
        public boolean isStatic() { return (accessFlags & 0x0008) != 0; }
        public boolean isFinal() { return (accessFlags & 0x0010) != 0; }
        public boolean isAbstract() { return (accessFlags & 0x0400) != 0; }
        public boolean isNative() { return (accessFlags & 0x0100) != 0; }
        public boolean isSynchronized() { return (accessFlags & 0x0020) != 0; }
        public boolean isConstructor() { return "<init>".equals(name); }
        public boolean isVarargs() { return (accessFlags & 0x0080) != 0; }
    }

    public static class RecordComponentInfo {
        public final String name;
        public final String descriptor;
        public final Type type;

        public RecordComponentInfo(String name, String descriptor, Type type) {
            this.name = name;
            this.descriptor = descriptor;
            this.type = type;
        }
    }

    public static class InnerClassInfo {
        public final String innerClassName;
        public final String outerClassName;
        public final String innerName;
        public final int accessFlags;

        public InnerClassInfo(String innerClassName, String outerClassName, String innerName, int accessFlags) {
            this.innerClassName = innerClassName;
            this.outerClassName = outerClassName;
            this.innerName = innerName;
            this.accessFlags = accessFlags;
        }
    }
}
