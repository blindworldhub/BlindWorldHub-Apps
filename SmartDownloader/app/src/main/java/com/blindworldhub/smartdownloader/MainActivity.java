package com.blindworldhub.smartdownloader;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Insets;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.text.InputType;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.WindowInsets;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.CompoundButton;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toolbar;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The one screen: paste or share a link, press Find, choose Video or Audio and the quality, press Download.
 * Below: the downloads, with Cancel, Open, Share, Try again and Report. Every control has a clear name for screen
 * readers, headings mark the parts, and the same screen is clear to see for sighted people.
 */
public final class MainActivity extends Activity implements Engine.Listener, DownloadService.Listener {

    private static final int REQ_NOTIFY = 1, REQ_STORAGE = 2;
    private static final int PRIMARY = 0xFF0B5CAD, TEXT = 0xFF14202E, MUTED = 0xFF4A5A6C;

    private EditText link;
    private Button findBtn, downloadBtn;
    private TextView status, title, details, qualityHead, noJobs;
    private ProgressBar busy;
    private LinearLayout result, jobsBox;
    private CheckBox wholePlaylist;
    private RadioGroup kinds, qualities;
    private RadioButton videoKind, audioKind;

    private Formats.Media media, videoMedia;
    private String mediaUrl = "";
    private boolean finding, pendingFind, pollAsked;
    private Job pendingJob;
    private final Map<String, JobViews> shown = new HashMap<String, JobViews>();

    // ------------------------------------------------------------------------------------------------ screen

    @Override
    protected void onCreate(Bundle saved) {
        super.onCreate(saved);
        setContentView(build());
        Engine.listen(this);
        DownloadService.listen(this);
        Engine.start(this);
        onEngine(Engine.state(), Engine.text());
        onJobs();
        askNotifications();
        MessageJob.schedule(this);
        Updates.checkOnOpen(this);
        handle(getIntent());
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handle(intent);
    }

    @Override
    protected void onResume() {
        super.onResume();
        onJobs();
        if (!pollAsked) {
            pollAsked = true;
            new Thread(new Runnable() {
                @Override public void run() {
                    MessageJob.check(MainActivity.this, false);
                    final Hub.Poll p = Hub.newPoll(MainActivity.this);
                    if (p != null) runOnUiThread(new Runnable() { @Override public void run() { askPoll(p); } });
                }
            }, "open-check").start();
        }
    }

    @Override
    protected void onDestroy() {
        Engine.unlisten(this);
        DownloadService.unlisten(this);
        super.onDestroy();
    }

