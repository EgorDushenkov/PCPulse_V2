import java.io.File;
import javax.swing.Icon;
import javax.swing.filechooser.FileSystemView;

public class TestIcon3 {
    public static void main(String[] args) throws Exception {
        File f = new File("c:/Windows/explorer.exe");
        Icon icon = FileSystemView.getFileSystemView().getSystemIcon(f);
        System.out.println("Size from FileSystemView: " + icon.getIconWidth());
    }
}
