/*
 * This project is distributed under the GPLv3 license.
 * This is a Copyleft license that gives the user the right to use,
 * copy and modify the code freely for non-commercial purposes.
 */
package it.denzosoft.javadecompiler.service.converter.transform;

import it.denzosoft.javadecompiler.model.javasyntax.expression.*;
import it.denzosoft.javadecompiler.model.javasyntax.statement.*;
import it.denzosoft.javadecompiler.model.javasyntax.type.ObjectType;
import it.denzosoft.javadecompiler.service.converter.cfg.ClassHierarchy;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Turns javac's table-driven enum switch back into {@code switch (e) { case CONST: ... }}
 * (BUG-2026-0166).
 *
 * <p>javac compiles a switch on an enum into {@code switch ($SwitchMap$E[e.ordinal()])}; the
 * case labels are indexes into that synthetic table, not ordinals. The table's contents are read
 * from the static initializer of the class that owns it ({@link ClassHierarchy#switchMap}). When
 * every label of the switch maps to a constant, the selector becomes the enum value and each
 * label the constant's name. When the table cannot be read, or one label is not in it, the switch
 * is left exactly as decoded.</p>
 */
public final class EnumSwitchMapResolver {

    private EnumSwitchMapResolver() { }

    public static List<Statement> resolve(List<Statement> body, final ClassHierarchy hierarchy) {
        if (hierarchy == null || body == null) return body;
        return new AstMapper() {
            @Override
            public Statement map(Statement s) {
                Statement m = super.map(s);
                if (m instanceof SwitchStatement) {
                    SwitchStatement sw = (SwitchStatement) m;
                    Map<Integer, String[]> table = tableOf(sw.getSelector(), hierarchy);
                    if (table == null) return m;
                    List<SwitchStatement.SwitchCase> cases = new ArrayList<SwitchStatement.SwitchCase>();
                    for (SwitchStatement.SwitchCase c : sw.getCases()) {
                        if (c.isPattern() || c.isNullLabel()) return m;
                        List<Expression> labels = c.isDefault() ? c.getLabels() : mapLabels(c.getLabels(), table);
                        if (labels == null && !c.isDefault()) return m;
                        cases.add(new SwitchStatement.SwitchCase(labels, c.getStatements()));
                    }
                    return new SwitchStatement(sw.getLineNumber(), enumValue(sw.getSelector()), cases,
                        sw.isArrowStyle());
                }
                return m;
            }

            @Override
            public Expression map(Expression e) {
                Expression m = super.map(e);
                if (m instanceof SwitchExpression) {
                    SwitchExpression sw = (SwitchExpression) m;
                    Map<Integer, String[]> table = tableOf(sw.getSelector(), hierarchy);
                    if (table == null) return m;
                    List<SwitchExpression.SwitchCase> cases = new ArrayList<SwitchExpression.SwitchCase>();
                    for (SwitchExpression.SwitchCase c : sw.getCases()) {
                        if (c.isPattern() || c.isRecordPattern() || c.getGuard() != null) return m;
                        List<Expression> labels = c.isDefault() ? c.getLabels() : mapLabels(c.getLabels(), table);
                        if (labels == null && !c.isDefault()) return m;
                        SwitchExpression.SwitchCase nc = new SwitchExpression.SwitchCase(labels, c.getValue());
                        nc.setBodyStatements(c.getBodyStatements());
                        cases.add(nc);
                    }
                    return new SwitchExpression(sw.getLineNumber(), sw.getType(), enumValue(sw.getSelector()), cases);
                }
                return m;
            }
        }.mapList(body);
    }

    /** The table behind {@code $SwitchMap$E[x.ordinal()]}, or null for any other selector. */
    private static Map<Integer, String[]> tableOf(Expression sel, ClassHierarchy hierarchy) {
        if (!(sel instanceof ArrayAccessExpression)) return null;
        ArrayAccessExpression aa = (ArrayAccessExpression) sel;
        if (!(aa.getArray() instanceof FieldAccessExpression)) return null;
        FieldAccessExpression f = (FieldAccessExpression) aa.getArray();
        if (f.getName() == null || !f.getName().startsWith("$SwitchMap$")) return null;
        if (!(aa.getIndex() instanceof MethodInvocationExpression)) return null;
        MethodInvocationExpression mi = (MethodInvocationExpression) aa.getIndex();
        if (!"ordinal".equals(mi.getMethodName()) || mi.getObject() == null) return null;
        Map<Integer, String[]> t = hierarchy.switchMap(f.getOwnerInternalName(), f.getName());
        return t == null || t.isEmpty() ? null : t;
    }

    private static Expression enumValue(Expression sel) {
        return ((MethodInvocationExpression) ((ArrayAccessExpression) sel).getIndex()).getObject();
    }

    /** Labels as enum constants, or null when one of them is not an index of the table. */
    private static List<Expression> mapLabels(List<Expression> in, Map<Integer, String[]> table) {
        List<Expression> out = new ArrayList<Expression>();
        for (Expression l : in) {
            if (!(l instanceof IntegerConstantExpression)) return null;
            String[] k = table.get(Integer.valueOf(((IntegerConstantExpression) l).getValue()));
            if (k == null) return null;
            out.add(new FieldAccessExpression(l.getLineNumber(), new ObjectType(k[0]), null, k[0], k[1],
                "L" + k[0] + ";"));
        }
        return out;
    }
}
