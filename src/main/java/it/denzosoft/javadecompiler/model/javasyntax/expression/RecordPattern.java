/*
 * This project is distributed under the GNU General Public License v3.0 (GPLv3).
 * It may be used, copied, modified and distributed, including for commercial purposes,
 * provided that distributed derivative works are licensed under the GPLv3 (see LICENSE).
 */
package it.denzosoft.javadecompiler.model.javasyntax.expression;

import it.denzosoft.javadecompiler.model.javasyntax.type.Type;

import java.util.List;

/**
 * A Java 21 record-deconstruction pattern, e.g. {@code Point(int x, int y)} or the nested
 * {@code Line(Point(int x1, int y1), Point(int x2, int y2))}. It is NOT an {@link Expression}; it is
 * carried by the host {@link InstanceOfExpression} (instanceof form) or
 * {@link SwitchExpression.SwitchCase} (switch form) and rendered by a dedicated writer helper, so no
 * {@code ExpressionVisitor} change is needed (BUG-2026-0067 record patterns).
 */
public class RecordPattern {

    private final List<Component> components;

    public RecordPattern(List<Component> components) {
        this.components = components;
    }

    public List<Component> getComponents() { return components; }

    /** One component of a record pattern: either a simple binding or a nested record pattern. */
    public static final class Component {
        private final Type type;            // binding declared type, or the record type when nested
        private final String bindingName;   // non-null => simple binding
        private final RecordPattern nested; // non-null => nested record pattern
        private final boolean isVar;        // render `var` rather than the explicit type

        public Component(Type type, String bindingName, boolean isVar) {
            this.type = type;
            this.bindingName = bindingName;
            this.nested = null;
            this.isVar = isVar;
        }

        public Component(Type type, RecordPattern nested) {
            this.type = type;
            this.bindingName = null;
            this.nested = nested;
            this.isVar = false;
        }

        public Type getType() { return type; }
        public String getBindingName() { return bindingName; }
        public RecordPattern getNested() { return nested; }
        public boolean isNested() { return nested != null; }
        public boolean isVar() { return isVar; }
    }
}
