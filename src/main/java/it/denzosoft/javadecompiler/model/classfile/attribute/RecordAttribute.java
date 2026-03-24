/*
 * This project is distributed under the GPLv3 license.
 * This is a Copyleft license that gives the user the right to use,
 * copy and modify the code freely for non-commercial purposes.
 */
package it.denzosoft.javadecompiler.model.classfile.attribute;

import java.util.List;

public class RecordAttribute extends Attribute {
    private final RecordComponent[] components;

    public RecordAttribute(String name, int length, RecordComponent[] components) {
        super(name, length);
        this.components = components;
    }

    public RecordComponent[] getComponents() { return components; }

    public static class RecordComponent {
        public final String name;
        public final String descriptor;
        public final List<Attribute> attributes;

        public RecordComponent(String name, String descriptor, List<Attribute> attributes) {
            this.name = name;
            this.descriptor = descriptor;
            this.attributes = attributes;
        }
    }
}
