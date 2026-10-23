package io.github.amxooo.voiceinput2pc;

import android.test.ActivityInstrumentationTestCase2;
import android.view.View;
import android.widget.TextView;

/** Management navigation never enters old full-page settings/history surfaces. */
public final class HomeActivityTest extends ActivityInstrumentationTestCase2<HomeActivity> {
    public HomeActivityTest(){super(HomeActivity.class);}
    public void testLauncherContainsRealHistoryAndInlineSettings()throws Throwable {
        HomeActivity a=getActivity();getInstrumentation().waitForIdleSync();
        for(int id:new int[]{3400,3401,3402,3403,3404,3405,3406})assertNotNull(a.findViewById(id));
        assertNotNull(a.findViewById(3101));assertNotNull(a.findViewById(3102));
        assertNotNull(a.findViewById(3104));assertNotNull(a.findViewById(3105));
        assertNotNull(a.findViewById(2701));assertNotNull(a.findViewById(2704));
        assertNotNull(a.findViewById(2710));assertNotNull(a.findViewById(2707));
        assertEquals(View.GONE,a.findViewById(3201).getParent() instanceof View?((View)a.findViewById(3201).getParent()).getVisibility():View.GONE);
        runTestOnUiThread(()->a.findViewById(3103).performClick());
        getInstrumentation().waitForIdleSync();
        assertEquals(View.VISIBLE,((View)a.findViewById(3201).getParent()).getVisibility());
        assertTrue(((TextView)a.findViewById(2710)).getText().length()>0);
    }
}
