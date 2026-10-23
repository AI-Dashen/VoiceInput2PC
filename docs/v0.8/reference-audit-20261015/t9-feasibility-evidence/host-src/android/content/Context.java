package android.content;
import java.io.File;
import android.content.res.AssetManager;
public class Context {
 private final File files;private final AssetManager assets;
 public Context(File files,File assets){this.files=files;this.files.mkdirs();this.assets=new AssetManager(assets);}
 public File getFilesDir(){return files;}
 public AssetManager getAssets(){return assets;}
}
