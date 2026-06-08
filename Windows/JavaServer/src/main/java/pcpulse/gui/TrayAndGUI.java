package pcpulse.gui;

import pcpulse.auth.AuthManager;

import javax.swing.*;
import java.awt.*;

public class TrayAndGUI {
    private JFrame frame;
    private final Runnable onExit;
    private final String localIp;
    private final AuthManager auth;
    private final Runnable onRevoke;
    private JLabel pinLabel;

    private static final Color BG = new Color(28, 30, 34);

    public TrayAndGUI(String localIp, AuthManager auth, Runnable onExit, Runnable onRevoke) {
        this.localIp = localIp;
        this.auth = auth;
        this.onExit = onExit;
        this.onRevoke = onRevoke;
    }

    public void init() {
        setupTray();
        setupWindow();
    }

    private void setupTray() {
        if (!SystemTray.isSupported()) return;
        try {
            SystemTray tray = SystemTray.getSystemTray();

            // заглушка-иконка 16x16, потом можно заменить на нормальную
            var img = new java.awt.image.BufferedImage(16, 16, java.awt.image.BufferedImage.TYPE_INT_ARGB);
            Graphics2D g = img.createGraphics();
            g.setColor(Color.BLUE);
            g.fillRect(0, 0, 16, 16);
            g.dispose();

            TrayIcon icon = new TrayIcon(img, "PC Pulse Server");
            icon.setImageAutoSize(true);
            icon.addActionListener(e -> {
                if (frame != null) {
                    frame.setVisible(true);
                    frame.setExtendedState(JFrame.NORMAL);
                }
            });

            PopupMenu popup = new PopupMenu();
            MenuItem exit = new MenuItem("Exit");
            exit.addActionListener(e -> {
                if (onExit != null) onExit.run();
                System.exit(0);
            });
            popup.add(exit);
            icon.setPopupMenu(popup);
            tray.add(icon);
        } catch (Exception e) {
            // SystemTray глючит на некоторых JDK — не критично
            System.err.println("[GUI] Tray icon не встал: " + e.getMessage());
        }
    }

    private void setupWindow() {
        try {
            UIManager.setLookAndFeel(UIManager.getCrossPlatformLookAndFeelClassName());
        } catch (Exception ignored) {}

        frame = new JFrame("PC Pulse Server");
        frame.setSize(380, 360);
        frame.setResizable(false);
        frame.setDefaultCloseOperation(JFrame.HIDE_ON_CLOSE);
        frame.setLocationRelativeTo(null);
        frame.getContentPane().setBackground(BG);
        frame.setLayout(new BorderLayout());

        JPanel panel = new JPanel();
        panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS));
        panel.setBackground(BG);
        panel.setBorder(BorderFactory.createEmptyBorder(25, 30, 25, 30));

        JLabel title = styledLabel("PC Pulse Активен", new Color(240, 240, 240), new Font("Segoe UI", Font.BOLD, 22));
        JLabel ipHint = styledLabel("IP-АДРЕС ДЛЯ ПОДКЛЮЧЕНИЯ", new Color(130, 135, 140), new Font("Segoe UI", Font.BOLD, 11));
        JLabel ipVal = styledLabel(localIp, new Color(88, 166, 255), new Font("Segoe UI", Font.BOLD, 18));
        JLabel pinHint = styledLabel("РАЗОВЫЙ PIN-КОД", new Color(130, 135, 140), new Font("Segoe UI", Font.BOLD, 11));

        pinLabel = styledLabel(auth.getPin(), new Color(80, 200, 120), new Font("Consolas", Font.BOLD, 46));

        JPanel btns = new JPanel(new GridLayout(1, 2, 15, 0));
        btns.setBackground(BG);
        btns.setMaximumSize(new Dimension(320, 42));

        ModernButton refreshBtn = new ModernButton("Обновить PIN");
        refreshBtn.addActionListener(e -> {
            auth.regeneratePin();
            pinLabel.setText(auth.getPin());
        });

        ModernButton revokeBtn = new ModernButton("Сбросить связи");
        revokeBtn.setBaseColor(new Color(170, 60, 60));
        revokeBtn.addActionListener(e -> {
            int ok = JOptionPane.showConfirmDialog(frame,
                "Все подключённые устройства будут отключены.\nПродолжить?",
                "Сброс устройств", JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE);
            if (ok == JOptionPane.YES_OPTION) {
                auth.revokeAll();
                if (onRevoke != null) onRevoke.run();
                JOptionPane.showMessageDialog(frame, "Все устройства отключены.", "Успешно", JOptionPane.INFORMATION_MESSAGE);
            }
        });

        btns.add(refreshBtn);
        btns.add(revokeBtn);

        panel.add(title);
        panel.add(Box.createRigidArea(new Dimension(0, 25)));
        panel.add(ipHint);
        panel.add(Box.createRigidArea(new Dimension(0, 4)));
        panel.add(ipVal);
        panel.add(Box.createRigidArea(new Dimension(0, 20)));
        panel.add(pinHint);
        panel.add(Box.createRigidArea(new Dimension(0, 4)));
        panel.add(pinLabel);
        panel.add(Box.createVerticalGlue());
        panel.add(btns);

        frame.add(panel, BorderLayout.CENTER);
        frame.setVisible(true);
    }

    private JLabel styledLabel(String text, Color fg, Font font) {
        JLabel lbl = new JLabel(text);
        lbl.setForeground(fg);
        lbl.setFont(font);
        lbl.setAlignmentX(Component.CENTER_ALIGNMENT);
        return lbl;
    }

    static class ModernButton extends JButton {
        private Color baseColor = new Color(60, 65, 70);

        public ModernButton(String text) {
            super(text);
            setFont(new Font("Segoe UI", Font.BOLD, 13));
            setForeground(Color.WHITE);
            setFocusPainted(false);
            setBorderPainted(false);
            setContentAreaFilled(false);
            setOpaque(false);
            setCursor(new Cursor(Cursor.HAND_CURSOR));

            addMouseListener(new java.awt.event.MouseAdapter() {
                public void mouseEntered(java.awt.event.MouseEvent e) { repaint(); }
                public void mouseExited(java.awt.event.MouseEvent e) { repaint(); }
                public void mousePressed(java.awt.event.MouseEvent e) { repaint(); }
                public void mouseReleased(java.awt.event.MouseEvent e) { repaint(); }
            });
        }

        public void setBaseColor(Color c) {
            this.baseColor = c;
            repaint();
        }

        @Override
        protected void paintComponent(Graphics g) {
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

            Color c = baseColor;
            ButtonModel m = getModel();
            if (m.isPressed()) c = c.darker();
            else if (m.isRollover()) c = c.brighter();

            g2.setColor(c);
            g2.fillRoundRect(0, 0, getWidth(), getHeight(), 12, 12);
            g2.dispose();
            super.paintComponent(g);
        }
    }
}
