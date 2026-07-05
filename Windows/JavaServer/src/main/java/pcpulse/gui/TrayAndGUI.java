package pcpulse.gui;

import pcpulse.auth.AuthManager;

import javax.swing.*;
import java.awt.*;
import java.awt.geom.RoundRectangle2D;
import java.util.prefs.Preferences;

public class TrayAndGUI {
    private JFrame frame;
    private final Runnable onExit;
    private volatile String localIp;
    private final AuthManager auth;
    private final Runnable onRevoke;
    private JLabel pinLabel;
    private JLabel ipVal;
    private JPanel specialEditionPanel;
    private static final String PREF_KEY_SHOW_LABEL = "show_special_edition_label";
    private final Preferences prefs = Preferences.userNodeForPackage(TrayAndGUI.class);

    private static final Color BG = new Color(28, 30, 34);

    public TrayAndGUI(String localIp, AuthManager auth, Runnable onExit, Runnable onRevoke) {
        this.localIp = localIp;
        this.auth = auth;
        this.onExit = onExit;
        this.onRevoke = onRevoke;
    }

    public void init() {
        setupTray();
    }

    public void showWindow() {
        try {
            String exePath = pcpulse.ServerApp.getRealExePath();
            if (exePath != null && !exePath.isEmpty() && new java.io.File(exePath).exists()) {
                new ProcessBuilder(exePath, "--ui").start();
                return;
            }
            java.io.File launcherExe = new java.io.File("launcher.exe");
            java.io.File pcPulseExe = new java.io.File("PC Pulse.exe");
            if (pcPulseExe.exists()) {
                new ProcessBuilder("PC Pulse.exe", "--ui").start();
            } else if (launcherExe.exists()) {
                new ProcessBuilder("launcher.exe", "--ui").start();
            } else {
                new ProcessBuilder("python", "launcher.py", "--ui").start();
            }
        } catch (Exception e) {
            System.err.println("[GUI] Failed to launch modern UI: " + e.getMessage());
        }
    }

