/*
 * This project is distributed under the GPLv3 license.
 * This is a Copyleft license that gives the user the right to use,
 * copy and modify the code freely for non-commercial purposes.
 */
package it.denzosoft.javadecompiler.api.classmodel;

import it.denzosoft.javadecompiler.model.classfile.attribute.Attribute;
import it.denzosoft.javadecompiler.model.classfile.attribute.SignatureAttribute;
import it.denzosoft.javadecompiler.util.TypeNameUtil;

import java.util.List;

/**
 * Represents a record component in a record class.
 */
public class CtRecordComponent {

    private final String name;
    private final String descriptor;
    private final String signature;

    CtRecordComponent(String name, String descriptor, List<Attribute> attributes) {
        this.name = name;
        this.descriptor = descriptor;
        String sig = null;
        if (attributes != null) {
            for (Attribute attr : attributes) {
                if ("Signature".equals(attr.getName()) && attr instanceof SignatureAttribute) {
                    sig = ((SignatureAttribute) attr).getSignature();
                    break;
                }
            }
        }
        this.signature = sig;
    }

    /**
     * Get the name of this record component.
     */
    public String getName() {
        return name;
    }

    /**
     * Get the field descriptor of this record component (e.g., "I", "Ljava/lang/String;").
     */
    public String getDescriptor() {
        return descriptor;
    }

    /**
     * Get the human-readable type name (e.g., "int", "java.lang.String").
     */
    public String getTypeName() {
        return TypeNameUtil.descriptorToTypeName(descriptor, false);
    }

    /**
     * Get the generic signature, or null if there is none.
     */
    public String getGenericSignature() {
        return signature;
    }
}
