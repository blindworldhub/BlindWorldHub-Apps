package com.blindworldhub.smartdownloader;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.job.JobInfo;
import android.app.job.JobParameters;
import android.app.job.JobScheduler;
import android.app.job.JobService;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Messages and polls from the developer: every 6 hours, and when the app opens, the server is asked whether there is
 * something new; a message is shown as a notification (pressing it opens its link), and a new poll as a notification
 * that opens the app, where the question is asked. Nothing about the person is sent.
 */
public final class MessageJob extends JobService {

    private static final int JOB = 51;
    private static final String CH = "messages";

    static void schedule(Context c) {
        try {
            JobScheduler js = (JobScheduler) c.getSystemService(Context.JOB_SCHEDULER_SERVICE);
            if (js == null) return;
            for (JobInfo j : js.getAllPendingJobs()) if (j.getId() == JOB) return;
            js.schedule(new JobInfo.Builder(JOB, new ComponentName(c, MessageJob.class))
                    .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                    .setPeriodic(6 * 3600 * 1000L).build());
        } catch (Throwable t) {
            AppLog.info(c, "Messages", "could not schedule: " + t.getMessage());
        }
    }

    @Override
    public boolean onStartJob(final JobParameters p) {
        new Thread(new Runnable() {
            @Override public void run() {
                check(MessageJob.this, true);
                Updates.checkQuietly(MessageJob.this);
                jobFinished(p, false);
            }
        }, "messages").start();
        return true;
    }

    @Override public boolean onStopJob(JobParameters p) { return false; }

    /** Background thread. The first time only messages of the last 7 days are shown. */
    static void check(Context c, boolean pollToo) {
        try {
            long last = Prefs.lastMessage(c);
            JSONArray ms = new JSONObject(Net.get(Server.URL + "/v1/messages?app=" + Server.APP + "&after=" + Math.max(0, last))).optJSONArray("messages");
            if (ms == null || ms.length() == 0) {
                if (last < 0) Prefs.setLastMessage(c, 0);
            } else {
                long newest = Math.max(0, last);
                long now = System.currentTimeMillis() / 1000;
                int shown = 0;
                for (int i = ms.length() - 1; i >= 0; i--) {             // oldest first
                    JSONObject m = ms.getJSONObject(i);
                    long id = m.optLong("id");
                    newest = Math.max(newest, id);
                    if (last < 0 && now - m.optLong("t") > 7 * 86400) continue;
                    if (shown++ >= 3) continue;
                    show(c, 1000 + (int) id, m.optString("title", ""), m.optString("body", ""), m.optString("link", ""), false);
                }
                Prefs.setLastMessage(c, newest);
            }
        } catch (Throwable t) {
            AppLog.info(c, "Messages", "not checked: " + t.getMessage());
        }
        if (!pollToo) return;
        Hub.Poll p = Hub.newPoll(c);
        if (p != null && p.id > Prefs.notedPoll(c)) {
            Prefs.setNotedPoll(c, p.id);
            show(c, 5000 + (int) p.id, "A question from the developer", p.question + " Open Smart Downloader to answer.", "", true);
        }
    }

    @SuppressWarnings("deprecation")
    private static void show(Context c, int id, String title, String body, String link, boolean openApp) {
        NotificationManager nm = (NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm == null) return;
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationChannel ch = new NotificationChannel(CH, "Messages from the developer", NotificationManager.IMPORTANCE_DEFAULT);
            ch.setDescription("News, notices and questions from Blind World Hub.");
            nm.createNotificationChannel(ch);
        }
        Intent open = !openApp && link.startsWith("https://") ? new Intent(Intent.ACTION_VIEW, Uri.parse(link))
                : new Intent(c, MainActivity.class);
        open.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        int f = PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE;
        Notification.Builder b = Build.VERSION.SDK_INT >= 26 ? new Notification.Builder(c, CH) : new Notification.Builder(c);
        String t = title.length() > 0 ? title : "Smart Downloader";
        String text = body + (link.length() > 0 ? (body.length() > 0 ? " " : "") + link : "");
        b.setSmallIcon(R.drawable.ic_stat_download).setContentTitle(t).setContentText(text)
                .setStyle(new Notification.BigTextStyle().bigText(text)).setAutoCancel(true)
                .setContentIntent(PendingIntent.getActivity(c, id, open, f));
        try { nm.notify(id, b.build()); } catch (Throwable ignored) { }
    }
}
