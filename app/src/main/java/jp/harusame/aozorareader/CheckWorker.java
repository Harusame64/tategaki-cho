package jp.harusame.aozorareader;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.work.Constraints;
import androidx.work.ExistingPeriodicWorkPolicy;
import androidx.work.NetworkType;
import androidx.work.PeriodicWorkRequest;
import androidx.work.WorkManager;
import androidx.work.Worker;
import androidx.work.WorkerParameters;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * The check for new episodes when the app is not open: every few hours (the user's choice, off by default),
 * the same requests as the shelf's own check — なろう in one API request, カクヨム one work page each, a second
 * apart — and a notification naming the works with new episodes since the last check.
 */
public class CheckWorker extends Worker {
    static final String NAME = "check-new-episodes";
    static final String CHANNEL = "new-episodes";
    static final int[] HOURS = {0, 3, 6, 12, 24};

    public CheckWorker(@NonNull Context c, @NonNull WorkerParameters p) {
        super(c, p);
    }

    @NonNull
    @Override
    public Result doWork() {
        Context c = getApplicationContext();
        try {
            Shelf s = Shelf.load(c);
            if (s.unread || s.works.isEmpty()) return Result.success();
            // Not again so soon after a check (the shelf's own, or the last round), and not when nothing can be
            // shown: notifications turned off.
            if (System.currentTimeMillis() - s.checked < ShelfActivity.CHECK_EVERY_MS) return Result.success();
            if (!c.getSystemService(NotificationManager.class).areNotificationsEnabled()) return Result.success();
            Map<String, Integer> before = new HashMap<>();
            for (Shelf.Work w : s.works) before.put(w.ncode, w.newEpisodes());
            // Never waits behind the shelf's own check, checks the time again once it has its turn, and shows its
            // news before letting the turn go.
            s.refresh(new ArrayList<>(s.works), c, false, ShelfActivity.CHECK_EVERY_MS, () -> {
                Map<String, Integer> more = new HashMap<>();
                Map<String, String> titles = new HashMap<>();
                for (Shelf.Work w : s.works) {
                    int m = w.newEpisodes() - before.getOrDefault(w.ncode, 0);
                    if (m > 0) { more.put(w.ncode, m); titles.put(w.ncode, w.title); }
                }
                if (!more.isEmpty()) publish(c, more, titles);
            });
        } catch (Exception e) {
            Log.w(Shelf.TAG, "background check", e);
        }
        return Result.success();                                         // never retried sooner than the next round
    }

    /** Adds this round's news and shows it, as one step against the shelf clearing it. */
    static void publish(Context c, Map<String, Integer> more, Map<String, String> titles) {
        synchronized (CheckWorker.class) { notify(c, addNews(c, more, titles)); }
    }

    /**
     * The news not yet looked at (kept until the shelf is opened), with this round's added: an earlier round's
     * works stay in the notification instead of being replaced.
     */
    static org.json.JSONObject addNews(Context c, Map<String, Integer> more, Map<String, String> titles) {
        SharedPreferences p = c.getSharedPreferences("reader", Context.MODE_PRIVATE);
        org.json.JSONObject news;
        try { news = new org.json.JSONObject(p.getString("news", "{}")); } catch (Exception e) { news = new org.json.JSONObject(); }
        try {
            for (Map.Entry<String, Integer> e : more.entrySet()) {
                org.json.JSONObject w = news.optJSONObject(e.getKey());
                int had = w == null ? 0 : w.optInt("n");
                news.put(e.getKey(), new org.json.JSONObject().put("t", titles.get(e.getKey())).put("n", had + e.getValue()));
            }
        } catch (Exception ignored) { }
        p.edit().putString("news", news.toString()).commit();
        return news;
    }

    /** The shelf was opened: the news has been seen, and its notification goes. */
    static void clearNews(Context c) {
        synchronized (CheckWorker.class) {
            // apply: the in-memory value changes at once, inside the lock; the disk write need not hold up the shelf.
            c.getSharedPreferences("reader", Context.MODE_PRIVATE).edit().remove("news").apply();
            c.getSystemService(NotificationManager.class).cancel(1);
        }
    }

    static void notify(Context c, org.json.JSONObject news) {
        List<String> works = new ArrayList<>();
        int total = 0;
        for (java.util.Iterator<String> it = news.keys(); it.hasNext(); ) {
            org.json.JSONObject w = news.optJSONObject(it.next());
            works.add(w.optString("t") + "（" + w.optInt("n") + "話）");
            total += w.optInt("n");
        }
        notify(c, total, works);
    }

    static void notify(Context c, int total, List<String> works) {
        if (c.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return;
        NotificationManager nm = c.getSystemService(NotificationManager.class);
        nm.createNotificationChannel(new NotificationChannel(CHANNEL, "新着", NotificationManager.IMPORTANCE_DEFAULT));
        PendingIntent open = PendingIntent.getActivity(c, 0, new Intent(c, ShelfActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), PendingIntent.FLAG_IMMUTABLE);
        Notification n = new Notification.Builder(c, CHANNEL)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle("新着 " + total + " 話")
                .setContentText(String.join("、", works))
                .setStyle(new Notification.BigTextStyle().bigText(String.join("\n", works)))
                .setContentIntent(open)
                .setAutoCancel(true)
                .build();
        nm.notify(1, n);
    }

    /** Schedules (or cancels, for 0 hours) the check as the settings say. */
    static void apply(Context c) {
        SharedPreferences p = c.getSharedPreferences("reader", Context.MODE_PRIVATE);
        int hours = p.getInt("checkHours", 0);
        WorkManager wm = WorkManager.getInstance(c);
        if (hours <= 0) { wm.cancelUniqueWork(NAME); return; }
        Constraints k = new Constraints.Builder()
                .setRequiredNetworkType(p.getBoolean("checkWifi", false) ? NetworkType.UNMETERED : NetworkType.CONNECTED)
                .build();
        wm.enqueueUniquePeriodicWork(NAME, ExistingPeriodicWorkPolicy.UPDATE,
                new PeriodicWorkRequest.Builder(CheckWorker.class, hours, TimeUnit.HOURS).setConstraints(k).build());
    }
}
