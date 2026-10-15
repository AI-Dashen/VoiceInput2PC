package io.github.amxooo.voiceinput2pc;

import android.test.ActivityInstrumentationTestCase2;
import android.view.View;
import android.widget.Button;

public final class ImeSettingsActivityTest extends ActivityInstrumentationTestCase2<ImeSettingsActivity> {
    public ImeSettingsActivityTest(){super(ImeSettingsActivity.class);}

    public void testSettingsActionsRemainReachableAndCaptureVisual() throws Exception {
        ImeSettingsActivity activity=getActivity();getInstrumentation().waitForIdleSync();
        float density=activity.getResources().getDisplayMetrics().density;
        for(int id:new int[]{3201,3202,3203}) {
            View view=activity.findViewById(id);
            assertNotNull("Missing settings action "+id,view);
            assertTrue(view instanceof Button);
            assertTrue("Settings action is shorter than 48dp",view.getLayoutParams().height>=48*density);
        }
        android.graphics.Bitmap bitmap=getInstrumentation().getUiAutomation().takeScreenshot();
        assertNotNull(bitmap);
        java.io.File file=new java.io.File(getInstrumentation().getTargetContext().getCacheDir(),"settings-redesign.png");
        try(java.io.FileOutputStream out=new java.io.FileOutputStream(file)) {
            assertTrue(bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,out));
        }
        assertTrue(file.isFile()&&file.length()>0);
    }
}
