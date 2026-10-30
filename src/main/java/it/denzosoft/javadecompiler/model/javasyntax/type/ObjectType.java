/*
 * This project is distributed under the GNU General Public License v3.0 (GPLv3).
 * It may be used, copied, modified and distributed, including for commercial purposes,
 * provided that distributed derivative works are licensed under the GPLv3 (see LICENSE).
 */
package it.denzosoft.javadecompiler.model.javasyntax.type;

import it.denzosoft.javadecompiler.util.TypeNameUtil;

public class ObjectType implements Type {
    public static final ObjectType OBJECT = new ObjectType("java/lang/Object", "java.lang.Object", "Object");
    public static final ObjectType STRING = new ObjectType("java/lang/String", "java.lang.String", "String");
    public static final ObjectType CLASS = new ObjectType("java/lang/Class", "java.lang.Class", "Class");

    private final String internalName;
    private final String qualifiedName;
    private final String simpleName;
    private final int dimension;

    public ObjectType(String internalName) {
        this(internalName, TypeNameUtil.internalToQualified(internalName),
             TypeNameUtil.simpleNameFromInternal(internalName), 0);
    }

    public ObjectType(String internalName, String qualifiedName, String simpleName) {
        this(internalName, qualifiedName, simpleName, 0);
    }

    public ObjectType(String internalName, String qualifiedName, String simpleName, int dimension) {
        this.internalName = internalName;
        this.qualifiedName = qualifiedName;
        this.simpleName = simpleName;
        this.dimension = dimension;
    }

    public String getInternalName() { return internalName; }
    public String getQualifiedName() { return qualifiedName; }

    @Override public String getDescriptor() { return "L" + internalName + ";"; }
    @Override public String getName() { return simpleName; }
    @Override public int getDimension() { return dimension; }
    @Override public void accept(TypeVisitor visitor) { visitor.visit(this); }

    public ObjectType createArrayType(int dim) {
        return new ObjectType(internalName, qualifiedName, simpleName, dim);
    }

    @Override public String toString() { return simpleName; }
}
