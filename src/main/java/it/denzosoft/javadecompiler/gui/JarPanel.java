/*
 * This project is distributed under the GPLv3 license.
 * This is a Copyleft license that gives the user the right to use,
 * copy and modify the code freely for non-commercial purposes.
 */
package it.denzosoft.javadecompiler.gui;

import it.denzosoft.javadecompiler.DenzoDecompiler;
import it.denzosoft.javadecompiler.api.loader.Loader;
import it.denzosoft.javadecompiler.api.printer.Printer;

import javax.swing.*;
import javax.swing.event.TreeSelectionEvent;
import javax.swing.event.TreeSelectionListener;
import javax.swing.tree.DefaultMutableTreeNode;
import javax.swing.tree.DefaultTreeCellRenderer;
import javax.swing.tree.DefaultTreeModel;
import javax.swing.tree.TreePath;
import java.awt.*;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

/**
 * Panel for each opened JAR file, showing a tree on the left and editor tabs on the right.
 */
public class JarPanel extends JPanel {

    private JarFile jarFile;
    private File jarFileRef;
    private JTree tree;
    private JTabbedPane editorTabs;
    private JSplitPane splitPane;
    private DecompilerGui parentGui;
    private Map openedTabs; // entryName -> tab index tracking

    public JarPanel(File file, DecompilerGui parentGui) throws IOException {
        this.jarFileRef = file;
        this.jarFile = new JarFile(file);
        this.parentGui = parentGui;
        this.openedTabs = new HashMap();

        setLayout(new BorderLayout());

        editorTabs = new JTabbedPane();
        editorTabs.setTabLayoutPolicy(JTabbedPane.SCROLL_TAB_LAYOUT);

        buildTree();

        JScrollPane treeScroll = new JScrollPane(tree);
        treeScroll.setMinimumSize(new Dimension(250, 100));

        splitPane = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, treeScroll, editorTabs);
        splitPane.setDividerLocation(300);
        splitPane.setOneTouchExpandable(true);

