package com.blindworldhub.smartdownloader;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.DownloadManager;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ShortcutInfo;
import android.content.pm.ShortcutManager;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Insets;
import android.graphics.Typeface;
import android.graphics.drawable.Icon;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.DocumentsContract;
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
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;

/**
 * The one screen. At the top: search YouTube, or paste or share a link and press Find. Then: Video or Audio, the
 * file type, the quality, and Download. Below: the downloads (kept, also after the app is closed), each with Pause,
 * Resume, Cancel, Open, Share, Rename, Try again, Report and Remove. Every control has a clear name for screen
 * readers, headings mark the parts, and the same screen is clear to see for sighted people.
 */
public final class MainActivity extends Activity implements Engine.Listener, DownloadService.Listener {

    private static final int REQ_NOTIFY = 1, REQ_STORAGE = 2;
    private static final int PRIMARY = 0xFF0B5CAD, TEXT = 0xFF14202E, MUTED = 0xFF4A5A6C;
    static final String DEVELOPER = "Blind World Hub by Muhammad Waqas";

    private EditText link, words;
    private Button findBtn, searchBtn, downloadBtn, clearBtn;
    private TextView status, title, details, typeHead, qualityHead, noJobs, searchHead;
    private ProgressBar busy;
    private LinearLayout result, jobsBox, resultsBox;
    private CheckBox wholePlaylist, cover, subtitles;
    private RadioGroup kinds, types, qualities;
    private RadioButton videoKind, audioKind;
    private ScrollView scroll;

    private Formats.Media media, videoMedia;
    private String mediaUrl = "";
    private boolean finding, searching, pendingFind, pollAsked, filling;
    private String pendingSearch;
    private static long clipSeen = -1;
    private Job pendingJob;
    private final Map<String, JobViews> shown = new HashMap<String, JobViews>();

    // ------------------------------------------------------------------------------------------------ screen

    @Override
    protected void onCreate(Bundle saved) {
        super.onCreate(saved);
        DownloadService.load(this);
        setContentView(build());
        Engine.listen(this);
        DownloadService.listen(this);
        Engine.start(this);
        onEngine(Engine.state(), Engine.text());
        onJobs();
        askNotifications();
        MessageJob.schedule(this);
        Updates.checkOnOpen(this);
        shareShortcut();
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
        Updates.resumed(this);
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

    /** Android 10 and newer let an app read the copied text only while it is on the screen (has the focus). */
    @Override
    public void onWindowFocusChanged(boolean focus) {
        super.onWindowFocusChanged(focus);
        if (focus) link.postDelayed(new Runnable() { @Override public void run() { offerCopiedLink(); } }, 400);
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
        bar.setSubtitle("Developer: " + DEVELOPER);
        bar.setTitleTextColor(Color.WHITE);
        bar.setSubtitleTextColor(0xFFDCE8F5);
        root.addView(bar, new LinearLayout.LayoutParams(-1, -2));
        setActionBar(bar);
        if (bar.getOverflowIcon() != null) bar.getOverflowIcon().setTint(Color.WHITE);

        scroll = new ScrollView(this);
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
        link.setHint("Paste a link from YouTube, TikTok, Instagram, Facebook or another site");
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
        wholePlaylist = check(result, "Download the whole playlist that this video is in", false);
        wholePlaylist.setVisibility(View.GONE);
        wholePlaylist.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override public void onCheckedChanged(CompoundButton b, boolean on) { playlistChanged(on); }
        });

        heading(result, "What do you want to download?");
        kinds = new RadioGroup(this);
        kinds.setOrientation(RadioGroup.HORIZONTAL);
        videoKind = radio(kinds, "Video");
        audioKind = radio(kinds, "Audio");
        result.addView(kinds, new LinearLayout.LayoutParams(-1, -2));
        kinds.setOnCheckedChangeListener(new RadioGroup.OnCheckedChangeListener() {
            @Override public void onCheckedChanged(RadioGroup g, int id) {
                Prefs.setLastKind(MainActivity.this, id == audioKind.getId() ? 1 : 0);
                fillTypes();
            }
        });

