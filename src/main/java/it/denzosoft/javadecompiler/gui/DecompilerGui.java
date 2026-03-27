/*
 * This project is distributed under the GPLv3 license.
 * This is a Copyleft license that gives the user the right to use,
 * copy and modify the code freely for non-commercial purposes.
 */
package it.denzosoft.javadecompiler.gui;

import it.denzosoft.javadecompiler.DenzoDecompiler;

import javax.swing.*;
import java.awt.*;
import java.awt.datatransfer.DataFlavor;
import java.awt.datatransfer.Transferable;
import java.awt.dnd.DnDConstants;
import java.awt.dnd.DropTarget;
import java.awt.dnd.DropTargetAdapter;
import java.awt.dnd.DropTargetDropEvent;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.awt.event.KeyEvent;
import java.io.File;
import java.util.List;

/**
 * Main GUI application for the DenzoSOFT Java Decompiler.
 * Provides a JD-GUI-inspired interface for browsing and decompiling JAR files.
 */
public class DecompilerGui extends JFrame {

    private JTabbedPane mainTabs;
    private JMenuBar menuBar;
    private JToolBar toolBar;
    // START_CHANGE: IMP-2026-0007-20260327-1 - GUI options for decompiler flags
    private boolean optCompact = false;
    private boolean optShowBytecode = false;
    private boolean optShowNativeInfo = false;
    private boolean optDeobfuscate = false;

    /** Build configuration map from current GUI options. */
    public java.util.Map<String, Object> getDecompilerConfig() {
        java.util.Map<String, Object> config = new java.util.HashMap<String, Object>();
        if (optShowBytecode) config.put("showBytecode", Boolean.TRUE);
        if (optShowNativeInfo) config.put("showNativeInfo", Boolean.TRUE);
        if (optDeobfuscate) config.put("deobfuscate", Boolean.TRUE);
        return config.isEmpty() ? null : config;
    }

    /** Returns true if compact (no line alignment) mode is selected. */
    public boolean isCompactMode() { return optCompact; }
    // END_CHANGE: IMP-2026-0007-1

    public DecompilerGui() {
        setTitle("DenzoSOFT Java Decompiler v" + DenzoDecompiler.getVersion());
        setSize(1200, 800);
        setDefaultCloseOperation(EXIT_ON_CLOSE);
        setLocationRelativeTo(null);

        initMenuBar();
        initToolBar();
        initContent();
        initDragAndDrop();
    }

