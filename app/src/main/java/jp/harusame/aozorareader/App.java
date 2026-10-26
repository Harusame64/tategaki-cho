package jp.harusame.aozorareader;

import android.app.Activity;
import android.app.Application;
import android.os.Bundle;

/** Counts the screens shown, so 全話を保存 goes on only while the app is in front. */
public class App extends Application {
    @Override
    public void onCreate() {
        super.onCreate();
        registerActivityLifecycleCallbacks(new ActivityLifecycleCallbacks() {
            @Override public void onActivityStarted(Activity a) { FullSave.SHOWN.incrementAndGet(); FullSave.wake(); }
            @Override public void onActivityStopped(Activity a) { FullSave.SHOWN.decrementAndGet(); }
            @Override public void onActivityCreated(Activity a, Bundle b) { }
            @Override public void onActivityResumed(Activity a) { }
            @Override public void onActivityPaused(Activity a) { }
            @Override public void onActivitySaveInstanceState(Activity a, Bundle b) { }
            @Override public void onActivityDestroyed(Activity a) { }
        });
    }
}
