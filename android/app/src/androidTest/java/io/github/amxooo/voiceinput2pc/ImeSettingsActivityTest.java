package io.github.amxooo.voiceinput2pc;

import android.content.Intent;
import android.test.ActivityInstrumentationTestCase2;
import android.view.View;
import android.widget.Button;

/** Settings live inside the management page; no duplicate permissions screen. */
public final class ImeSettingsActivityTest extends ActivityInstrumentationTestCase2<HomeActivity> {
    public ImeSettingsActivityTest(){super(HomeActivity.class);}
    public void testSettingsSectionIntentKeepsActionsInOnePage()throws Throwable {
        setActivityIntent(new Intent().putExtra(HomeActivity.EXTRA_SECTION,"settings"));
        HomeActivity a=getActivity();getInstrumentation().waitForIdleSync();
        assertEquals(View.VISIBLE,((View)a.findViewById(3102).getParent()).getVisibility());
        for(int id:new int[]{3201,3101,3102,3110})assertTrue(a.findViewById(id) instanceof Button);
        assertNotNull(a.findViewById(2701));assertNotNull(a.findViewById(2710));
    }
}