    private int dp(float v) { return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, getResources().getDisplayMetrics())); }

    private View build() {
        final LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(0xFFF3F6FA);

        final View statusBar = new View(this);
        statusBar.setBackgroundColor(0xFF083F78);
        root.addView(statusBar, new LinearLayout.LayoutParams(-1, 0));

        Toolbar bar = new Toolbar(this);
        bar.setBackgroundColor(PRIMARY);
        bar.setTitle(getString(R.string.app_name));
        bar.setSubtitle("by Waqas, Blind World Hub");
        bar.setTitleTextColor(Color.WHITE);
        bar.setSubtitleTextColor(0xFFDCE8F5);
        root.addView(bar, new LinearLayout.LayoutParams(-1, -2));
        setActionBar(bar);
        if (bar.getOverflowIcon() != null) bar.getOverflowIcon().setTint(Color.WHITE);

        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setPadding(dp(12), dp(12), dp(12), dp(24));
        scroll.addView(page, new ScrollView.LayoutParams(-1, -2));
        root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));

        // 1. the link
        LinearLayout c1 = card(page);
        TextView lab = heading(c1, "Video or playlist link");
        link = new EditText(this);
        link.setId(View.generateViewId());
        lab.setLabelFor(link.getId());
        link.setHint("Paste a link from YouTube or another site");
        link.setSingleLine(true);
        link.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        link.setImeOptions(EditorInfo.IME_ACTION_GO);
        link.setTextSize(17);
        link.setMinHeight(dp(52));
        link.setOnEditorActionListener(new TextView.OnEditorActionListener() {
            @Override public boolean onEditorAction(TextView v, int id, KeyEvent e) { find(); return true; }
        });
        c1.addView(link, new LinearLayout.LayoutParams(-1, -2));
        LinearLayout row = row(c1);
        Button paste = button(row, "Paste link", false);
        paste.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) { paste(); } });
        findBtn = button(row, "Find", true);
        findBtn.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) { find(); } });

        busy = new ProgressBar(this);
        busy.setIndeterminate(true);
        busy.setVisibility(View.GONE);
        busy.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        LinearLayout.LayoutParams bp = new LinearLayout.LayoutParams(dp(36), dp(36));
        bp.gravity = Gravity.CENTER_HORIZONTAL;
        bp.topMargin = dp(8);
        c1.addView(busy, bp);
        status = text(c1, "", 16, TEXT);
        status.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        status.setVisibility(View.GONE);

        // 2. what was found, and the choices
        result = card(page);
        result.setVisibility(View.GONE);
        title = text(result, "", 19, TEXT);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        if (Build.VERSION.SDK_INT >= 28) title.setAccessibilityHeading(true);
        details = text(result, "", 15, MUTED);
        wholePlaylist = new CheckBox(this);
        wholePlaylist.setText("Download the whole playlist that this video is in");
        wholePlaylist.setTextSize(16);
        wholePlaylist.setMinHeight(dp(48));
        wholePlaylist.setVisibility(View.GONE);
        wholePlaylist.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override public void onCheckedChanged(CompoundButton b, boolean on) { playlistChanged(on); }
        });
        result.addView(wholePlaylist, new LinearLayout.LayoutParams(-1, -2));

        heading(result, "What do you want to download?");
        kinds = new RadioGroup(this);
        kinds.setOrientation(RadioGroup.HORIZONTAL);
        videoKind = radio(kinds, "Video");
        audioKind = radio(kinds, "Audio");
        result.addView(kinds, new LinearLayout.LayoutParams(-1, -2));
        kinds.setOnCheckedChangeListener(new RadioGroup.OnCheckedChangeListener() {
            @Override public void onCheckedChanged(RadioGroup g, int id) {
                Prefs.setLastKind(MainActivity.this, id == audioKind.getId() ? 1 : 0);
                fillQualities();
            }
        });

        qualityHead = heading(result, "Choose the quality");
        qualities = new RadioGroup(this);
        qualities.setOrientation(RadioGroup.VERTICAL);
        result.addView(qualities, new LinearLayout.LayoutParams(-1, -2));

        downloadBtn = button(result, "Download", true);
        ((LinearLayout.LayoutParams) downloadBtn.getLayoutParams()).topMargin = dp(12);
        downloadBtn.setMinHeight(dp(56));
        downloadBtn.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) { download(); } });

        // 3. the downloads
        LinearLayout c3 = card(page);
        heading(c3, "Downloads");
        noJobs = text(c3, "No downloads yet. Finished files are saved in the Download folder, in Smart Downloader.", 15, MUTED);
        jobsBox = new LinearLayout(this);
        jobsBox.setOrientation(LinearLayout.VERTICAL);
        c3.addView(jobsBox, new LinearLayout.LayoutParams(-1, -2));

        if (Build.VERSION.SDK_INT >= 35) {
            // Android 15 and newer draw the app under the status and navigation bars: keep everything clear of them
            root.setOnApplyWindowInsetsListener(new View.OnApplyWindowInsetsListener() {
                @Override public WindowInsets onApplyWindowInsets(View v, WindowInsets in) {
                    Insets i = in.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.ime() | WindowInsets.Type.displayCutout());
                    statusBar.getLayoutParams().height = i.top;
                    statusBar.requestLayout();
                    root.setPadding(i.left, 0, i.right, i.bottom);
                    return WindowInsets.CONSUMED;
                }
            });
        }
        return root;
    }

    private LinearLayout card(LinearLayout parent) {
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        c.setBackgroundResource(R.drawable.card);
        c.setPadding(dp(16), dp(14), dp(16), dp(16));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.bottomMargin = dp(12);
        parent.addView(c, lp);
        return c;
    }

    private TextView heading(LinearLayout parent, String s) {
        TextView t = text(parent, s, 18, PRIMARY);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        ((LinearLayout.LayoutParams) t.getLayoutParams()).topMargin = dp(8);
        if (Build.VERSION.SDK_INT >= 28) t.setAccessibilityHeading(true);
        return t;
    }

    private TextView text(LinearLayout parent, String s, int sp, int color) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(sp);
        t.setTextColor(color);
        t.setPadding(0, dp(4), 0, dp(4));
        parent.addView(t, new LinearLayout.LayoutParams(-1, -2));
        return t;
    }

    private LinearLayout row(LinearLayout parent) {
        LinearLayout r = new LinearLayout(this);
        r.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.topMargin = dp(6);
        parent.addView(r, lp);
        return r;
    }

    private Button button(LinearLayout parent, String s, boolean main) {
        Button b = new Button(this);
        b.setText(s);
        b.setAllCaps(false);
        b.setTextSize(16);
        b.setMinHeight(dp(48));
        if (main) {
            b.setBackgroundTintList(ColorStateList.valueOf(PRIMARY));
            b.setTextColor(Color.WHITE);
        }
        LinearLayout.LayoutParams lp = parent.getOrientation() == LinearLayout.HORIZONTAL
                ? new LinearLayout.LayoutParams(0, -2, 1) : new LinearLayout.LayoutParams(-1, -2);
        parent.addView(b, lp);
        return b;
    }

    private RadioButton radio(RadioGroup g, String s) {
        RadioButton r = new RadioButton(this);
        r.setId(View.generateViewId());
        r.setText(s);
        r.setTextSize(16);
        r.setMinHeight(dp(48));
        r.setPadding(dp(4), 0, dp(16), 0);
        g.addView(r, new RadioGroup.LayoutParams(g.getOrientation() == RadioGroup.HORIZONTAL ? 0 : -1, -2,
                g.getOrientation() == RadioGroup.HORIZONTAL ? 1 : 0));
        return r;
    }

    private void say(String s) {
        status.setVisibility(s.length() == 0 ? View.GONE : View.VISIBLE);
        status.setText(s);
    }

    // ------------------------------------------------------------------------------------------------ the link

    private void handle(Intent i) {
        if (i == null) return;
        if (i.getBooleanExtra("checkUpdate", false)) Updates.checkNow(this);
        if (Intent.ACTION_SEND.equals(i.getAction())) {
            String shared = i.getStringExtra(Intent.EXTRA_TEXT);
            if (shared != null && shared.trim().length() > 0) {
                link.setText(Formats.findLink(shared));
                find();
            }
        }
    }

    private void paste() {
        ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        ClipData cd = cm == null ? null : cm.getPrimaryClip();
        CharSequence t = cd != null && cd.getItemCount() > 0 ? cd.getItemAt(0).coerceToText(this) : null;
        if (t == null || t.toString().trim().length() == 0) {
            say("Nothing to paste. Copy a link first, for example with Share and then Copy link in YouTube.");
            return;
        }
        link.setText(Formats.findLink(t.toString()));
        link.setSelection(link.getText().length());
        say("Link pasted. Press Find.");
    }

    private void find() {
        final String url = Formats.findLink(link.getText().toString());
        hideKeyboard();
        if (url.length() == 0) {
            say("Please paste a link first.");
            link.requestFocus();
            return;
        }
        if (!url.startsWith("http")) {
            say("This does not look like a link. A link starts with https.");
            return;
        }
        if (finding) return;
        if (Engine.state() != Engine.READY) {
            pendingFind = true;
            if (Engine.state() == Engine.FAILED) say(Engine.text());
            else say("Please wait: " + Engine.text() + " The link will be opened when it is ready.");
            return;
        }
        final boolean playlist = Formats.playlistOnly(url);
        finding = true;
        busy.setVisibility(View.VISIBLE);
        findBtn.setEnabled(false);
        result.setVisibility(View.GONE);
        say(playlist ? "Finding the playlist. Please wait." : "Finding the video. Please wait.");
        new Thread(new Runnable() {
            @Override public void run() {
                Formats.Media m = null;
                String err = null;
                try {
                    m = Formats.parse(Engine.info(url, playlist));
                } catch (Throwable t) {
                    err = Engine.plain(t.getMessage());
                    AppLog.error(MainActivity.this, "Find", "could not read " + url, t);
                }
                final Formats.Media fm = m;
                final String fe = err;
                runOnUiThread(new Runnable() {
                    @Override public void run() {
                        finding = false;
                        busy.setVisibility(View.GONE);
                        findBtn.setEnabled(true);
                        if (fm == null) { say(fe); return; }
                        mediaUrl = url;
                        videoMedia = fm.playlist ? null : fm;
                        wholePlaylist.setOnCheckedChangeListener(null);
                        wholePlaylist.setChecked(false);
                        wholePlaylist.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
                            @Override public void onCheckedChanged(CompoundButton b, boolean on) { playlistChanged(on); }
                        });
                        wholePlaylist.setVisibility(!fm.playlist && Formats.videoInPlaylist(url) ? View.VISIBLE : View.GONE);
                        show(fm, true);
                    }
                });
            }
        }, "find").start();
    }

    private void playlistChanged(boolean on) {
        if (!on) {
            if (videoMedia != null) show(videoMedia, false);
            return;
        }
        if (finding) return;
        finding = true;
        busy.setVisibility(View.VISIBLE);
        say("Finding the playlist. Please wait.");
        final String url = mediaUrl;
        new Thread(new Runnable() {
            @Override public void run() {
                Formats.Media m = null;
                String err = null;
                try { m = Formats.parse(Engine.info(url, true)); } catch (Throwable t) {
                    err = Engine.plain(t.getMessage());
                    AppLog.error(MainActivity.this, "Find", "could not read the playlist " + url, t);
                }
                final Formats.Media fm = m;
                final String fe = err;
                runOnUiThread(new Runnable() {
                    @Override public void run() {
                        finding = false;
                        busy.setVisibility(View.GONE);
                        if (fm == null || !fm.playlist) {
                            wholePlaylist.setChecked(false);
                            say(fm == null ? fe : "This link has no playlist to download.");
                            return;
                        }
                        show(fm, false);
                    }
                });
            }
        }, "find-playlist").start();
    }

    private void show(Formats.Media m, boolean moveFocus) {
        media = m;
        title.setText(m.title);
        StringBuilder d = new StringBuilder();
        if (m.playlist) d.append("Playlist with ").append(m.count).append(m.count == 1 ? " video." : " videos.");
        else if (m.duration > 0) d.append("Length: ").append(Formats.duration(m.duration)).append('.');
        if (m.uploader.length() > 0) d.append(d.length() > 0 ? " " : "").append("By ").append(m.uploader).append('.');
        details.setText(d.toString());
        details.setVisibility(d.length() > 0 ? View.VISIBLE : View.GONE);
        downloadBtn.setText(m.playlist ? "Download the playlist" : "Download");
        result.setVisibility(View.VISIBLE);
        int want = Prefs.lastKind(this) == 1 ? audioKind.getId() : videoKind.getId();
        if (kinds.getCheckedRadioButtonId() != want) kinds.check(want); else fillQualities();
        say("Found. Choose Video or Audio, then the quality, then press " + downloadBtn.getText() + ".");
        if (moveFocus) title.post(new Runnable() {
            @Override public void run() { title.performAccessibilityAction(AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS, null); }
        });
    }

    private void fillQualities() {
        qualities.removeAllViews();
        if (media == null) return;
        boolean audio = kinds.getCheckedRadioButtonId() == audioKind.getId();
        List<Formats.Option> list = audio ? media.audio : media.video;
        qualityHead.setText(audio ? "Choose the audio file type and quality" : "Choose the video quality");
        for (int i = 0; i < list.size(); i++) {
            RadioButton r = radio(qualities, list.get(i).label);
            r.setTag(list.get(i));
            if (i == 0) qualities.check(r.getId());
        }
    }

    // ------------------------------------------------------------------------------------------------ download

    private void download() {
        if (media == null) return;
        View v = qualities.findViewById(qualities.getCheckedRadioButtonId());
        if (v == null || !(v.getTag() instanceof Formats.Option)) {
            say("Please choose a quality first.");
            return;
        }
        Formats.Option o = (Formats.Option) v.getTag();
        Job j = new Job("j" + System.currentTimeMillis(), mediaUrl, media.title, o.label, media.playlist, o.audio, o.args);
        start(j);
    }

    private void start(Job j) {
        if (Build.VERSION.SDK_INT < 29 && checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
            pendingJob = j;
            say("Smart Downloader needs permission to save files in your Download folder.");
            requestPermissions(new String[] { Manifest.permission.WRITE_EXTERNAL_STORAGE }, REQ_STORAGE);
            return;
        }
        DownloadService.add(this, j);
        say("Download started: " + j.title + ". You can follow it below, under Downloads, and in the notification.");
    }

    @Override
    public void onRequestPermissionsResult(int code, String[] perms, int[] res) {
        super.onRequestPermissionsResult(code, perms, res);
        if (code != REQ_STORAGE) return;
        Job j = pendingJob;
        pendingJob = null;
        if (j != null && res.length > 0 && res[0] == PackageManager.PERMISSION_GRANTED) start(j);
        else say("Without this permission Smart Downloader cannot save files on this phone. Press Download again to allow it.");
    }

    private void askNotifications() {
        if (Build.VERSION.SDK_INT >= 33 && !Prefs.askedNotifications(this)
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            Prefs.setAskedNotifications(this);
            requestPermissions(new String[] { Manifest.permission.POST_NOTIFICATIONS }, REQ_NOTIFY);
        }
    }

    // ------------------------------------------------------------------------------------------------ downloads list

    private static final class JobViews {
        LinearLayout box, buttons;
        TextView status;
        ProgressBar bar;
        int state = -1;
    }

    @Override
    public void onJobs() {
        if (jobsBox == null) return;
        List<Job> jobs = DownloadService.jobs();
        noJobs.setVisibility(jobs.isEmpty() ? View.VISIBLE : View.GONE);
        List<String> ids = new ArrayList<String>();
        for (Job j : jobs) ids.add(j.id);
        for (String id : new ArrayList<String>(shown.keySet())) {
            if (!ids.contains(id)) { jobsBox.removeView(shown.get(id).box); shown.remove(id); }
        }
        for (int i = 0; i < jobs.size(); i++) {
            Job j = jobs.get(i);
            JobViews v = shown.get(j.id);
            if (v == null) {
                v = jobView(j);
                shown.put(j.id, v);
                jobsBox.addView(v.box, Math.min(i, jobsBox.getChildCount()));
            }
            int before = v.state;
            v.status.setText(j.status());
            boolean bar = j.state == Job.RUNNING && j.percent >= 0 && "Downloading".equals(j.step);
            v.bar.setVisibility(j.state == Job.RUNNING ? View.VISIBLE : View.GONE);
            v.bar.setIndeterminate(!bar);
            if (bar) v.bar.setProgress(Math.round(j.percent));
            if (before != j.state) {
                v.state = j.state;
                buttons(v, j);
                if (before != -1 && j.finished()) jobsBox.announceForAccessibility(j.title + ". " + j.status());
            }
        }
    }

    private JobViews jobView(Job j) {
        JobViews v = new JobViews();
        v.box = new LinearLayout(this);
        v.box.setOrientation(LinearLayout.VERTICAL);
        v.box.setPadding(0, dp(10), 0, dp(10));
        TextView t = text(v.box, j.title, 16, TEXT);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        if (Build.VERSION.SDK_INT >= 28) t.setAccessibilityHeading(true);
        text(v.box, j.choice + (j.playlist ? ", whole playlist" : ""), 14, MUTED);
        v.status = text(v.box, "", 15, TEXT);
        v.bar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        v.bar.setMax(100);
        v.bar.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        v.box.addView(v.bar, new LinearLayout.LayoutParams(-1, dp(10)));
        v.buttons = row(v.box);
        View line = new View(this);
        line.setBackgroundColor(0xFFD5DEE8);
        v.box.addView(line, new LinearLayout.LayoutParams(-1, dp(1)));
        ((LinearLayout.LayoutParams) line.getLayoutParams()).topMargin = dp(10);
        return v;
    }

    private void buttons(JobViews v, final Job j) {
        v.buttons.removeAllViews();
        if (!j.finished()) {
            add(v.buttons, "Cancel", "Cancel the download of " + j.title, new Runnable() {
                @Override public void run() { DownloadService.cancel(MainActivity.this, j); }
            });
            return;
        }
        if (j.state == Job.DONE && !j.saved.isEmpty()) {
            if (j.saved.size() == 1) add(v.buttons, "Open", "Open " + j.saved.get(0).name, new Runnable() {
                @Override public void run() { open(j.saved.get(0)); }
            });
            add(v.buttons, "Share", "Share " + (j.saved.size() == 1 ? j.saved.get(0).name : "the files of " + j.title), new Runnable() {
                @Override public void run() { share(j); }
            });
        }
        if (j.state == Job.FAILED || j.state == Job.CANCELLED) {
            add(v.buttons, "Try again", "Try again to download " + j.title, new Runnable() {
                @Override public void run() {
                    DownloadService.remove(j);
                    start(new Job("j" + System.currentTimeMillis(), j.url, j.title, j.choice, j.playlist, j.audio, j.args));
                }
            });
        }
        if (j.state == Job.FAILED) {
            add(v.buttons, "Report", "Send this problem with " + j.title + " to the developer", new Runnable() {
                @Override public void run() { reportJob(j); }
            });
        }
        add(v.buttons, "Remove", "Remove " + j.title + " from this list. The saved file stays.", new Runnable() {
            @Override public void run() { DownloadService.remove(j); }
        });
    }

    private void add(LinearLayout row, String label, String spoken, final Runnable r) {
        Button b = button(row, label, false);
        b.setContentDescription(spoken);
        b.setTextSize(14);
        b.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) { r.run(); } });
    }

    private void open(Saver.Saved s) {
        try {
            Intent v = new Intent(Intent.ACTION_VIEW).setDataAndType(s.uri, s.mime).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivity(Intent.createChooser(v, "Open with"));
        } catch (Throwable t) {
            say("No app on this phone can open this file. It is saved in the " + s.where + " folder.");
        }
    }

    private void share(Job j) {
        try {
            Intent i;
            if (j.saved.size() == 1) {
                i = new Intent(Intent.ACTION_SEND).setType(j.saved.get(0).mime).putExtra(Intent.EXTRA_STREAM, j.saved.get(0).uri);
            } else {
                ArrayList<Uri> us = new ArrayList<Uri>();
                for (Saver.Saved s : j.saved) us.add(s.uri);
                i = new Intent(Intent.ACTION_SEND_MULTIPLE).setType(j.audio ? "audio/*" : "video/*").putParcelableArrayListExtra(Intent.EXTRA_STREAM, us);
            }
            i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivity(Intent.createChooser(i, "Share with"));
        } catch (Throwable t) {
            say("The file could not be shared.");
        }
    }

    // ------------------------------------------------------------------------------------------------ engine

    @Override
    public void onEngine(int state, String text) {
        if (status == null) return;
        if (state == Engine.PREPARING) {
            busy.setVisibility(View.VISIBLE);
            say(text);
        } else if (state == Engine.FAILED) {
            busy.setVisibility(finding ? View.VISIBLE : View.GONE);
            say(text);
        } else if (state == Engine.READY) {
            busy.setVisibility(finding ? View.VISIBLE : View.GONE);
            if (pendingFind) {
                pendingFind = false;
                find();
            } else if (!finding && media == null) {
                say("Ready. Paste a link and press Find.");
            }
        }
    }

    // ------------------------------------------------------------------------------------------------ menu

    private static final int M_UPDATE = 1, M_ENGINE = 2, M_REPORT = 3, M_ABOUT = 4;

    @Override
    public boolean onCreateOptionsMenu(Menu m) {
        m.add(0, M_UPDATE, 0, "Check for updates");
        m.add(0, M_ENGINE, 1, "Update the download engine");
        m.add(0, M_REPORT, 2, "Send a problem report");
        m.add(0, M_ABOUT, 3, "About Smart Downloader");
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        switch (item.getItemId()) {
            case M_UPDATE: Updates.checkNow(this); return true;
            case M_ENGINE: updateEngine(); return true;
            case M_REPORT: report(null); return true;
            case M_ABOUT: about(); return true;
            default: return super.onOptionsItemSelected(item);
        }
    }

    private void updateEngine() {
        if (DownloadService.busy()) {
            say("Please wait until the downloads finish, then update the download engine.");
            return;
        }
        if (Engine.state() != Engine.READY) {
            say(Engine.text());
            return;
        }
        busy.setVisibility(View.VISIBLE);
        say("Updating the download engine. Please wait.");
        new Thread(new Runnable() {
            @Override public void run() {
                final String r = Engine.update(MainActivity.this);
                runOnUiThread(new Runnable() { @Override public void run() { busy.setVisibility(View.GONE); say(r); } });
            }
        }, "engine-update-now").start();
    }

    private void about() {
        String msg = "Smart Downloader, version " + Updates.myVersionName(this) + ".\n"
                + "Developer: Waqas, Blind World Hub.\n\n"
                + "Download engine: yt-dlp " + Engine.version(this) + ", with FFmpeg.\n\n"
                + "Files are saved in your Download folder, in Smart Downloader. A playlist gets its own folder there.\n\n"
                + "Please download only what you have the right to save.";
        new AlertDialog.Builder(this).setTitle("About Smart Downloader").setMessage(msg)
                .setPositiveButton("OK", null)
                .setNeutralButton("Visit the website", new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int w) {
                        try { startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse("https://blindworldhub.github.io/"))); } catch (Throwable ignored) { }
                    }
                }).show();
    }

    private void reportJob(final Job j) {
        report("Download failed: " + j.error + "\nLink: " + j.url + "\nChoice: " + j.choice + (j.playlist ? " (whole playlist)" : "")
                + "\nWhat yt-dlp said:\n" + j.errorDetail);
    }

    private void report(final String about) {
        final EditText words = new EditText(this);
        words.setHint("What went wrong? (you can leave this empty)");
        words.setMinLines(3);
        words.setGravity(Gravity.TOP | Gravity.START);
        LinearLayout box = new LinearLayout(this);
        box.setPadding(dp(20), dp(8), dp(20), 0);
        box.addView(words, new LinearLayout.LayoutParams(-1, -2));
        new AlertDialog.Builder(this)
                .setTitle("Send a problem report")
                .setMessage("The report goes to the developer with your phone model, the Android version and the app's log"
                        + (about != null ? ", and the link that failed" : "") + ". Nothing else is sent.")
                .setView(box)
                .setPositiveButton("Send", new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int w) {
                        final String text = (words.getText().toString().trim().length() > 0 ? "The person wrote: " + words.getText().toString().trim() + "\n\n" : "")
                                + (about == null ? "" : about);
                        say("Sending the report...");
                        new Thread(new Runnable() {
                            @Override public void run() {
                                final String r = Hub.report(MainActivity.this, about == null ? "sent by the person from the menu" : "a download failed", text);
                                runOnUiThread(new Runnable() {
                                    @Override public void run() { say(r == null ? "Thank you. The report was sent to the developer." : r); }
                                });
                            }
                        }, "report").start();
                    }
                })
                .setNegativeButton("Cancel", null).show();
    }

    private void askPoll(final Hub.Poll p) {
        if (isFinishing()) return;
        Hub.pollShown(this, p);
        TextView q = new TextView(this);
        q.setText("A question from the developer:\n" + p.question);
        q.setTextSize(18);
        q.setTextColor(TEXT);
        q.setTypeface(Typeface.DEFAULT_BOLD);
        q.setPadding(dp(24), dp(20), dp(24), dp(8));
        if (Build.VERSION.SDK_INT >= 28) q.setAccessibilityHeading(true);
        new AlertDialog.Builder(this)
                .setCustomTitle(q)
                .setItems(p.options, new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, final int which) {
                        new Thread(new Runnable() {
                            @Override public void run() {
                                final boolean ok = Hub.vote(MainActivity.this, p, which);
                                runOnUiThread(new Runnable() {
                                    @Override public void run() {
                                        Updates.toast(MainActivity.this, ok ? "Thank you, your answer was sent." : "Your answer could not be sent. Please check the internet.");
                                    }
                                });
                            }
                        }, "vote").start();
                    }
                })
                .setNegativeButton("Not now", null).show();
    }

    private void hideKeyboard() {
        InputMethodManager im = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
        if (im != null) im.hideSoftInputFromWindow(link.getWindowToken(), 0);
    }

}
