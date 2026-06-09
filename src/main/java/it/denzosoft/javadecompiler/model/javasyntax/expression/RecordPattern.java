/*
 * This project is distributed under the GPLv3 license.
 * This is a Copyleft license that gives the user the right to use,
 * copy and modify the code freely for non-commercial purposes.
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
