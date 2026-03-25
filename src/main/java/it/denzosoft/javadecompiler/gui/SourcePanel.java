/*
 * This project is distributed under the GPLv3 license.
 * This is a Copyleft license that gives the user the right to use,
 * copy and modify the code freely for non-commercial purposes.
 */
package it.denzosoft.javadecompiler.gui;

import javax.swing.*;
import javax.swing.text.*;
import java.awt.*;
import java.util.HashSet;
import java.util.Set;

/**
 * Editor panel with basic syntax highlighting for decompiled Java source.
 */
public class SourcePanel extends JPanel {

    private JTextPane textPane;
    private String source;
    private String entryName;
    private int lastFindIndex = 0;

    private static final Set KEYWORDS = new HashSet();
    static {
        String[] kw = {
            "abstract", "assert", "boolean", "break", "byte", "case", "catch",
            "char", "class", "const", "continue", "default", "do", "double",
            "else", "enum", "extends", "final", "finally", "float", "for",
            "goto", "if", "implements", "import", "instanceof", "int",
            "interface", "long", "native", "new", "package", "private",
            "protected", "public", "return", "short", "static", "strictfp",
            "super", "switch", "synchronized", "this", "throw", "throws",
            "transient", "try", "void", "volatile", "while", "true", "false", "null"
        };
        for (int i = 0; i < kw.length; i++) {
            KEYWORDS.add(kw[i]);
        }
    }

    public SourcePanel(String entryName, String source) {
        this.entryName = entryName;
        this.source = source;

        textPane = new JTextPane();
        textPane.setEditable(false);
        textPane.setFont(new Font("Monospaced", Font.PLAIN, 13));

        if (entryName.endsWith(".class") || entryName.endsWith(".java")) {
            applySyntaxHighlighting(source);
        } else {
            textPane.setText(source);
        }

        textPane.setCaretPosition(0);

        setLayout(new BorderLayout());
        add(new JScrollPane(textPane), BorderLayout.CENTER);
    }

    public JTextPane getTextPane() {
        return textPane;
    }

    public String getEntryName() {
        return entryName;
    }

    private void applySyntaxHighlighting(String src) {
        StyledDocument doc = textPane.getStyledDocument();

        // Default style
        SimpleAttributeSet defaultAttr = new SimpleAttributeSet();
        StyleConstants.setForeground(defaultAttr, Color.BLACK);
        StyleConstants.setFontFamily(defaultAttr, "Monospaced");
        StyleConstants.setFontSize(defaultAttr, 13);

        // Keyword style
        SimpleAttributeSet keywordAttr = new SimpleAttributeSet(defaultAttr);
        StyleConstants.setForeground(keywordAttr, new Color(0, 0, 180));
        StyleConstants.setBold(keywordAttr, true);

        // String style
        SimpleAttributeSet stringAttr = new SimpleAttributeSet(defaultAttr);
        StyleConstants.setForeground(stringAttr, new Color(0, 128, 0));

        // Comment style
        SimpleAttributeSet commentAttr = new SimpleAttributeSet(defaultAttr);
        StyleConstants.setForeground(commentAttr, new Color(128, 128, 128));
        StyleConstants.setItalic(commentAttr, true);

        // Annotation style
        SimpleAttributeSet annotationAttr = new SimpleAttributeSet(defaultAttr);
        StyleConstants.setForeground(annotationAttr, new Color(0, 128, 128));

        // Number style
        SimpleAttributeSet numberAttr = new SimpleAttributeSet(defaultAttr);
        StyleConstants.setForeground(numberAttr, new Color(180, 0, 0));

        try {
            doc.insertString(0, src, defaultAttr);

            int len = src.length();
            int i = 0;
            while (i < len) {
                char c = src.charAt(i);

                // Line comments
                if (c == '/' && i + 1 < len && src.charAt(i + 1) == '/') {
                    int end = src.indexOf('\n', i);
                    if (end < 0) end = len;
                    doc.setCharacterAttributes(i, end - i, commentAttr, true);
                    i = end;
                    continue;
                }

                // Block comments
                if (c == '/' && i + 1 < len && src.charAt(i + 1) == '*') {
                    int end = src.indexOf("*/", i + 2);
                    if (end < 0) end = len - 2;
                    doc.setCharacterAttributes(i, end + 2 - i, commentAttr, true);
                    i = end + 2;
                    continue;
                }

                // String literals
                if (c == '"') {
                    int end = i + 1;
                    while (end < len) {
                        char sc = src.charAt(end);
                        if (sc == '\\') {
                            end += 2;
                            continue;
                        }
                        if (sc == '"') {
                            end++;
                            break;
                        }
                        end++;
                    }
                    doc.setCharacterAttributes(i, end - i, stringAttr, true);
                    i = end;
                    continue;
                }

                // Char literals
                if (c == '\'') {
                    int end = i + 1;
                    while (end < len) {
                        char sc = src.charAt(end);
                        if (sc == '\\') {
                            end += 2;
                            continue;
                        }
                        if (sc == '\'') {
                            end++;
                            break;
                        }
                        end++;
                    }
                    doc.setCharacterAttributes(i, end - i, stringAttr, true);
                    i = end;
                    continue;
                }

                // Annotations
                if (c == '@' && i + 1 < len && Character.isLetter(src.charAt(i + 1))) {
                    int end = i + 1;
                    while (end < len && (Character.isLetterOrDigit(src.charAt(end)) || src.charAt(end) == '.')) {
                        end++;
                    }
                    doc.setCharacterAttributes(i, end - i, annotationAttr, true);
                    i = end;
                    continue;
                }

                // Identifiers / keywords
                if (Character.isJavaIdentifierStart(c)) {
                    int end = i + 1;
                    while (end < len && Character.isJavaIdentifierPart(src.charAt(end))) {
                        end++;
                    }
                    String word = src.substring(i, end);
                    if (KEYWORDS.contains(word)) {
                        doc.setCharacterAttributes(i, end - i, keywordAttr, true);
                    }
                    i = end;
                    continue;
                }

                // Numbers
                if (Character.isDigit(c) || (c == '.' && i + 1 < len && Character.isDigit(src.charAt(i + 1)))) {
                    int end = i + 1;
                    while (end < len && (Character.isLetterOrDigit(src.charAt(end))
                            || src.charAt(end) == '.' || src.charAt(end) == '_'
                            || src.charAt(end) == 'x' || src.charAt(end) == 'X')) {
                        end++;
                    }
                    doc.setCharacterAttributes(i, end - i, numberAttr, true);
                    i = end;
                    continue;
                }

                i++;
            }
        } catch (BadLocationException e) {
            // Fallback: plain text
            textPane.setText(src);
        }
    }

    /**
     * Search for text in the source, highlighting and scrolling to the next match.
     * Returns true if found.
     */
    public boolean find(String text) {
        if (text == null || text.length() == 0) {
            return false;
        }
        String content = source;
        if (content == null) {
            return false;
        }
        String lowerContent = content.toLowerCase();
        String lowerText = text.toLowerCase();

        int idx = lowerContent.indexOf(lowerText, lastFindIndex);
        if (idx < 0) {
            // Wrap around
            idx = lowerContent.indexOf(lowerText, 0);
            if (idx < 0) {
                lastFindIndex = 0;
                return false;
            }
        }
        lastFindIndex = idx + 1;

        textPane.setCaretPosition(idx);
        textPane.select(idx, idx + text.length());
        textPane.requestFocusInWindow();
        return true;
    }

    /**
     * Reset find position to beginning.
     */
    public void resetFind() {
        lastFindIndex = 0;
    }
}
