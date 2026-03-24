/*
 * DenzoSOFT Java Decompiler
 * Copyright (c) 2024-2026 DenzoSOFT. All rights reserved.
 */
package it.denzosoft.javadecompiler.model.classfile.attribute;

import java.util.List;

public class ModuleAttribute extends Attribute {
    private final String moduleName;
    private final int moduleFlags;
    private final String moduleVersion;
    private final List<Requires> requires;
    private final List<Exports> exports;
    private final List<Opens> opens;
    private final List<String> uses;
    private final List<Provides> provides;

    public ModuleAttribute(String name, int length, String moduleName, int moduleFlags,
                           String moduleVersion, List<Requires> requires, List<Exports> exports,
                           List<Opens> opens, List<String> uses, List<Provides> provides) {
        super(name, length);
        this.moduleName = moduleName;
        this.moduleFlags = moduleFlags;
        this.moduleVersion = moduleVersion;
        this.requires = requires;
        this.exports = exports;
        this.opens = opens;
        this.uses = uses;
        this.provides = provides;
    }

    public String getModuleName() { return moduleName; }
    public int getModuleFlags() { return moduleFlags; }
    public String getModuleVersion() { return moduleVersion; }
    public List<Requires> getRequires() { return requires; }
    public List<Exports> getExports() { return exports; }
    public List<Opens> getOpens() { return opens; }
    public List<String> getUses() { return uses; }
    public List<Provides> getProvides() { return provides; }

    public static class Requires {
        public final String name;
        public final int flags;
        public final String version;

        public Requires(String name, int flags, String version) {
            this.name = name;
            this.flags = flags;
            this.version = version;
        }
    }

    public static class Exports {
        public final String name;
        public final int flags;
        public final String[] to;

        public Exports(String name, int flags, String[] to) {
            this.name = name;
            this.flags = flags;
            this.to = to;
        }
    }

    public static class Opens {
        public final String name;
        public final int flags;
        public final String[] to;

        public Opens(String name, int flags, String[] to) {
            this.name = name;
            this.flags = flags;
            this.to = to;
        }
    }

    public static class Provides {
        public final String service;
        public final String[] providers;

        public Provides(String service, String[] providers) {
            this.service = service;
            this.providers = providers;
        }
    }
}