        typeHead = heading(result, "File type");
        types = new RadioGroup(this);
        types.setOrientation(RadioGroup.VERTICAL);
        result.addView(types, new LinearLayout.LayoutParams(-1, -2));
        types.setOnCheckedChangeListener(new RadioGroup.OnCheckedChangeListener() {
            @Override public void onCheckedChanged(RadioGroup g, int id) {
                if (filling) return;
                View v = g.findViewById(id);
                if (v != null && v.getTag() instanceof String) {
                    Prefs.setLastType(MainActivity.this, isAudio(), (String) v.getTag());
                    fillQualities();
                }
            }
        });

        qualityHead = heading(result, "Choose the quality");
        qualities = new RadioGroup(this);
        qualities.setOrientation(RadioGroup.VERTICAL);
        result.addView(qualities, new LinearLayout.LayoutParams(-1, -2));
        qualities.setOnCheckedChangeListener(new RadioGroup.OnCheckedChangeListener() {
            @Override public void onCheckedChanged(RadioGroup g, int id) {
                if (filling) return;
                View v = g.findViewById(id);
                if (v != null && v.getTag() instanceof Formats.Option) {
                    Formats.Option o = (Formats.Option) v.getTag();
                    Prefs.setLastQuality(MainActivity.this, o.type, o.key);
                }
            }
        });

