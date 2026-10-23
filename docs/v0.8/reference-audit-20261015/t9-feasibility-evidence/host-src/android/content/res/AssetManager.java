package android.content.res;
import java.io.*;
public class AssetManager {
 private final File root;
 public AssetManager(File root){this.root=root;}
 public InputStream open(String path)throws IOException{return new FileInputStream(new File(root,path));}
 public String[] list(String path){String[] values=new File(root,path).list();return values==null?new String[0]:values;}
}
