package pcpulse.gui;

import pcpulse.auth.AuthManager;

import javax.swing.*;
import java.awt.*;

public class TrayAndGUI {
    private JFrame mainFrame;
    private final Runnable onExit;
    private final String localIp;
    private final AuthManager authManager;
    private final Runnable onRevoke;
    private JLabel pinLabel;

    public TrayAndGUI(String localIp, AuthManager authManager, Runnable onExit, Runnable onRevoke) {
        this.localIp = localIp;
        this.authManager = authManager;
        this.onExit = onExit;
        this.onRevoke = onRevoke;
    }

    public void init() {
        setupTrayIcon();
        setupGUI();
    }

    private void setupTrayIcon() {
        if (!SystemTray.isSupported()) return;
        try {
            SystemTray tray = SystemTray.getSystemTray();
            java.awt.image.BufferedImage img = new java.awt.image.BufferedImage(16, 16, java.awt.image.BufferedImage.TYPE_INT_ARGB);
            Graphics2D g = img.createGraphics();
            g.setColor(Color.BLUE);
            g.fillRect(0,0,16,16);
            g.dispose();
            
            TrayIcon trayIcon = new TrayIcon(img, "PC Pulse Server");
            trayIcon.setImageAutoSize(true);
            trayIcon.addActionListener(e -> {
                if (mainFrame != null) {
                    mainFrame.setVisible(true);
                    mainFrame.setExtendedState(JFrame.NORMAL);
                }
            });
            
            PopupMenu popup = new PopupMenu();
            MenuItem exitItem = new MenuItem("Exit");
            exitItem.addActionListener(e -> {
                if (onExit != null) onExit.run();
                System.exit(0);
            });
            popup.add(exitItem);
            trayIcon.setPopupMenu(popup);
            tray.add(trayIcon);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private void setupGUI() {
        try {
            UIManager.setLookAndFeel(UIManager.getCrossPlatformLookAndFeelClassName());
        } catch (Exception e) {}

        mainFrame = new JFrame("PC Pulse Server");
        mainFrame.setSize(380, 360);
        mainFrame.setResizable(false);
        mainFrame.setDefaultCloseOperation(JFrame.HIDE_ON_CLOSE); // Hide to tray
        mainFrame.setLocationRelativeTo(null);
        
        Color bgDark = new Color(28, 30, 34); // Sleek dark gray
        mainFrame.getContentPane().setBackground(bgDark);
        mainFrame.setLayout(new BorderLayout());

        JPanel panel = new JPanel();
        panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS));
        panel.setBackground(bgDark);
        panel.setBorder(BorderFactory.createEmptyBorder(25, 30, 25, 30));

        JLabel titleLabel = new JLabel("PC Pulse Активен");
        titleLabel.setForeground(new Color(240, 240, 240));
        titleLabel.setFont(new Font("Segoe UI", Font.BOLD, 22));
        titleLabel.setAlignmentX(Component.CENTER_ALIGNMENT);

        JLabel instructionLabel = new JLabel("IP-АДРЕС ДЛЯ ПОДКЛЮЧЕНИЯ");
        instructionLabel.setForeground(new Color(130, 135, 140));
        instructionLabel.setFont(new Font("Segoe UI", Font.BOLD, 11));
        instructionLabel.setAlignmentX(Component.CENTER_ALIGNMENT);

        JLabel ipValueLabel = new JLabel(localIp);
        ipValueLabel.setForeground(new Color(88, 166, 255)); // Soft blue
        ipValueLabel.setFont(new Font("Segoe UI", Font.BOLD, 18));
        ipValueLabel.setAlignmentX(Component.CENTER_ALIGNMENT);

        JLabel pinTitleLabel = new JLabel("РАЗОВЫЙ PIN-КОД");
        pinTitleLabel.setForeground(new Color(130, 135, 140));
        pinTitleLabel.setFont(new Font("Segoe UI", Font.BOLD, 11));
        pinTitleLabel.setAlignmentX(Component.CENTER_ALIGNMENT);

        pinLabel = new JLabel(authManager.getPin());
        pinLabel.setForeground(new Color(80, 200, 120)); // Soft vibrant green
        pinLabel.setFont(new Font("Consolas", Font.BOLD, 46)); // Big monospace numbers
        pinLabel.setAlignmentX(Component.CENTER_ALIGNMENT);

        JPanel buttonsPanel = new JPanel(new GridLayout(1, 2, 15, 0));
        buttonsPanel.setBackground(bgDark);
        buttonsPanel.setMaximumSize(new Dimension(320, 42));

        ModernButton refreshPinBtn = new ModernButton("Обновить PIN");
        refreshPinBtn.addActionListener(e -> {
            authManager.regeneratePin();
            pinLabel.setText(authManager.getPin());
        });

        ModernButton revokeBtn = new ModernButton("Сбросить связи");
        revokeBtn.setBaseColor(new Color(170, 60, 60)); // Soft red
        revokeBtn.addActionListener(e -> {
            int confirm = JOptionPane.showConfirmDialog(mainFrame, 
                "Все подключённые устройства будут отключены.\nПродолжить?",
                "Сброс устройств", JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE);
            if (confirm == JOptionPane.YES_OPTION) {
                authManager.revokeAll();
                if (onRevoke != null) onRevoke.run();
                JOptionPane.showMessageDialog(mainFrame, "Все устройства отключены.", "Успешно", JOptionPane.INFORMATION_MESSAGE);
            }
        });

        buttonsPanel.add(refreshPinBtn);
        buttonsPanel.add(revokeBtn);

        panel.add(titleLabel);
        panel.add(Box.createRigidArea(new Dimension(0, 25)));
        panel.add(instructionLabel);
        panel.add(Box.createRigidArea(new Dimension(0, 4)));
        panel.add(ipValueLabel);
        panel.add(Box.createRigidArea(new Dimension(0, 20)));
        panel.add(pinTitleLabel);
        panel.add(Box.createRigidArea(new Dimension(0, 4)));
        panel.add(pinLabel);
        panel.add(Box.createVerticalGlue());
        panel.add(buttonsPanel);

        mainFrame.add(panel, BorderLayout.CENTER);
        mainFrame.setVisible(true);
    }

    // Custom UI element for a modern button
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
                public void mouseEntered(java.awt.event.MouseEvent evt) { repaint(); }
                public void mouseExited(java.awt.event.MouseEvent evt) { repaint(); }
                public void mousePressed(java.awt.event.MouseEvent evt) { repaint(); }
                public void mouseReleased(java.awt.event.MouseEvent evt) { repaint(); }
            });
        }
        
        public void setBaseColor(Color color) {
            this.baseColor = color;
            repaint();
        }

        @Override
        protected void paintComponent(Graphics g) {
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            
            Color currentColor = baseColor;
            ButtonModel model = getModel();
            if (model.isPressed()) {
                currentColor = currentColor.darker();
            } else if (model.isRollover()) {
                currentColor = currentColor.brighter();
            }
            
            g2.setColor(currentColor);
            g2.fillRoundRect(0, 0, getWidth(), getHeight(), 12, 12); // Modern slightly rounded corners
            g2.dispose();
            super.paintComponent(g);
        }
    }
}
