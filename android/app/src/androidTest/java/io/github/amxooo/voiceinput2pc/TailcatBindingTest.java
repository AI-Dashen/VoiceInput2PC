package io.github.amxooo.voiceinput2pc;
import android.test.AndroidTestCase;
public final class TailcatBindingTest extends AndroidTestCase {
 public void testPackagedNativeRuntimeLoads() throws Exception {
  Class<?> b=Class.forName("io.github.amxooo.voiceinput2pc.tailcatbridge.Tailcatbridge");
  Class<?> l=Class.forName("io.github.amxooo.voiceinput2pc.tailcatbridge.InterfaceLister");
  assertEquals(long.class,b.getMethod("startForward",String.class,long.class).getReturnType());
  assertNotNull(b.getMethod("setInterfaceLister",l));
  b.getMethod("touch").invoke(null);b.getMethod("stopForward").invoke(null);
 }
}
