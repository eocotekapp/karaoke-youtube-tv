package vn.karaoke.youtube;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

/** Shows the last crash's stack trace full-screen so it can be screenshotted. */
public class CrashActivity extends Activity {
    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        String trace = getIntent().getStringExtra("trace");
        final String file = getIntent().getStringExtra("file");
        if (trace == null) trace = "(khong co thong tin loi)";
        final String full = trace;

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(0xFF000000);
        root.setPadding(16, 16, 16, 16);

        TextView title = new TextView(this);
        title.setTextSize(22);
        title.setTextColor(0xFFFF5555);
        title.setText("APP BI LOI — CHUP MAN HINH NAY GUI MESU");
        root.addView(title);

        TextView f = new TextView(this);
        f.setTextSize(16);
        f.setTextColor(0xFFFFFFFF);
        f.setText("Log da luu: " + (file == null || file.isEmpty() ? "(khong luu duoc)" : file));
        root.addView(f);

        Button copy = new Button(this);
        copy.setText("Sao chep loi");
        copy.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                ClipboardManager cm = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
                cm.setPrimaryClip(ClipData.newPlainText("karaoke-crash", full));
                Toast.makeText(CrashActivity.this, "Da sao chep", Toast.LENGTH_SHORT).show();
            }
        });
        root.addView(copy);

        TextView tv = new TextView(this);
        tv.setTextSize(15);
        tv.setTextColor(0xFFFFFFFF);
        tv.setTypeface(android.graphics.Typeface.MONOSPACE);
        tv.setText(full);
        ScrollView sv = new ScrollView(this);
        sv.addView(tv);
        root.addView(sv);
        setContentView(root);
    }
}
