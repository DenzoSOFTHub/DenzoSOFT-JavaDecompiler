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
                + "<p>or drag and drop a .jar file onto this window.</p>"
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
                            if (f.getName().endsWith(".jar")) {
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
        chooser.setFileFilter(new javax.swing.filechooser.FileFilter() {
            public boolean accept(File f) {
                return f.isDirectory() || f.getName().endsWith(".jar");
            }
            public String getDescription() {
                return "JAR Files (*.jar)";
            }
        });
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
                    if (args[i].endsWith(".jar")) {
                        gui.openJar(new File(args[i]));
                    }
                }
            }
        });
    }
}
