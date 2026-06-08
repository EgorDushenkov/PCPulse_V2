import java.io.File;
import java.awt.Image;
import java.awt.image.BufferedImage;
import java.awt.Graphics2D;
import javax.imageio.ImageIO;
import sun.awt.shell.ShellFolder;

public class TestIcon2 {
    public static void main(String[] args) throws Exception {
        File f = new File("c:/Windows/explorer.exe");
        ShellFolder sf = ShellFolder.getShellFolder(f);
        Image icon = sf.getIcon(true); // true = large icon
        BufferedImage img = new BufferedImage(icon.getWidth(null), icon.getHeight(null), BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        g.drawImage(icon, 0, 0, null);
        g.dispose();
        ImageIO.write(img, "png", new File("test_icon.png"));
        System.out.println("Size: " + icon.getWidth(null));
    }
}
