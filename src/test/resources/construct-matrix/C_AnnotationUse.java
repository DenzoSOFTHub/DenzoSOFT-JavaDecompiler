/** Uses annotations on classes, methods, fields and parameters; plus built-ins. */
@C_AnnotationDecl.Marker
@C_AnnotationDecl.Config(author = "denzo", version = 2, tags = { "demo", "test" })
public class C_AnnotationUse {

    @C_AnnotationDecl.Name("counter")
    private int count;

    @Deprecated
    public int oldApi() {
        return 0;
    }

    @C_AnnotationDecl.Name("increment")
    @C_AnnotationDecl.Config(author = "denzo")
    public void increment() {
        count++;
    }

    // Annotation on a parameter, plus @SuppressWarnings.
    @SuppressWarnings("unchecked")
    public java.util.List<String> raw(@C_AnnotationDecl.Name("arg") Object o) {
        return (java.util.List<String>) o;
    }

    // @Override and covariant return are tested in C_Covariant; here just @Override.
    @Override
    public String toString() {
        return "count=" + count;
    }
}
