/*
 * This project is distributed under the GNU General Public License v3.0 (GPLv3).
 * It may be used, copied, modified and distributed, including for commercial purposes,
 * provided that distributed derivative works are licensed under the GPLv3 (see LICENSE).
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
