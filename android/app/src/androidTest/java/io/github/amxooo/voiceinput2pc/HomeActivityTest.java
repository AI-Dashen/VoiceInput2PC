package io.github.amxooo.voiceinput2pc;

import android.test.ActivityInstrumentationTestCase2;
import android.view.View;

public final class HomeActivityTest extends ActivityInstrumentationTestCase2<HomeActivity> {
    public HomeActivityTest(){super(HomeActivity.class);}
    public void testLauncherIsOneCompactManagementCenter(){
        HomeActivity activity=getActivity();getInstrumentation().waitForIdleSync();
        assertNotNull(activity.findViewById(3101));
        assertNotNull(activity.findViewById(3102));
        assertTrue(activity.findViewById(3103).getContentDescription().toString().contains("本地输入与语音"));
        assertTrue(activity.findViewById(3104).getContentDescription().toString().contains("设备与跨端"));
        assertTrue(activity.findViewById(3105).getContentDescription().toString().contains("输入记录"));
        assertNotNull(activity.findViewById(3110));
    }
}
