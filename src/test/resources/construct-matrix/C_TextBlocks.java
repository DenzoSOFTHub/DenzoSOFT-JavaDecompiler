/** Text blocks: basic, formatted interpolation, escapes, line continuation. */
public class C_TextBlocks {

    /** Basic multi-line text block. */
    public String html() {
        return """
                <html>
                    <body>
                        <p>Hello</p>
                    </body>
                </html>
                """;
    }

    /** Text block used with formatted() for interpolation. */
    public String greeting(String name, int age) {
        return """
                Name: %s
                Age:  %d
                """.formatted(name, age);
    }

    /** Text block with escape sequences (\\s for trailing space, \\n explicit, \\" quote). */
    public String escapes() {
        return """
                tab:\tend
                quote:\"q\"
                trailing:\s\s
                explicit-newline:\nmid
                """;
    }

    /** Text block with line continuation (\\ suppresses the newline). */
    public String oneLine() {
        return """
                this is \
                a single \
                logical line""";
    }

    /** Text block JSON snippet. */
    public String json() {
        return """
                {
                  "id": 1,
                  "name": "alpha"
                }""";
    }
}
