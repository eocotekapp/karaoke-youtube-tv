package vn.karaoke.youtube;

import android.app.Application;

/** Installs the global crash catcher as early as possible. */
public class KaraokeApp extends Application {
    @Override
    public void onCreate() {
        super.onCreate();
        CrashHandler.install(this);
    }
}
