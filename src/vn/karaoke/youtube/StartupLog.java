package vn.karaoke.youtube;

import android.content.Context;

import java.io.File;
import java.io.FileOutputStream;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * On-screen + on-disk startup log. Every milestone of app start is recorded
 * with a timestamp, kept in memory (last 40 lines) and appended to
 * startup-log.txt in the app's external files dir. If the app dies, the last
 * photo of the screen (or the file) shows exactly which step it died on —
 * no more guessing from videos.
 */
public class StartupLog {
    private static final List<String> lines = new ArrayList<String>();
    private static final SimpleDateFormat FMT =
            new SimpleDateFormat("HH:mm:ss.SSS", Locale.US);

    public static synchronized void log(Context ctx, String msg) {
        String line = FMT.format(new Date()) + " " + msg;
        lines.add(line);
        while (lines.size() > 40) lines.remove(0);
        try {
            File dir = ctx.getExternalFilesDir(null);
            if (dir == null) dir = ctx.getFilesDir();
            File f = new File(dir, "startup-log.txt");
            FileOutputStream os = new FileOutputStream(f, true);
            os.write((line + "\n").getBytes("UTF-8"));
            os.close();
        } catch (Exception ignored) { }
    }

    public static synchronized String getText() {
        StringBuilder sb = new StringBuilder();
        for (String l : lines) sb.append(l).append("\n");
        return sb.toString();
    }
}
