package pcpulse.gui;

import javax.swing.*;
import java.awt.*;

public class TrayAndGUI {
    private JFrame mainFrame;
    private final Runnable onExit;
    private final String localIp;

    public TrayAndGUI(String localIp, Runnable onExit) {
        this.localIp = localIp;
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
        mainFrame.setSize(300, 180);
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

        panel.add(titleLabel);
        panel.add(Box.createRigidArea(new Dimension(0, 15)));
        panel.add(ipLabel);

        mainFrame.add(panel, BorderLayout.CENTER);
        mainFrame.setVisible(true);
    }
}
