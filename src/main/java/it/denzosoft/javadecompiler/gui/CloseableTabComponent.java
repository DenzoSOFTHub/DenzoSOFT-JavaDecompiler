/*
 * This project is distributed under the GPLv3 license.
 * This is a Copyleft license that gives the user the right to use,
 * copy and modify the code freely for non-commercial purposes.
 */
package it.denzosoft.javadecompiler.gui;

import javax.swing.*;
import java.awt.*;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;

/**
 * Tab component with a close button (X) for use in JTabbedPane.
 */
public class CloseableTabComponent extends JPanel {

    public CloseableTabComponent(final JTabbedPane tabs) {
        super(new FlowLayout(FlowLayout.LEFT, 0, 0));
        setOpaque(false);

        JLabel label = new JLabel() {
            public String getText() {
                int i = tabs.indexOfTabComponent(CloseableTabComponent.this);
                if (i >= 0) {
                    return tabs.getTitleAt(i);
                }
                return "";
            }
        };
        label.setBorder(BorderFactory.createEmptyBorder(0, 0, 0, 5));
        add(label);

        JButton closeBtn = new JButton("x");
        closeBtn.setFont(new Font("Dialog", Font.PLAIN, 10));
        closeBtn.setPreferredSize(new Dimension(17, 17));
        closeBtn.setMargin(new Insets(0, 0, 0, 0));
        closeBtn.setBorderPainted(false);
        closeBtn.setContentAreaFilled(false);
        closeBtn.setFocusable(false);
        closeBtn.setToolTipText("Close this tab");
        closeBtn.addActionListener(new ActionListener() {
            public void actionPerformed(ActionEvent e) {
                int i = tabs.indexOfTabComponent(CloseableTabComponent.this);
                if (i >= 0) {
                    tabs.removeTabAt(i);
                }
            }
        });
        add(closeBtn);
    }
}