    public void updateIp(String newIp) {
        if (newIp != null && !newIp.equals(this.localIp)) {
            this.localIp = newIp;
            SwingUtilities.invokeLater(() -> {
                if (ipVal != null) ipVal.setText(newIp);
            });
        }
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
            icon.addActionListener(e -> showWindow());

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

        JPanel headerPanel = new JPanel();
        headerPanel.setLayout(new BoxLayout(headerPanel, BoxLayout.X_AXIS));
        headerPanel.setBackground(BG);
        headerPanel.setMaximumSize(new Dimension(380, 36));
        headerPanel.setAlignmentX(Component.CENTER_ALIGNMENT);

        headerPanel.add(Box.createRigidArea(new Dimension(30, 30)));
        headerPanel.add(Box.createHorizontalGlue());

        JLabel title = styledLabel("PC Pulse Активен", new Color(240, 240, 240), new Font("Segoe UI", Font.BOLD, 22));
        headerPanel.add(title);

        headerPanel.add(Box.createHorizontalGlue());

        StarButton starBtn = new StarButton();
        starBtn.setToolTipText("Информация о специальной версии");
        starBtn.addActionListener(e -> showSpecialEditionDialog());
        headerPanel.add(starBtn);
        JLabel ipHint = styledLabel("IP-АДРЕС ДЛЯ ПОДКЛЮЧЕНИЯ", new Color(130, 135, 140), new Font("Segoe UI", Font.BOLD, 11));
        ipVal = styledLabel(localIp, new Color(88, 166, 255), new Font("Segoe UI", Font.BOLD, 18));
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
                pinLabel.setText(auth.getPin());
                if (onRevoke != null) onRevoke.run();
                JOptionPane.showMessageDialog(frame, "Все устройства отключены.", "Успешно", JOptionPane.INFORMATION_MESSAGE);
            }
        });

        btns.add(refreshBtn);
        btns.add(revokeBtn);

        panel.add(headerPanel);
        panel.add(Box.createRigidArea(new Dimension(0, 20)));
        panel.add(ipHint);
        panel.add(Box.createRigidArea(new Dimension(0, 4)));
        panel.add(ipVal);
        panel.add(Box.createRigidArea(new Dimension(0, 15)));
        panel.add(pinHint);
        panel.add(Box.createRigidArea(new Dimension(0, 4)));
        panel.add(pinLabel);
        panel.add(Box.createVerticalGlue());
        panel.add(btns);

        specialEditionPanel = createSpecialEditionMainPanel();
        panel.add(specialEditionPanel);
        updateSpecialEditionVisibility();

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

    private JPanel createSpecialEditionMainPanel() {
        JPanel p = new JPanel();
        p.setLayout(new BoxLayout(p, BoxLayout.Y_AXIS));
        p.setBackground(BG);
        p.setAlignmentX(Component.CENTER_ALIGNMENT);

        p.add(Box.createRigidArea(new Dimension(0, 15)));

        JPanel sep = new JPanel() {
            @Override
            protected void paintComponent(Graphics g) {
                super.paintComponent(g);
                Graphics2D g2 = (Graphics2D) g.create();
                g2.setColor(new Color(75, 65, 45));
                g2.fillRect(getWidth() / 4, 0, getWidth() / 2, 1);
                g2.dispose();
            }
        };
        sep.setPreferredSize(new Dimension(280, 1));
        sep.setMinimumSize(new Dimension(280, 1));
        sep.setMaximumSize(new Dimension(280, 1));
        sep.setBackground(BG);
        p.add(sep);
        p.add(Box.createRigidArea(new Dimension(0, 10)));

        SpecialEditionScriptLabel lbl = new SpecialEditionScriptLabel("✨ Special Edition for Shelikhov O. Yu. ✨", 15.5f);
        lbl.setAlignmentX(Component.CENTER_ALIGNMENT);
        p.add(lbl);

        return p;
    }

    private void updateSpecialEditionVisibility() {
        boolean show = prefs.getBoolean(PREF_KEY_SHOW_LABEL, false);
        if (specialEditionPanel != null) {
            specialEditionPanel.setVisible(show);
            if (frame != null) {
                frame.setSize(380, show ? 400 : 360);
                frame.revalidate();
                frame.repaint();
            }
        }
    }

    private void showSpecialEditionDialog() {
        JDialog dialog = new JDialog(frame, "Эксклюзивное издание", true);
        dialog.setSize(480, 360);
        dialog.setResizable(false);
        dialog.setLocationRelativeTo(frame);

        JPanel content = new JPanel() {
            @Override
            protected void paintComponent(Graphics g) {
                super.paintComponent(g);
                Graphics2D g2 = (Graphics2D) g.create();
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

                GradientPaint bgGradient = new GradientPaint(
                    0, 0, new Color(22, 25, 31),
                    0, getHeight(), new Color(14, 16, 20)
                );
                g2.setPaint(bgGradient);
                g2.fillRect(0, 0, getWidth(), getHeight());

                g2.setColor(new Color(212, 175, 55, 130));
                g2.drawRect(8, 8, getWidth() - 17, getHeight() - 17);
                g2.setColor(new Color(255, 215, 0, 45));
                g2.drawRect(10, 10, getWidth() - 21, getHeight() - 21);

                g2.dispose();
            }
        };
        content.setLayout(new BorderLayout());
        content.setBorder(BorderFactory.createEmptyBorder(25, 25, 20, 25));

        JPanel topPanel = new JPanel();
        topPanel.setLayout(new BoxLayout(topPanel, BoxLayout.Y_AXIS));
        topPanel.setOpaque(false);

        SpecialEditionScriptLabel mainScript = new SpecialEditionScriptLabel("Special Edition for Shelikhov O. Yu.", 24f);
        mainScript.setAlignmentX(Component.CENTER_ALIGNMENT);
        topPanel.add(mainScript);

        topPanel.add(Box.createRigidArea(new Dimension(0, 6)));
        JLabel subTitle = styledLabel("Эксклюзивная версия программы PC Pulse", new Color(190, 170, 110), new Font("Segoe UI", Font.ITALIC, 12));
        topPanel.add(subTitle);
        topPanel.add(Box.createRigidArea(new Dimension(0, 18)));

        content.add(topPanel, BorderLayout.NORTH);

        JPanel centerPanel = new JPanel();
        centerPanel.setLayout(new BoxLayout(centerPanel, BoxLayout.Y_AXIS));
        centerPanel.setOpaque(false);

        String[] lines = {
            "В знак глубокой благодарности за Ваше наставничество,",
            "бесценный педагогический труд и веру в успех!",
            "",
            "Благодаря Вашему профессиональному руководству и поддержке",
            "этот проект завоевал 3-е место во Всероссийском конкурсе.",
            "",
            "Спасибо за то, что вдохновляете на новые победы!"
        };

        for (String line : lines) {
            if (line.isEmpty()) {
                centerPanel.add(Box.createRigidArea(new Dimension(0, 8)));
            } else {
                JLabel l = styledLabel(line, new Color(225, 230, 240), new Font("Segoe UI", Font.PLAIN, 13));
                centerPanel.add(l);
                centerPanel.add(Box.createRigidArea(new Dimension(0, 4)));
            }
        }
        content.add(centerPanel, BorderLayout.CENTER);

        JPanel bottomPanel = new JPanel(new BorderLayout());
        bottomPanel.setOpaque(false);
        bottomPanel.setBorder(BorderFactory.createEmptyBorder(15, 5, 0, 5));

        boolean isShown = prefs.getBoolean(PREF_KEY_SHOW_LABEL, false);
        JCheckBox showOnMainCheck = new JCheckBox("Надпись на главном экране", isShown);
        showOnMainCheck.setFont(new Font("Segoe UI", Font.BOLD, 12));
        showOnMainCheck.setForeground(new Color(245, 225, 160));
        showOnMainCheck.setOpaque(false);
        showOnMainCheck.setFocusPainted(false);
        showOnMainCheck.setCursor(new Cursor(Cursor.HAND_CURSOR));
        showOnMainCheck.addActionListener(e -> {
            prefs.putBoolean(PREF_KEY_SHOW_LABEL, showOnMainCheck.isSelected());
            updateSpecialEditionVisibility();
        });

        ModernButton closeBtn = new ModernButton("Закрыть");
        closeBtn.setPreferredSize(new Dimension(90, 32));
        closeBtn.addActionListener(e -> dialog.dispose());

        bottomPanel.add(showOnMainCheck, BorderLayout.WEST);
        bottomPanel.add(closeBtn, BorderLayout.EAST);

        content.add(bottomPanel, BorderLayout.SOUTH);

        dialog.setContentPane(content);
        dialog.setVisible(true);
    }

    static class SpecialEditionScriptLabel extends JComponent {
        private final String text;
        private final Font scriptFont;

        public SpecialEditionScriptLabel(String text, float size) {
            this.text = text;
            this.scriptFont = findScriptFont(size);
            setFont(this.scriptFont);

            FontMetrics fm = getFontMetrics(this.scriptFont);
            int width = fm.stringWidth(text) + 24;
            int height = fm.getHeight() + 12;
            setPreferredSize(new Dimension(width, height));
            setMinimumSize(new Dimension(width, height));
            setMaximumSize(new Dimension(width, height));
        }

        private static Font findScriptFont(float size) {
            String[] preferred = {"Segoe Script", "Monotype Corsiva", "Gabriola", "Pristina", "Brush Script MT", "Lucidacalligraphy", "Georgia", "Serif"};
            for (String name : preferred) {
                Font f = new Font(name, Font.BOLD | Font.ITALIC, (int)size);
                if (!f.getFamily().equalsIgnoreCase("Dialog") && !f.getFamily().equalsIgnoreCase("DialogInput")) {
                    return f.deriveFont(size);
                }
            }
            return new Font("Serif", Font.BOLD | Font.ITALIC, (int)size);
        }

        @Override
        protected void paintComponent(Graphics g) {
            super.paintComponent(g);
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_LCD_HRGB);
            g2.setRenderingHint(RenderingHints.KEY_FRACTIONALMETRICS, RenderingHints.VALUE_FRACTIONALMETRICS_ON);

            g2.setFont(scriptFont);
            FontMetrics fm = g2.getFontMetrics();
            int x = (getWidth() - fm.stringWidth(text)) / 2;
            int y = ((getHeight() - fm.getHeight()) / 2) + fm.getAscent();

            g2.setColor(new Color(0, 0, 0, 180));
            g2.drawString(text, x + 2, y + 2);
            g2.setColor(new Color(255, 180, 0, 50));
            g2.drawString(text, x - 1, y - 1);
            g2.drawString(text, x + 1, y + 1);

            LinearGradientPaint goldGradient = new LinearGradientPaint(
                x, y - fm.getAscent(), x + fm.stringWidth(text), y + fm.getDescent(),
                new float[]{0.0f, 0.3f, 0.7f, 1.0f},
                new Color[]{
                    new Color(255, 245, 210),
                    new Color(255, 215, 0),
                    new Color(255, 165, 0),
                    new Color(255, 235, 150)
                }
            );
            g2.setPaint(goldGradient);
            g2.drawString(text, x, y);

            g2.dispose();
        }
    }

    static class StarButton extends JButton {
        private boolean hovered = false;

        public StarButton() {
            super("★");
            setPreferredSize(new Dimension(30, 30));
            setMinimumSize(new Dimension(30, 30));
            setMaximumSize(new Dimension(30, 30));
            setFont(new Font("Segoe UI", Font.PLAIN, 15));
            setForeground(new Color(212, 175, 55));
            setFocusPainted(false);
            setBorderPainted(false);
            setContentAreaFilled(false);
            setOpaque(false);
            setCursor(new Cursor(Cursor.HAND_CURSOR));

            addMouseListener(new java.awt.event.MouseAdapter() {
                public void mouseEntered(java.awt.event.MouseEvent e) { hovered = true; repaint(); }
                public void mouseExited(java.awt.event.MouseEvent e) { hovered = false; repaint(); }
                public void mousePressed(java.awt.event.MouseEvent e) { repaint(); }
                public void mouseReleased(java.awt.event.MouseEvent e) { repaint(); }
            });
        }

        @Override
        protected void paintComponent(Graphics g) {
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

            Color circleColor = hovered ? new Color(65, 70, 82) : new Color(42, 45, 52);
            Color borderColor = hovered ? new Color(255, 215, 0) : new Color(100, 95, 80);

            g2.setColor(circleColor);
            g2.fillOval(1, 1, getWidth() - 3, getHeight() - 3);

            g2.setColor(borderColor);
            g2.setStroke(new BasicStroke(hovered ? 1.5f : 1.0f));
            g2.drawOval(1, 1, getWidth() - 3, getHeight() - 3);

            g2.dispose();

            if (hovered) {
                setForeground(new Color(255, 235, 120));
            } else {
                setForeground(new Color(212, 175, 55));
            }
            super.paintComponent(g);
        }
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
