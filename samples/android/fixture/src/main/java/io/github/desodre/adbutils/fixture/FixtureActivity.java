package io.github.desodre.adbutils.fixture;

import android.app.Activity;
import android.graphics.Color;
import android.os.Bundle;
import android.util.Log;
import android.view.Gravity;
import android.widget.TextView;

public final class FixtureActivity extends Activity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        TextView content = new TextView(this);
        content.setBackgroundColor(Color.rgb(18, 52, 86));
        content.setTextColor(Color.WHITE);
        content.setGravity(Gravity.CENTER);
        content.setTextSize(24);
        content.setText("adb-utils fixture");
        content.setContentDescription("adb-utils-fixture-ready");
        setContentView(content);
        Log.i("AdbUtilsFixture", "fixture-ready");
    }
}