        cover = check(result, "Add the cover picture to the audio file (MP3 and M4A)", Prefs.cover(this));
        cover.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override public void onCheckedChanged(CompoundButton b, boolean on) { Prefs.setCover(MainActivity.this, on); }
        });
        subtitles = check(result, "Also save the subtitles, when the video has them (English, Urdu, Hindi, Arabic)", Prefs.subtitles(this));
        subtitles.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override public void onCheckedChanged(CompoundButton b, boolean on) { Prefs.setSubtitles(MainActivity.this, on); }
        });

        downloadBtn = button(result, "Download", true);
        ((LinearLayout.LayoutParams) downloadBtn.getLayoutParams()).topMargin = dp(12);
        downloadBtn.setMinHeight(dp(56));
        downloadBtn.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) { download(); } });

        // 3. search YouTube
        LinearLayout c2 = card(page);
        searchHead = heading(c2, "Search YouTube");
        words = new EditText(this);
        words.setId(View.generateViewId());
        searchHead.setLabelFor(words.getId());
        words.setHint("Write what to search for");
        words.setSingleLine(true);
        words.setInputType(InputType.TYPE_CLASS_TEXT);
        words.setImeOptions(EditorInfo.IME_ACTION_SEARCH);
        words.setTextSize(17);
        words.setMinHeight(dp(52));
        words.setOnEditorActionListener(new TextView.OnEditorActionListener() {
            @Override public boolean onEditorAction(TextView v, int id, KeyEvent e) { search(); return true; }
        });
        c2.addView(words, new LinearLayout.LayoutParams(-1, -2));
        searchBtn = button(c2, "Search", true);
        ((LinearLayout.LayoutParams) searchBtn.getLayoutParams()).topMargin = dp(6);
        searchBtn.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) { search(); } });
        resultsBox = new LinearLayout(this);
        resultsBox.setOrientation(LinearLayout.VERTICAL);
        c2.addView(resultsBox, new LinearLayout.LayoutParams(-1, -2));

        // 4. the downloads
        LinearLayout c3 = card(page);
        heading(c3, "Downloads");
        LinearLayout drow = row(c3);
        Button folder = button(drow, "Open the folder", false);
        folder.setContentDescription("Open the Smart Downloader folder, in Download");
        folder.setTextSize(14);
        folder.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) { openFolder(); } });
        clearBtn = button(drow, "Remove finished", false);
        clearBtn.setContentDescription("Remove the finished downloads from this list. The saved files stay.");
        clearBtn.setTextSize(14);
        clearBtn.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) { clearFinished(); } });
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

    private CheckBox check(LinearLayout parent, String s, boolean on) {
        CheckBox b = new CheckBox(this);
        b.setText(s);
        b.setTextSize(16);
        b.setMinHeight(dp(48));
        b.setChecked(on);
        parent.addView(b, new LinearLayout.LayoutParams(-1, -2));
        return b;
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

    private boolean isAudio() { return kinds.getCheckedRadioButtonId() == audioKind.getId(); }

    // ------------------------------------------------------------------------------------------------ the link

    private void handle(Intent i) {
        if (i == null) return;
        if (i.getBooleanExtra("checkUpdate", false)) Updates.checkNow(this);
        if (Intent.ACTION_SEND.equals(i.getAction())) {
            String shared = i.getStringExtra(Intent.EXTRA_TEXT);
            if (shared != null && shared.trim().length() > 0) {
                String l = Formats.findLink(shared);
                Prefs.setOfferedLink(this, l);               // not offered again from the copied text
                link.setText(l);
                find();
            }
        }
    }

    private CharSequence clip() {
        try {
            ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
            ClipData cd = cm == null ? null : cm.getPrimaryClip();
            return cd != null && cd.getItemCount() > 0 ? cd.getItemAt(0).coerceToText(this) : null;
        } catch (Throwable t) {
            return null;
        }
    }

    private void paste() {
        CharSequence t = clip();
        if (t == null || t.toString().trim().length() == 0) {
            say("Nothing to paste. Copy a link first, for example with Share and then Copy link in YouTube.");
            return;
        }
        String l = Formats.findLink(t.toString());
        Prefs.setOfferedLink(this, l);
        link.setText(l);
        link.setSelection(link.getText().length());
        say("Link pasted. Press Find.");
    }

    /** A video link was copied (in YouTube, TikTok, Instagram or Facebook): offer it once. */
    private void offerCopiedLink() {
        if (isFinishing() || !hasWindowFocus() || finding) return;
        // Newer Android says "pasted from your clipboard" each time the copied text is read: read it only when
        // something new was copied (the time of the copy is known without reading it).
        try {
            ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
            android.content.ClipDescription d = cm == null ? null : cm.getPrimaryClipDescription();
            if (d == null || !d.hasMimeType("text/*")) return;
            long when = d.getTimestamp();
            if (when != 0 && when == clipSeen) return;
            clipSeen = when;
        } catch (Throwable t) {
            return;
        }
        CharSequence t = clip();
        if (t == null) return;
        final String l = Formats.onlyLink(t.toString());
        if (l.length() == 0 || !Formats.knownSite(l) || l.equals(Prefs.offeredLink(this))) return;
        if (l.equals(Formats.findLink(link.getText().toString())) || l.equals(mediaUrl)) return;
        Prefs.setOfferedLink(this, l);
        new AlertDialog.Builder(this)
                .setTitle("A copied link")
                .setMessage("You copied this link:\n" + l + "\n\nDo you want to download from it?")
                .setPositiveButton("Find it", new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int w) { link.setText(l); find(); }
                })
                .setNegativeButton("No", null)
                .show();
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
                    m = Formats.parse(Engine.info(MainActivity.this, url, playlist));
                } catch (Throwable t) {
                    err = Engine.plain(t.getMessage());
                    AppLog.error(MainActivity.this, "Find", "could not read " + url, t);
                }
                final Formats.Media fm = m;
                final String fe = err;
                runOnUiThread(new Runnable() {
                    @Override public void run() {
                        finding = false;
                        busy.setVisibility(searching ? View.VISIBLE : View.GONE);
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
                try { m = Formats.parse(Engine.info(MainActivity.this, url, true)); } catch (Throwable t) {
                    err = Engine.plain(t.getMessage());
                    AppLog.error(MainActivity.this, "Find", "could not read the playlist " + url, t);
                }
                final Formats.Media fm = m;
                final String fe = err;
                runOnUiThread(new Runnable() {
                    @Override public void run() {
                        finding = false;
                        busy.setVisibility(searching ? View.VISIBLE : View.GONE);
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
        if (kinds.getCheckedRadioButtonId() != want) kinds.check(want); else fillTypes();
        say("Found. Choose Video or Audio, then the file type and the quality, then press " + downloadBtn.getText() + ".");
        if (moveFocus) title.post(new Runnable() {
            @Override public void run() {
                scroll.smoothScrollTo(0, Math.max(0, result.getTop() - dp(8)));
                title.performAccessibilityAction(AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS, null);
            }
        });
    }

    private void fillTypes() {
        filling = true;
        types.removeAllViews();
        if (media == null) { filling = false; return; }
        boolean audio = isAudio();
        Map<String, List<Formats.Option>> all = audio ? media.audio : media.video;
        typeHead.setText(audio ? "Audio file type" : "Video file type");
        String want = Prefs.lastType(this, audio);
        if (!all.containsKey(want)) want = all.keySet().iterator().next();
        for (String t : all.keySet()) {
            RadioButton r = radio(types, typeName(t));
            r.setTag(t);
            if (t.equals(want)) types.check(r.getId());
        }
        cover.setVisibility(audio ? View.VISIBLE : View.GONE);
        subtitles.setVisibility(audio ? View.GONE : View.VISIBLE);
        filling = false;
        fillQualities();
    }

    private static String typeName(String t) {
        if ("MP3".equals(t)) return "MP3 (plays everywhere)";
        if ("M4A".equals(t)) return "M4A";
        if ("AAC".equals(t)) return "AAC";
        if ("OPUS".equals(t)) return "OPUS (small files)";
        if ("OGG".equals(t)) return "OGG";
        if ("WAV".equals(t)) return "WAV (lossless, very big)";
        if ("FLAC".equals(t)) return "FLAC (lossless, big)";
        if ("MP4".equals(t)) return "MP4 (plays everywhere)";
        if ("MKV".equals(t)) return "MKV";
        if ("WEBM".equals(t)) return "WEBM";
        if ("3GP".equals(t)) return "3GP (small, for old phones)";
        return t;
    }

    private String chosenType() {
        View v = types.findViewById(types.getCheckedRadioButtonId());
        return v != null && v.getTag() instanceof String ? (String) v.getTag() : null;
    }

    private void fillQualities() {
        filling = true;
        qualities.removeAllViews();
        String t = chosenType();
        if (media == null || t == null) { filling = false; return; }
        boolean audio = isAudio();
        List<Formats.Option> list = (audio ? media.audio : media.video).get(t);
        if (list == null) list = Collections.emptyList();
        qualityHead.setText(audio ? "Audio quality" : "Video quality");
        String want = Prefs.lastQuality(this, t);
        int chosen = -1, first = -1;
        for (int i = 0; i < list.size(); i++) {
            RadioButton r = radio(qualities, list.get(i).label);
            r.setTag(list.get(i));
            if (first < 0) first = r.getId();
            if (chosen < 0 && list.get(i).key.equals(want)) chosen = r.getId();
        }
        if (chosen < 0) chosen = first;
        if (chosen >= 0) qualities.check(chosen);
        cover.setEnabled("MP3".equals(t) || "M4A".equals(t));
        filling = false;
    }

    // ------------------------------------------------------------------------------------------------ search

    private void search() {
        final String w = words.getText().toString().trim();
        hideKeyboard();
        if (w.length() == 0) {
            say("Please write what to search for.");
            words.requestFocus();
            return;
        }
        if (searching) return;
        if (Engine.state() != Engine.READY) {
            pendingSearch = w;
            say(Engine.state() == Engine.FAILED ? Engine.text() : "Please wait: " + Engine.text() + " The search starts when it is ready.");
            return;
        }
        searching = true;
        searchBtn.setEnabled(false);
        busy.setVisibility(View.VISIBLE);
        resultsBox.removeAllViews();
        say("Searching YouTube for " + w + ". Please wait.");
        new Thread(new Runnable() {
            @Override public void run() {
                List<Formats.Found> list = null;
                String err = null;
                try {
                    list = Formats.searchResults(Engine.search(w, 20));
                } catch (Throwable t) {
                    err = Engine.plain(t.getMessage());
                    AppLog.error(MainActivity.this, "Search", "could not search", t);
                }
                final List<Formats.Found> fl = list;
                final String fe = err;
                runOnUiThread(new Runnable() {
                    @Override public void run() {
                        searching = false;
                        searchBtn.setEnabled(true);
                        busy.setVisibility(finding ? View.VISIBLE : View.GONE);
                        if (fl == null) { say(fe); return; }
                        showResults(w, fl);
                    }
                });
            }
        }, "search").start();
    }

    private void showResults(String w, List<Formats.Found> list) {
        resultsBox.removeAllViews();
        if (list.isEmpty()) {
            say("Nothing was found for " + w + ". Try other words.");
            return;
        }
        final TextView head = heading(resultsBox, list.size() + " results for " + w);
        for (final Formats.Found f : list) {
            Button b = new Button(this);
            b.setAllCaps(false);
            b.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
            String d = Formats.duration(f.duration);
            b.setText(f.title + (f.by.length() > 0 || d.length() > 0 ? "\n" + f.by + (f.by.length() > 0 && d.length() > 0 ? ", " : "") + d : ""));
            b.setContentDescription(f.spoken());
            b.setTextSize(15);
            b.setMinHeight(dp(56));
            b.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) {
                    link.setText(f.url);
                    Prefs.setOfferedLink(MainActivity.this, f.url);
                    find();
                }
            });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
            lp.topMargin = dp(4);
            resultsBox.addView(b, lp);
        }
        say(list.size() + " results. Choose one to download it.");
        head.post(new Runnable() {
            @Override public void run() { head.performAccessibilityAction(AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS, null); }
        });
    }

    // ------------------------------------------------------------------------------------------------ download

    private void download() {
        if (media == null) return;
        View v = qualities.findViewById(qualities.getCheckedRadioButtonId());
        if (v == null || !(v.getTag() instanceof Formats.Option)) {
            say("Please choose a quality first.");
            return;
        }
        final Formats.Option o = (Formats.Option) v.getTag();
        List<String> args = new ArrayList<String>(o.args);
        String extra = "";
        if (o.audio && cover.isChecked() && ("MP3".equals(o.type) || "M4A".equals(o.type))) {
            args.add("--embed-thumbnail");
            args.add("--convert-thumbnails");
            args.add("jpg");
            extra = ", with the cover picture";
        }
        if (!o.audio && subtitles.isChecked()) {
            args.add("--write-subs");
            args.add("--write-auto-subs");
            args.add("--sub-langs");
            args.add("en.*,ur.*,hi.*,ar.*,-live_chat");
            args.add("--convert-subs");
            args.add("srt");
            extra = ", with subtitles";
        }
        final String choice = o.type + ", " + o.label + extra;
        final Job j = new Job("j" + System.currentTimeMillis(), mediaUrl, media.title, choice, media.playlist, o.audio, args, o.bytes,
                System.currentTimeMillis());
        Job same = DownloadService.same(mediaUrl, choice);
        if (same != null) {
            String when = same.state == Job.DONE ? "You already downloaded this, with the same choice." : "This is already in the list of downloads, with the same choice.";
            new AlertDialog.Builder(this)
                    .setTitle("Already downloaded")
                    .setMessage(when + "\n" + same.title + "\n" + same.choice + "\n\nDo you want to download it again?")
                    .setPositiveButton("Download again", new DialogInterface.OnClickListener() {
                        @Override public void onClick(DialogInterface d, int w) { checkSpace(j); }
                    })
                    .setNegativeButton("Cancel", null).show();
            return;
        }
        checkSpace(j);
    }

    /** The phone needs room for the download and for the finished file (about twice the size, for a moment). */
    private void checkSpace(final Job j) {
        long free = DownloadService.freeBytes(this);
        long need = j.bytes > 0 ? j.bytes * 2 + 50L * 1024 * 1024 : 200L * 1024 * 1024;
        if (free >= 0 && free < need) {
            new AlertDialog.Builder(this)
                    .setTitle("Not enough free space")
                    .setMessage("This download needs about " + Formats.size(need) + " of free space for a moment"
                            + (j.bytes > 0 ? " (the file is about " + Formats.size(j.bytes) + ")" : "")
                            + ". The phone has " + Formats.size(free) + " free.\n\nFree some space, or choose a smaller quality.")
                    .setPositiveButton("Download anyway", new DialogInterface.OnClickListener() {
                        @Override public void onClick(DialogInterface d, int w) { start(j); }
                    })
                    .setNegativeButton("Cancel", null).show();
            return;
        }
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
        String name = "";
    }

    @Override
    public void onJobs() {
        if (jobsBox == null) return;
        List<Job> jobs = DownloadService.jobs();
        noJobs.setVisibility(jobs.isEmpty() ? View.VISIBLE : View.GONE);
        boolean anyFinished = false;
        HashSet<String> ids = new HashSet<String>();
        for (Job j : jobs) { ids.add(j.id); if (j.finished()) anyFinished = true; }
        clearBtn.setVisibility(anyFinished ? View.VISIBLE : View.GONE);
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
            Saver.Saved m = j.main();
            String name = m == null ? "" : m.name;
            if (before != j.state || !name.equals(v.name)) {
                v.state = j.state;
                v.name = name;
                buttons(v, j);
                if (before != -1 && before != j.state && j.finished()) jobsBox.announceForAccessibility(j.title + ". " + j.status());
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
        v.buttons = new LinearLayout(this);
        v.buttons.setOrientation(LinearLayout.VERTICAL);
        v.box.addView(v.buttons, new LinearLayout.LayoutParams(-1, -2));
        View line = new View(this);
        line.setBackgroundColor(0xFFD5DEE8);
        v.box.addView(line, new LinearLayout.LayoutParams(-1, dp(1)));
        ((LinearLayout.LayoutParams) line.getLayoutParams()).topMargin = dp(10);
        return v;
    }

    /** The buttons of one download, two in a row so they stay big enough. */
    private void buttons(JobViews v, final Job j) {
        v.buttons.removeAllViews();
        List<Object[]> b = new ArrayList<Object[]>();
        if (j.state == Job.WAITING || j.state == Job.RUNNING) {
            b.add(new Object[] { "Pause", "Pause the download of " + j.title, new Runnable() {
                @Override public void run() { DownloadService.pause(MainActivity.this, j); } } });
            b.add(new Object[] { "Cancel", "Cancel the download of " + j.title, new Runnable() {
                @Override public void run() { DownloadService.cancel(MainActivity.this, j); } } });
        } else if (j.state == Job.PAUSED) {
            b.add(new Object[] { "Resume", "Resume the download of " + j.title, new Runnable() {
                @Override public void run() { DownloadService.resume(MainActivity.this, j); } } });
            b.add(new Object[] { "Cancel", "Cancel the download of " + j.title + ". What was downloaded is deleted.", new Runnable() {
                @Override public void run() { DownloadService.cancel(MainActivity.this, j); } } });
        } else {
            final Saver.Saved m = j.main();
            if (j.state == Job.DONE && m != null) {
                boolean one = j.mediaCount() == 1;
                if (one) b.add(new Object[] { "Open", "Open " + m.name, new Runnable() { @Override public void run() { open(m); } } });
                b.add(new Object[] { "Share", "Share " + (one ? m.name : "the files of " + j.title), new Runnable() {
                    @Override public void run() { share(j); } } });
                if (one) b.add(new Object[] { "Rename", "Rename " + m.name, new Runnable() { @Override public void run() { rename(j, m); } } });
            }
            if (j.state == Job.FAILED || j.state == Job.CANCELLED) {
                b.add(new Object[] { "Try again", "Try again to download " + j.title, new Runnable() {
                    @Override public void run() { DownloadService.remove(MainActivity.this, j); checkSpace(j.again()); } } });
            }
            if (j.state == Job.FAILED) {
                b.add(new Object[] { "Report", "Send this problem with " + j.title + " to the developer", new Runnable() {
                    @Override public void run() { reportJob(j); } } });
            }
            b.add(new Object[] { "Remove", "Remove " + j.title + " from this list. The saved file stays.", new Runnable() {
                @Override public void run() { DownloadService.remove(MainActivity.this, j); } } });
        }
        LinearLayout r = null;
        for (int i = 0; i < b.size(); i++) {
            if (i % 2 == 0) r = row(v.buttons);
            add(r, (String) b.get(i)[0], (String) b.get(i)[1], (Runnable) b.get(i)[2]);
        }
        if (r != null && b.size() % 2 == 1) {
            View space = new View(this);
            r.addView(space, new LinearLayout.LayoutParams(0, 1, 1));
        }
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
            List<Saver.Saved> files = new ArrayList<Saver.Saved>(j.saved);
            if (files.size() == 1) {
                i = new Intent(Intent.ACTION_SEND).setType(files.get(0).mime).putExtra(Intent.EXTRA_STREAM, files.get(0).uri);
            } else {
                ArrayList<Uri> us = new ArrayList<Uri>();
                for (Saver.Saved s : files) us.add(s.uri);
                i = new Intent(Intent.ACTION_SEND_MULTIPLE).setType("*/*").putParcelableArrayListExtra(Intent.EXTRA_STREAM, us);
            }
            i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivity(Intent.createChooser(i, "Share with"));
        } catch (Throwable t) {
            say("The file could not be shared.");
        }
    }

    private void rename(final Job j, final Saver.Saved s) {
        int dot = s.name.lastIndexOf('.');
        final EditText name = new EditText(this);
        name.setText(dot > 0 ? s.name.substring(0, dot) : s.name);
        name.setSelectAllOnFocus(true);
        name.setSingleLine(true);
        name.setContentDescription("New name");
        LinearLayout box = new LinearLayout(this);
        box.setPadding(dp(20), dp(8), dp(20), 0);
        box.addView(name, new LinearLayout.LayoutParams(-1, -2));
        new AlertDialog.Builder(this)
                .setTitle("Rename the file")
                .setMessage("Write the new name. The file type" + (dot > 0 ? " (" + s.name.substring(dot) + ")" : "") + " stays.")
                .setView(box)
                .setPositiveButton("Rename", new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int w) {
                        try {
                            Saver.Saved n = Saver.rename(MainActivity.this, s, name.getText().toString());
                            synchronized (j.saved) {
                                int at = j.saved.indexOf(s);
                                if (at >= 0) j.saved.set(at, n);
                            }
                            DownloadService.changed(true);
                            say("Renamed: " + n.name);
                        } catch (Throwable t) {
                            AppLog.error(MainActivity.this, "Rename", "could not rename " + s.name, t);
                            say(t.getMessage() == null ? "The file could not be renamed." : t.getMessage());
                        }
                    }
                })
                .setNegativeButton("Cancel", null).show();
    }

    private void clearFinished() {
        int n = DownloadService.clearFinished();
        say(n == 1 ? "1 finished download removed from the list. Its file stays." : n + " finished downloads removed from the list. Their files stay.");
    }

    /** The Smart Downloader folder in a file manager; Android's Downloads screen when that cannot be opened. */
    private void openFolder() {
        try {
            Uri u = DocumentsContract.buildDocumentUri("com.android.externalstorage.documents", "primary:Download/" + Saver.FOLDER);
            Intent i = new Intent(Intent.ACTION_VIEW).setDataAndType(u, DocumentsContract.Document.MIME_TYPE_DIR)
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivity(i);
            return;
        } catch (Throwable ignored) { }
        try {
            startActivity(new Intent(DownloadManager.ACTION_VIEW_DOWNLOADS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            say("Open the Smart Downloader folder there.");
        } catch (Throwable t) {
            say("No file manager opened. Your files are in the Download folder, in Smart Downloader.");
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
            busy.setVisibility(finding || searching ? View.VISIBLE : View.GONE);
            say(text);
        } else if (state == Engine.READY) {
            busy.setVisibility(finding || searching ? View.VISIBLE : View.GONE);
            if (pendingFind) {
                pendingFind = false;
                find();
            } else if (pendingSearch != null) {
                pendingSearch = null;
                search();
            } else if (!finding && !searching && media == null) {
                say("Ready. Paste a link and press Find, or search YouTube below.");
            }
        }
    }

    // ------------------------------------------------------------------------------------------------ menu

    private static final int M_UPDATE = 1, M_ENGINE = 2, M_REPORT = 3, M_ABOUT = 4, M_INSTA = 5, M_FB = 6, M_FOLDER = 7;

    @Override
    public boolean onCreateOptionsMenu(Menu m) {
        m.add(0, M_UPDATE, 0, "Check for updates");
        m.add(0, M_ENGINE, 1, "Update the download engine");
        m.add(0, M_FOLDER, 2, "Open the download folder");
        m.add(0, M_INSTA, 3, "Sign in to Instagram (optional)");
        m.add(0, M_FB, 4, "Sign in to Facebook (optional)");
        m.add(0, M_REPORT, 5, "Send a problem report");
        m.add(0, M_ABOUT, 6, "About Smart Downloader");
        return true;
    }

    /** The sign-in items say Sign out when that site is signed in. */
    @Override
    public boolean onPrepareOptionsMenu(Menu m) {
        MenuItem i = m.findItem(M_INSTA), f = m.findItem(M_FB);
        if (i != null) i.setTitle(Login.signedIn(this, Login.INSTAGRAM) ? "Sign out of Instagram" : "Sign in to Instagram (optional)");
        if (f != null) f.setTitle(Login.signedIn(this, Login.FACEBOOK) ? "Sign out of Facebook" : "Sign in to Facebook (optional)");
        return super.onPrepareOptionsMenu(m);
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        switch (item.getItemId()) {
            case M_UPDATE: Updates.checkNow(this); return true;
            case M_ENGINE: updateEngine(); return true;
            case M_FOLDER: openFolder(); return true;
            case M_INSTA: login(Login.INSTAGRAM); return true;
            case M_FB: login(Login.FACEBOOK); return true;
            case M_REPORT: report(null); return true;
            case M_ABOUT: about(); return true;
            default: return super.onOptionsItemSelected(item);
        }
    }

    private void login(final String site) {
        final String name = Login.name(site);
        if (Login.signedIn(this, site)) {
            new AlertDialog.Builder(this)
                    .setTitle("Sign out of " + name + "?")
                    .setMessage("Smart Downloader will forget the " + name + " sign-in. Public videos still download.")
                    .setPositiveButton("Sign out", new DialogInterface.OnClickListener() {
                        @Override public void onClick(DialogInterface d, int w) {
                            Login.signOut(MainActivity.this, site);
                            say("Signed out of " + name + ".");
                        }
                    })
                    .setNegativeButton("Cancel", null).show();
            return;
        }
        new AlertDialog.Builder(this)
                .setTitle("Sign in to " + name)
                .setMessage("This is not needed for public videos: they download without signing in. Sign in only when "
                        + name + " does not let a video download without it. " + name + "'s own page opens here; your "
                        + "password goes only to " + name + ".")
                .setPositiveButton("Open the sign-in page", new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int w) {
                        startActivity(new Intent(MainActivity.this, LoginActivity.class).putExtra(LoginActivity.SITE, site));
                    }
                })
                .setNegativeButton("Cancel", null).show();
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
                + "Developer: " + DEVELOPER + ".\n\n"
                + "Downloads videos and audio from YouTube, TikTok, Instagram, Facebook and many other sites, on Wi-Fi or "
                + "mobile data. Video: MP4, MKV, WEBM and 3GP. Audio: MP3, M4A, AAC, OPUS, OGG, WAV and FLAC.\n\n"
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

    /**
     * "Download with Smart Downloader" at the top of Android's share screen (Direct Share), so a shared video link
     * reaches the app in one tap. Android decides where it shows; nothing happens on phones that do not show it.
     */
    private void shareShortcut() {
        if (Build.VERSION.SDK_INT < 29) return;
        try {
            ShortcutManager sm = getSystemService(ShortcutManager.class);
            if (sm == null) return;
            HashSet<String> cats = new HashSet<String>();
            cats.add("com.blindworldhub.smartdownloader.category.DOWNLOAD");
            ShortcutInfo.Builder b = new ShortcutInfo.Builder(this, "download")
                    .setShortLabel("Download")
                    .setLongLabel("Download with Smart Downloader")
                    .setIcon(Icon.createWithResource(this, R.drawable.ic_launcher))
                    .setCategories(cats)
                    .setIntent(new Intent(Intent.ACTION_VIEW).setClass(this, MainActivity.class));
            if (Build.VERSION.SDK_INT >= 30) b.setLongLived(true);
            List<ShortcutInfo> list = new ArrayList<ShortcutInfo>();
            list.add(b.build());
            sm.setDynamicShortcuts(list);
        } catch (Throwable t) {
            AppLog.info(this, "Share", "no share shortcut: " + t.getMessage());
        }
    }

    private void hideKeyboard() {
        InputMethodManager im = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
        if (im != null) im.hideSoftInputFromWindow(link.getWindowToken(), 0);
    }

}
