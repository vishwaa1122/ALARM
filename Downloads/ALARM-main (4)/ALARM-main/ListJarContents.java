import java.util.jar.JarFile;
import java.util.Enumeration;
import java.util.jar.JarEntry;

public class ListJarContents {
    public static void main(String[] args) {
        if (args.length != 1) {
            System.out.println("Usage: java ListJarContents <jar-file>");
            return;
        }
        
        try {
            JarFile jarFile = new JarFile(args[0]);
            Enumeration<JarEntry> entries = jarFile.entries();
            
            System.out.println("Contents of " + args[0] + ":");
            while (entries.hasMoreElements()) {
                JarEntry entry = entries.nextElement();
                System.out.println(entry.getName());
            }
            
            jarFile.close();
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}