        add(splitPane, BorderLayout.CENTER);
    }

    private void buildTree() {
        DefaultMutableTreeNode root = buildTreeFromJar();
        tree = new JTree(root);
        tree.setRootVisible(true);
        tree.setShowsRootHandles(true);

        // Custom cell renderer for icons
        tree.setCellRenderer(new DefaultTreeCellRenderer() {
            private Icon packageIcon = UIManager.getIcon("FileView.directoryIcon");
            private Icon classIcon = UIManager.getIcon("FileView.fileIcon");
            private Icon jarIcon = UIManager.getIcon("FileView.hardDriveIcon");

            public Component getTreeCellRendererComponent(JTree tree, Object value,
                    boolean sel, boolean expanded, boolean leaf, int row, boolean hasFocus) {
                Component comp = super.getTreeCellRendererComponent(tree, value, sel, expanded, leaf, row, hasFocus);
                if (value instanceof DefaultMutableTreeNode) {
                    Object userObj = ((DefaultMutableTreeNode) value).getUserObject();
                    if (userObj instanceof TreeNodeData) {
                        TreeNodeData data = (TreeNodeData) userObj;
                        if (data.isPackage) {
                            setIcon(packageIcon);
                        } else if (data.isJar) {
                            setIcon(jarIcon != null ? jarIcon : classIcon);
                        } else if (data.isClass) {
                            setIcon(classIcon);
                        } else {
                            setIcon(classIcon);
                        }
                    }
                }
                return comp;
            }
        });

        // Double-click to open
        tree.addMouseListener(new MouseAdapter() {
            public void mouseClicked(MouseEvent e) {
                if (e.getClickCount() == 2) {
                    TreePath path = tree.getPathForLocation(e.getX(), e.getY());
                    if (path != null) {
                        DefaultMutableTreeNode node = (DefaultMutableTreeNode) path.getLastPathComponent();
                        Object userObj = node.getUserObject();
                        if (userObj instanceof TreeNodeData) {
                            TreeNodeData data = (TreeNodeData) userObj;
                            if (!data.isPackage) {
                                openEntry(data.fullPath);
                            }
                        }
                    }
                }
            }
        });
    }

    private DefaultMutableTreeNode buildTreeFromJar() {
        String jarName = jarFileRef.getName();
        DefaultMutableTreeNode root = new DefaultMutableTreeNode(
                new TreeNodeData(jarName, "", true, false, false));

        Map packageNodes = new HashMap(); // String -> DefaultMutableTreeNode

        Enumeration entries = jarFile.entries();
        List entryList = new ArrayList();
        while (entries.hasMoreElements()) {
            JarEntry entry = (JarEntry) entries.nextElement();
            if (!entry.isDirectory()) {
                entryList.add(entry);
            }
        }

        // Sort entries by name
        Collections.sort(entryList, new Comparator() {
            public int compare(Object o1, Object o2) {
                return ((JarEntry) o1).getName().compareTo(((JarEntry) o2).getName());
            }
        });

        for (int i = 0; i < entryList.size(); i++) {
            JarEntry entry = (JarEntry) entryList.get(i);
            String name = entry.getName();

            String pkg = "";
            String fileName = name;
            int lastSlash = name.lastIndexOf('/');
            if (lastSlash >= 0) {
                pkg = name.substring(0, lastSlash);
                fileName = name.substring(lastSlash + 1);
            }

            DefaultMutableTreeNode parent = getOrCreatePackageNode(root, packageNodes, pkg);

            boolean isClass = fileName.endsWith(".class");
            boolean isJar = fileName.endsWith(".jar");
            parent.add(new DefaultMutableTreeNode(
                    new TreeNodeData(fileName, name, false, isClass, isJar)));
        }

        // Sort children: packages first, then files
        sortTreeNode(root);

        return root;
    }

    private DefaultMutableTreeNode getOrCreatePackageNode(DefaultMutableTreeNode root,
            Map packageNodes, String pkg) {
        if (pkg.length() == 0) {
            return root;
        }
        DefaultMutableTreeNode existing = (DefaultMutableTreeNode) packageNodes.get(pkg);
        if (existing != null) {
            return existing;
        }

        int lastSlash = pkg.lastIndexOf('/');
        DefaultMutableTreeNode parentNode;
        String nodeName;
        if (lastSlash >= 0) {
            String parentPkg = pkg.substring(0, lastSlash);
            nodeName = pkg.substring(lastSlash + 1);
            parentNode = getOrCreatePackageNode(root, packageNodes, parentPkg);
        } else {
            nodeName = pkg;
            parentNode = root;
        }

        DefaultMutableTreeNode node = new DefaultMutableTreeNode(
                new TreeNodeData(nodeName, pkg, true, false, false));
        parentNode.add(node);
        packageNodes.put(pkg, node);
        return node;
    }

    private void sortTreeNode(DefaultMutableTreeNode node) {
        if (node.getChildCount() == 0) {
            return;
        }

        List children = new ArrayList();
        for (int i = 0; i < node.getChildCount(); i++) {
            children.add((DefaultMutableTreeNode) node.getChildAt(i));
        }

        Collections.sort(children, new Comparator() {
            public int compare(Object o1, Object o2) {
                DefaultMutableTreeNode n1 = (DefaultMutableTreeNode) o1;
                DefaultMutableTreeNode n2 = (DefaultMutableTreeNode) o2;
                TreeNodeData d1 = (n1.getUserObject() instanceof TreeNodeData) ? (TreeNodeData) n1.getUserObject() : null;
                TreeNodeData d2 = (n2.getUserObject() instanceof TreeNodeData) ? (TreeNodeData) n2.getUserObject() : null;
                if (d1 == null || d2 == null) return 0;
                // Packages first
                if (d1.isPackage && !d2.isPackage) return -1;
                if (!d1.isPackage && d2.isPackage) return 1;
                return d1.name.compareToIgnoreCase(d2.name);
            }
        });

        node.removeAllChildren();
        for (int i = 0; i < children.size(); i++) {
            DefaultMutableTreeNode child = (DefaultMutableTreeNode) children.get(i);
            node.add(child);
            sortTreeNode(child);
        }
    }

    /**
     * Open a JAR entry in an editor tab.
     */
    public void openEntry(String entryName) {
        if (entryName == null || entryName.length() == 0) {
            return;
        }

        // Check if already open
        for (int i = 0; i < editorTabs.getTabCount(); i++) {
            Component comp = editorTabs.getComponentAt(i);
            if (comp instanceof SourcePanel) {
                if (entryName.equals(((SourcePanel) comp).getEntryName())) {
                    editorTabs.setSelectedIndex(i);
                    return;
                }
            }
        }

        // Nested JAR: extract and open as new JAR tab
        if (entryName.endsWith(".jar")) {
            openNestedJar(entryName);
            return;
        }

        // Decompile .class or read text
        String source;
        if (entryName.endsWith(".class")) {
            source = decompileClass(entryName);
        } else {
            source = readEntryAsText(entryName);
        }

        // Get short name for tab title
        String tabTitle = entryName;
        int lastSlash = entryName.lastIndexOf('/');
        if (lastSlash >= 0) {
            tabTitle = entryName.substring(lastSlash + 1);
        }
        if (tabTitle.endsWith(".class")) {
            tabTitle = tabTitle.substring(0, tabTitle.length() - 6) + ".java";
        }

        SourcePanel panel = new SourcePanel(entryName, source);
        editorTabs.addTab(tabTitle, panel);
        int idx = editorTabs.getTabCount() - 1;
        editorTabs.setTabComponentAt(idx, new CloseableTabComponent(editorTabs));
        editorTabs.setSelectedIndex(idx);
    }

    private String decompileClass(String entryName) {
        try {
            final String className = entryName.endsWith(".class")
                    ? entryName.substring(0, entryName.length() - 6)
                    : entryName;

            DenzoDecompiler decompiler = new DenzoDecompiler();
            StringPrinter printer = new StringPrinter();

            Loader loader = new JarLoader();
            decompiler.decompile(loader, printer, className);
            return printer.getResult();
        } catch (Exception e) {
            return "// Error decompiling " + entryName + ": " + e.getMessage();
        }
    }

    private String readEntryAsText(String entryName) {
        try {
            JarEntry entry = jarFile.getJarEntry(entryName);
            if (entry == null) {
                return "// Entry not found: " + entryName;
            }
            InputStream is = jarFile.getInputStream(entry);
            try {
                byte[] data = readAllBytes(is);
                return new String(data, "UTF-8");
            } finally {
                is.close();
            }
        } catch (Exception e) {
            return "// Error reading " + entryName + ": " + e.getMessage();
        }
    }

    private void openNestedJar(String entryName) {
        try {
            JarEntry entry = jarFile.getJarEntry(entryName);
            if (entry == null) return;
            InputStream is = jarFile.getInputStream(entry);
            try {
                byte[] data = readAllBytes(is);
                // Write to temp file
                File tempFile = File.createTempFile("denzosoft_nested_", ".jar");
                tempFile.deleteOnExit();
                FileOutputStream fos = new FileOutputStream(tempFile);
                try {
                    fos.write(data);
                } finally {
                    fos.close();
                }
                parentGui.openJar(tempFile);
            } finally {
                is.close();
            }
        } catch (Exception e) {
            JOptionPane.showMessageDialog(this,
                    "Error opening nested JAR: " + e.getMessage(),
                    "Error", JOptionPane.ERROR_MESSAGE);
        }
    }

    private byte[] readAllBytes(InputStream is) throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        byte[] buf = new byte[4096];
        int n;
        while ((n = is.read(buf)) != -1) {
            baos.write(buf, 0, n);
        }
        return baos.toByteArray();
    }

    /**
     * Get the currently selected SourcePanel, if any.
     */
    public SourcePanel getSelectedSourcePanel() {
        Component comp = editorTabs.getSelectedComponent();
        if (comp instanceof SourcePanel) {
            return (SourcePanel) comp;
        }
        return null;
    }

    /**
     * Close the JAR file when this panel is removed.
     */
    public void close() {
        try {
            jarFile.close();
        } catch (IOException e) {
            // ignore
        }
    }

    /**
     * Loader implementation that loads classes from the opened JAR.
     */
    private class JarLoader implements Loader {
        public boolean canLoad(String internalName) {
            return jarFile.getJarEntry(internalName + ".class") != null;
        }

        public byte[] load(String internalName) throws Exception {
            JarEntry entry = jarFile.getJarEntry(internalName + ".class");
            if (entry == null) return null;
            InputStream is = jarFile.getInputStream(entry);
            try {
                return readAllBytes(is);
            } finally {
                is.close();
            }
        }
    }

    /**
     * Simple Printer that collects output into a String.
     */
    static class StringPrinter implements Printer {
        private final StringBuffer sb = new StringBuffer();
        private int indentLevel = 0;
        private static final String INDENT = "    ";

        public void start(int maxLineNumber, int majorVersion, int minorVersion) {}
        public void end() {}
        public void printText(String text) { sb.append(text); }
        public void printNumericConstant(String constant) { sb.append(constant); }
        public void printStringConstant(String constant, String ownerInternalName) { sb.append(constant); }
        public void printKeyword(String keyword) { sb.append(keyword); }

        public void printDeclaration(int type, String internalTypeName, String name, String descriptor) {
            sb.append(name);
        }

        public void printReference(int type, String internalTypeName, String name, String descriptor, String ownerInternalName) {
            sb.append(name);
        }

        public void indent() { indentLevel++; }
        public void unindent() { indentLevel--; }

        public void startLine(int lineNumber) {
            for (int i = 0; i < indentLevel; i++) {
                sb.append(INDENT);
            }
        }

        public void endLine() { sb.append("\n"); }

        public void extraLine(int count) {
            for (int i = 0; i < count; i++) sb.append("\n");
        }

        public void startMarker(int type) {}
        public void endMarker(int type) {}

        public String getResult() { return sb.toString(); }
    }
}
