/*
 * This project is distributed under the GNU General Public License v3.0 (GPLv3).
 * It may be used, copied, modified and distributed, including for commercial purposes,
 * provided that distributed derivative works are licensed under the GPLv3 (see LICENSE).
 */
package it.denzosoft.javadecompiler.service.converter.transform;

import it.denzosoft.javadecompiler.model.javasyntax.expression.*;
import it.denzosoft.javadecompiler.model.javasyntax.statement.*;

import java.util.ArrayList;
import java.util.List;

/**
 * Removes a leftover handler store {@code v = $exception;} at the head of a catch body and
 * renames {@code v} to the catch parameter inside that body (BUG-2026-0150).
 *
 * <p>The try/catch reconstruction strips the handler's opening {@code astore} only when the slot
 * name equals the catch variable name. A slot renamed by the type-conflict split ({@code var3a})
 * or by the shared-slot resolution keeps the store, and {@code $exception} - the decoder's seed for
 * the caught value - is not a Java variable. Both forms name the caught exception, so renaming
 * the body's references to the catch parameter is exact.</p>
 */
public final class CatchVarNormalizer {

    private CatchVarNormalizer() { }

    public static List<Statement> normalize(List<Statement> body) {
        if (body == null) return null;
        return new AstMapper() {
            @Override
            public Statement map(Statement s) {
                Statement m = super.map(s);
                if (!(m instanceof TryCatchStatement)) return m;
                TryCatchStatement t = (TryCatchStatement) m;
                boolean changed = false;
                List<TryCatchStatement.CatchClause> out = new ArrayList<TryCatchStatement.CatchClause>();
                for (TryCatchStatement.CatchClause cc : t.getCatchClauses()) {
                    Statement nb = fixClause(cc);
                    if (nb != cc.body) {
                        changed = true;
                        out.add(new TryCatchStatement.CatchClause(cc.exceptionTypes, cc.variableName, nb));
                    } else {
                        out.add(cc);
                    }
                }
                if (!changed) return m;
                return new TryCatchStatement(t.getLineNumber(), t.getTryBody(), out, t.getFinallyBody(), t.getResources());
            }
        }.mapList(body);
    }

    private static Statement fixClause(TryCatchStatement.CatchClause cc) {
        if (cc.variableName == null || !(cc.body instanceof BlockStatement)) return cc.body;
        List<Statement> st = ((BlockStatement) cc.body).getStatements();
        if (st == null || st.isEmpty()) return cc.body;
        String stored = storedName(st.get(0));
        if (stored == null) return cc.body;
        List<Statement> rest = new ArrayList<Statement>(st.subList(1, st.size()));
        if (!stored.equals(cc.variableName)) {
            rest = new AstMapper.Renamer(stored, cc.variableName).mapList(rest);
        }
        // any other reference to the seed itself is the caught exception too
        rest = new AstMapper.Renamer("$exception", cc.variableName).mapList(rest);
        return new BlockStatement(cc.body.getLineNumber(), rest);
    }

    /** Name V of a leading `V = $exception;` / `T V = $exception;`, else null. */
    private static String storedName(Statement s) {
        if (s instanceof VariableDeclarationStatement) {
            VariableDeclarationStatement v = (VariableDeclarationStatement) s;
            return isSeed(v.getInitializer()) ? v.getName() : null;
        }
        if (s instanceof ExpressionStatement && ((ExpressionStatement) s).getExpression() instanceof AssignmentExpression) {
            AssignmentExpression a = (AssignmentExpression) ((ExpressionStatement) s).getExpression();
            if ("=".equals(a.getOperator()) && a.getLeft() instanceof LocalVariableExpression && isSeed(a.getRight())) {
                return ((LocalVariableExpression) a.getLeft()).getName();
            }
        }
        return null;
    }

    private static boolean isSeed(Expression e) {
        while (e instanceof CastExpression) e = ((CastExpression) e).getExpression();
        return e instanceof LocalVariableExpression && "$exception".equals(((LocalVariableExpression) e).getName());
    }
}
