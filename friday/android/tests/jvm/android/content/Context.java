package android.content;

import java.io.File;

/** JVM stub: only what the checks use. */
public class Context {
  private final File dir;
  public Context(File dir){this.dir=dir;}
  public File getFilesDir(){return dir;}
}
