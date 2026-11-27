package vn.karaoke.youtube;

import android.content.Context;
import android.content.Intent;
import android.os.Build;

import java.io.File;
import java.io.FileOutputStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * Global crash catcher. On any uncaught exception (the "app keeps stopping"
 * dialog), writes the full stack trace to a file and opens CrashActivity
 * showing it on screen, so the user can screenshot it instead of guessing.
 */
public class CrashHandler implements Thread.UncaughtExceptionHandler {
    private final Context appCtx;
    private final Thread.UncaughtExceptionHandler prev;

    public static void install(Context ctx) {
        Context app = ctx.getApplicationContext();
        Thread.UncaughtExceptionHandler prev = Thread.getDefaultUncaughtExceptionHandler();
        if (!(prev instanceof CrashHandler)) {
            Thread.setDefaultUncaughtExceptionHandler(new CrashHandler(app, prev));
        }
    }

    private CrashHandler(Context appCtx, Thread.UncaughtExceptionHandler prev) {
        this.appCtx = appCtx;
        this.prev = prev;
    }

    /** Log a caught exception (non-fatal) to the same crash file. */
    public static void log(Context ctx, Throwable t) {
        try { writeFile(ctx, traceOf(t)); } catch (Exception ignored) { }
    }

    public static String traceOf(Throwable t) {
        StringWriter sw = new StringWriter();
        PrintWriter pw = new PrintWriter(sw);
        t.printStackTrace(pw);
        pw.flush();
        return sw.toString();
    }

    private static String stamp() {
        return new SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.US).format(new Date());
    }

    /** Returns the file the trace was written to, or null. */
    public static File writeFile(Context ctx, String trace) {
        try {
            File dir = ctx.getExternalFilesDir(null);
            if (dir == null) dir = ctx.getFilesDir();
            File f = new File(dir, "crash-" + stamp() + ".txt");
            FileOutputStream os = new FileOutputStream(f);
            String head = "Karaoke YouTube v1.0.1 crash\n"
                    + "Time: " + new Date().toString() + "\n"
                    + "Device: " + Build.MANUFACTURER + " " + Build.MODEL
                    + " Android " + Build.VERSION.RELEASE + " SDK " + Build.VERSION.SDK_INT
                    + " ABI " + Build.CPU_ABI + "\n\n";
            os.write((head + trace).getBytes("UTF-8"));
            os.close();
            return f;
        } catch (Exception e) { return null; }
    }

    @Override
    public void uncaughtException(Thread thread, Throwable t) {
        try {
            String trace = traceOf(t);
            File f = writeFile(appCtx, trace);
            Intent i = new Intent(appCtx, CrashActivity.class);
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
            i.putExtra("trace", trace);
            i.putExtra("file", f == null ? "" : f.getAbsolutePath());
            appCtx.startActivity(i);
            // Give the activity a moment to start, then kill this process
            // so Android does not show its own crash dialog on top.
            try { Thread.sleep(800); } catch (InterruptedException ignored) { }
        } catch (Exception ignored) { }
        if (prev != null) {
            try { prev.uncaughtException(thread, t); } catch (Exception ignored) { }
        } else {
            android.os.Process.killProcess(android.os.Process.myPid());
            System.exit(2);
        }
    }
}
