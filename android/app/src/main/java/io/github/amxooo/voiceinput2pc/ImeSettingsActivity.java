package io.github.amxooo.voiceinput2pc;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;

/** Compatibility entry for Android IME settings: reuse the single management page. */
public final class ImeSettingsActivity extends Activity {
    @Override public void onCreate(Bundle state){super.onCreate(state);
        Intent target=new Intent(this,HomeActivity.class).putExtra(HomeActivity.EXTRA_SECTION,"settings");
        target.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP|Intent.FLAG_ACTIVITY_SINGLE_TOP);
        startActivity(target);finish();
    }
}
