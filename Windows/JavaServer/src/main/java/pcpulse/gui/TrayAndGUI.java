package pcpulse.gui;

import pcpulse.auth.AuthManager;

import javax.swing.*;
import java.awt.*;

public class TrayAndGUI {
    private JFrame mainFrame;
    private final Runnable onExit;
    private final String localIp;
    private final AuthManager authManager;
    private JLabel pinLabel;

    public TrayAndGUI(String localIp, AuthManager authManager, Runnable onExit) {
        this.localIp = localIp;
        this.authManager = authManager;
        this.onExit = onExit;
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
            UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
        } catch (Exception e) {}

        mainFrame = new JFrame("PC Pulse Server");
        mainFrame.setSize(340, 300);
        mainFrame.setResizable(false);
        mainFrame.setDefaultCloseOperation(JFrame.HIDE_ON_CLOSE); // Hide to tray
        mainFrame.setLocationRelativeTo(null);
        mainFrame.getContentPane().setBackground(new Color(40, 44, 52));
        mainFrame.setLayout(new BorderLayout());

        JPanel panel = new JPanel();
        panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS));
        panel.setBackground(new Color(40, 44, 52));
        panel.setBorder(BorderFactory.createEmptyBorder(20, 20, 20, 20));

        JLabel titleLabel = new JLabel("PC Pulse Активен");
        titleLabel.setForeground(Color.WHITE);
        titleLabel.setFont(new Font("SansSerif", Font.BOLD, 20));
        titleLabel.setAlignmentX(Component.CENTER_ALIGNMENT);
        
        JLabel ipLabel = new JLabel("<html><center>Введите этот IP в приложении:<br><br><b>" + localIp + "</b></center></html>");
        ipLabel.setForeground(new Color(180, 180, 180));
        ipLabel.setFont(new Font("SansSerif", Font.PLAIN, 14));
        ipLabel.setAlignmentX(Component.CENTER_ALIGNMENT);

        // PIN display
        pinLabel = new JLabel("PIN: " + authManager.getPin());
        pinLabel.setForeground(new Color(100, 200, 100));
        pinLabel.setFont(new Font("SansSerif", Font.BOLD, 22));
        pinLabel.setAlignmentX(Component.CENTER_ALIGNMENT);

        // Buttons panel
        JPanel buttonsPanel = new JPanel(new FlowLayout(FlowLayout.CENTER, 10, 0));
        buttonsPanel.setBackground(new Color(40, 44, 52));

        JButton refreshPinBtn = new JButton("Обновить PIN");
        refreshPinBtn.setFocusPainted(false);
        refreshPinBtn.addActionListener(e -> {
            authManager.regeneratePin();
            pinLabel.setText("PIN: " + authManager.getPin());
        });

        JButton revokeBtn = new JButton("Сбросить устройства");
        revokeBtn.setFocusPainted(false);
        revokeBtn.addActionListener(e -> {
            int confirm = JOptionPane.showConfirmDialog(mainFrame, 
                "Все подключённые устройства будут отключены.\nПродолжить?",
                "Подтверждение", JOptionPane.YES_NO_OPTION);
            if (confirm == JOptionPane.YES_OPTION) {
                authManager.revokeAll();
                JOptionPane.showMessageDialog(mainFrame, "Все устройства сброшены.");
            }
        });

        buttonsPanel.add(refreshPinBtn);
        buttonsPanel.add(revokeBtn);

        panel.add(titleLabel);
        panel.add(Box.createRigidArea(new Dimension(0, 10)));
        panel.add(ipLabel);
        panel.add(Box.createRigidArea(new Dimension(0, 12)));
        panel.add(pinLabel);
        panel.add(Box.createRigidArea(new Dimension(0, 12)));
        panel.add(buttonsPanel);

        mainFrame.add(panel, BorderLayout.CENTER);
        mainFrame.setVisible(true);
    }
}