    private void initMenuBar() {
        menuBar = new JMenuBar();

        // File menu
        JMenu fileMenu = new JMenu("File");
        fileMenu.setMnemonic(KeyEvent.VK_F);

        JMenuItem openItem = new JMenuItem("Open JAR...");
        openItem.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_O, KeyEvent.CTRL_MASK));
        openItem.addActionListener(new ActionListener() {
            public void actionPerformed(ActionEvent e) {
                doOpenJar();
            }
        });
        fileMenu.add(openItem);

        JMenuItem closeTabItem = new JMenuItem("Close Tab");
        closeTabItem.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_W, KeyEvent.CTRL_MASK));
        closeTabItem.addActionListener(new ActionListener() {
            public void actionPerformed(ActionEvent e) {
                doCloseTab();
            }
        });
        fileMenu.add(closeTabItem);

        fileMenu.addSeparator();

        JMenuItem exitItem = new JMenuItem("Exit");
        exitItem.addActionListener(new ActionListener() {
            public void actionPerformed(ActionEvent e) {
                dispose();
                System.exit(0);
            }
        });
        fileMenu.add(exitItem);

        menuBar.add(fileMenu);

        // Edit menu
        JMenu editMenu = new JMenu("Edit");
        editMenu.setMnemonic(KeyEvent.VK_E);

        JMenuItem findItem = new JMenuItem("Find...");
        findItem.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_F, KeyEvent.CTRL_MASK));
        findItem.addActionListener(new ActionListener() {
            public void actionPerformed(ActionEvent e) {
                doFind();
            }
        });
        editMenu.add(findItem);

        JMenuItem copyItem = new JMenuItem("Copy");
        copyItem.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_C, KeyEvent.CTRL_MASK));
        copyItem.addActionListener(new ActionListener() {
            public void actionPerformed(ActionEvent e) {
                doCopy();
            }
        });
        editMenu.add(copyItem);

        JMenuItem selectAllItem = new JMenuItem("Select All");
        selectAllItem.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_A, KeyEvent.CTRL_MASK));
        selectAllItem.addActionListener(new ActionListener() {
            public void actionPerformed(ActionEvent e) {
                doSelectAll();
            }
        });
        editMenu.add(selectAllItem);

        menuBar.add(editMenu);

        // START_CHANGE: IMP-2026-0007-20260327-2 - Options menu with decompiler flags
        JMenu optionsMenu = new JMenu("Options");
        optionsMenu.setMnemonic(KeyEvent.VK_P);

        final JCheckBoxMenuItem compactItem = new JCheckBoxMenuItem("Compact Output");
        compactItem.setToolTipText("Remove line number alignment blank lines");
        compactItem.addActionListener(new ActionListener() {
            public void actionPerformed(ActionEvent e) { optCompact = compactItem.isSelected(); }
        });
        optionsMenu.add(compactItem);

        final JCheckBoxMenuItem bytecodeItem = new JCheckBoxMenuItem("Show Bytecode Info");
        bytecodeItem.setToolTipText("Show bytecode metadata in method bodies");
        bytecodeItem.addActionListener(new ActionListener() {
            public void actionPerformed(ActionEvent e) { optShowBytecode = bytecodeItem.isSelected(); }
        });
        optionsMenu.add(bytecodeItem);

        final JCheckBoxMenuItem nativeItem = new JCheckBoxMenuItem("Show Native Method Info");
        nativeItem.setToolTipText("Show JNI function names on native methods");
        nativeItem.addActionListener(new ActionListener() {
            public void actionPerformed(ActionEvent e) { optShowNativeInfo = nativeItem.isSelected(); }
        });
        optionsMenu.add(nativeItem);

        final JCheckBoxMenuItem deobfItem = new JCheckBoxMenuItem("Deobfuscate");
        deobfItem.setToolTipText("Sanitize obfuscated identifiers for compilable output");
        deobfItem.addActionListener(new ActionListener() {
            public void actionPerformed(ActionEvent e) { optDeobfuscate = deobfItem.isSelected(); }
        });
        optionsMenu.add(deobfItem);

        menuBar.add(optionsMenu);
        // END_CHANGE: IMP-2026-0007-2

        // Help menu
        JMenu helpMenu = new JMenu("Help");
        helpMenu.setMnemonic(KeyEvent.VK_H);

        JMenuItem aboutItem = new JMenuItem("About");
        aboutItem.addActionListener(new ActionListener() {
            public void actionPerformed(ActionEvent e) {
                doAbout();
            }
        });
        helpMenu.add(aboutItem);

        menuBar.add(helpMenu);

        setJMenuBar(menuBar);
    }

    private void initToolBar() {
        toolBar = new JToolBar();
        toolBar.setFloatable(false);

        JButton openBtn = new JButton("Open JAR");
        openBtn.setToolTipText("Open a JAR file (Ctrl+O)");
        openBtn.addActionListener(new ActionListener() {
            public void actionPerformed(ActionEvent e) {
                doOpenJar();
            }
        });
        toolBar.add(openBtn);

        toolBar.addSeparator();

        JButton findBtn = new JButton("Find");
        findBtn.setToolTipText("Find text in current tab (Ctrl+F)");
        findBtn.addActionListener(new ActionListener() {
            public void actionPerformed(ActionEvent e) {
                doFind();
            }
        });
        toolBar.add(findBtn);

        add(toolBar, BorderLayout.NORTH);
    }

    private void initContent() {
        mainTabs = new JTabbedPane();
        mainTabs.setTabLayoutPolicy(JTabbedPane.SCROLL_TAB_LAYOUT);

        // Welcome panel when no JAR is open
        JPanel welcomePanel = new JPanel(new BorderLayout());
        JLabel welcomeLabel = new JLabel(
                "<html><center><h1>DenzoSOFT Java Decompiler</h1>"
                + "<p>Open a JAR file using File &rarr; Open JAR (Ctrl+O)</p>"
                + "<p>or drag and drop a .jar/.war/.ear/.apk file onto this window.</p>"
                + "<br/><p style='color:gray'>Version " + DenzoDecompiler.getVersion() + "</p></center></html>",
                SwingConstants.CENTER);
        welcomePanel.add(welcomeLabel, BorderLayout.CENTER);
        mainTabs.addTab("Welcome", welcomePanel);

        add(mainTabs, BorderLayout.CENTER);
    }

    private void initDragAndDrop() {
        new DropTarget(this, new DropTargetAdapter() {
            public void drop(DropTargetDropEvent event) {
                try {
                    event.acceptDrop(DnDConstants.ACTION_COPY);
                    Transferable t = event.getTransferable();
                    if (t.isDataFlavorSupported(DataFlavor.javaFileListFlavor)) {
                        List files = (List) t.getTransferData(DataFlavor.javaFileListFlavor);
                        for (int i = 0; i < files.size(); i++) {
                            File f = (File) files.get(i);
                            String fn = f.getName().toLowerCase();
                            if (fn.endsWith(".jar") || fn.endsWith(".war") || fn.endsWith(".ear") || fn.endsWith(".apk")) {
                                openJar(f);
                            }
                        }
                    }
                    event.dropComplete(true);
                } catch (Exception e) {
                    event.dropComplete(false);
                }
            }
        });
    }

    /**
     * Open a JAR file in a new tab.
     */
    public void openJar(File jarFile) {
        try {
            // Remove welcome tab if present
            if (mainTabs.getTabCount() == 1 && "Welcome".equals(mainTabs.getTitleAt(0))) {
                Component comp = mainTabs.getComponentAt(0);
                if (!(comp instanceof JarPanel)) {
                    mainTabs.removeTabAt(0);
                }
            }

            JarPanel panel = new JarPanel(jarFile, this);
            String title = jarFile.getName();
            mainTabs.addTab(title, panel);
            int idx = mainTabs.getTabCount() - 1;
            mainTabs.setTabComponentAt(idx, new CloseableTabComponent(mainTabs));
            mainTabs.setSelectedIndex(idx);
        } catch (Exception e) {
            JOptionPane.showMessageDialog(this,
                    "Error opening JAR: " + e.getMessage(),
                    "Error", JOptionPane.ERROR_MESSAGE);
        }
    }

    private void doOpenJar() {
        JFileChooser chooser = new JFileChooser();
        // START_CHANGE: IMP-2026-0010-20260327-4 - Support JAR, WAR, EAR, APK files
        chooser.setFileFilter(new javax.swing.filechooser.FileFilter() {
            public boolean accept(File f) {
                if (f.isDirectory()) return true;
                String name = f.getName().toLowerCase();
                return name.endsWith(".jar") || name.endsWith(".war")
                    || name.endsWith(".ear") || name.endsWith(".apk");
            }
            public String getDescription() {
                return "Archives (*.jar, *.war, *.ear, *.apk)";
            }
        });
        // END_CHANGE: IMP-2026-0010-4
        chooser.setMultiSelectionEnabled(true);
        int result = chooser.showOpenDialog(this);
        if (result == JFileChooser.APPROVE_OPTION) {
            File[] files = chooser.getSelectedFiles();
            for (int i = 0; i < files.length; i++) {
                openJar(files[i]);
            }
        }
    }

    private void doCloseTab() {
        int idx = mainTabs.getSelectedIndex();
        if (idx >= 0) {
            Component comp = mainTabs.getComponentAt(idx);
            if (comp instanceof JarPanel) {
                ((JarPanel) comp).close();
            }
            mainTabs.removeTabAt(idx);
        }
    }

    private void doFind() {
        Component comp = mainTabs.getSelectedComponent();
        if (!(comp instanceof JarPanel)) {
            return;
        }
        JarPanel jarPanel = (JarPanel) comp;
        SourcePanel sourcePanel = jarPanel.getSelectedSourcePanel();
        if (sourcePanel == null) {
            JOptionPane.showMessageDialog(this, "No source tab is open.", "Find", JOptionPane.INFORMATION_MESSAGE);
            return;
        }

        String searchText = JOptionPane.showInputDialog(this, "Find text:", "Find", JOptionPane.PLAIN_MESSAGE);
        if (searchText != null && searchText.length() > 0) {
            sourcePanel.resetFind();
            boolean found = sourcePanel.find(searchText);
            if (!found) {
                JOptionPane.showMessageDialog(this, "Text not found: " + searchText, "Find", JOptionPane.INFORMATION_MESSAGE);
            }
        }
    }

    private void doCopy() {
        Component comp = mainTabs.getSelectedComponent();
        if (comp instanceof JarPanel) {
            SourcePanel sp = ((JarPanel) comp).getSelectedSourcePanel();
            if (sp != null) {
                sp.getTextPane().copy();
            }
        }
    }

    private void doSelectAll() {
        Component comp = mainTabs.getSelectedComponent();
        if (comp instanceof JarPanel) {
            SourcePanel sp = ((JarPanel) comp).getSelectedSourcePanel();
            if (sp != null) {
                sp.getTextPane().selectAll();
            }
        }
    }

    private void doAbout() {
        JOptionPane.showMessageDialog(this,
                "DenzoSOFT Java Decompiler v" + DenzoDecompiler.getVersion() + "\n\n"
                + "A Java bytecode decompiler supporting Java 1.0 through Java "
                + DenzoDecompiler.getMaxSupportedJavaVersion() + ".\n\n"
                + "Licensed under GPLv3.",
                "About",
                JOptionPane.INFORMATION_MESSAGE);
    }

    /**
     * Application entry point for the GUI.
     */
    public static void main(final String[] args) {
        SwingUtilities.invokeLater(new Runnable() {
            public void run() {
                try {
                    UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
                } catch (Exception e) {
                    // Use default L&F
                }
                DecompilerGui gui = new DecompilerGui();
                gui.setVisible(true);
                for (int i = 0; i < args.length; i++) {
                    String argLower = args[i].toLowerCase();
                    if (argLower.endsWith(".jar") || argLower.endsWith(".war") || argLower.endsWith(".ear") || argLower.endsWith(".apk")) {
                        gui.openJar(new File(args[i]));
                    }
                }
            }
        });
    }
}
