package tech.intac.devtools.cachingproxy;

import java.awt.*;

import javax.swing.*;

public class MsgBox {

    public static void info(Component parent, String message) {
        JOptionPane.showMessageDialog(parent, message, "Info", JOptionPane.INFORMATION_MESSAGE);
    }
}
