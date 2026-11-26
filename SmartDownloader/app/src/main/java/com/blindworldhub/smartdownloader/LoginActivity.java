package com.blindworldhub.smartdownloader;

import android.app.Activity;
import android.graphics.Color;
import android.graphics.Insets;
import android.os.Build;
import android.os.Bundle;
import android.view.View;
import android.view.WindowInsets;
import android.webkit.CookieManager;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

/**
 * The site's own sign-in page (Instagram or Facebook), for the person who wants to download a video that the site
 * shows only after signing in. At the top: what to do, and the Done button; below: the site's page.
 */
public final class LoginActivity extends Activity {

    static final String SITE = "site";
    private String site;
    private TextView help;

    @Override
    protected void onCreate(Bundle saved) {
        super.onCreate(saved);
        site = Login.FACEBOOK.equals(getIntent().getStringExtra(SITE)) ? Login.FACEBOOK : Login.INSTAGRAM;
        final String name = Login.name(site);
        setTitle("Sign in to " + name);

        final LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.WHITE);
        final View statusBar = new View(this);
        statusBar.setBackgroundColor(0xFF083F78);
        root.addView(statusBar, new LinearLayout.LayoutParams(-1, 0));

        LinearLayout top = new LinearLayout(this);
        top.setOrientation(LinearLayout.VERTICAL);
        int pad = Math.round(12 * getResources().getDisplayMetrics().density);
        top.setPadding(pad, pad, pad, pad);
        top.setBackgroundColor(0xFFF3F6FA);
        TextView head = new TextView(this);
        head.setText("Sign in to " + name);
        head.setTextSize(19);
        head.setTextColor(0xFF0B5CAD);
        head.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        if (Build.VERSION.SDK_INT >= 28) head.setAccessibilityHeading(true);
        top.addView(head);
        help = new TextView(this);
        help.setText("Below is " + name + "'s own sign-in page. Sign in there as usual. Then come back here and press Done. "
                + "Your password goes only to " + name + "; Smart Downloader keeps only the sign-in on this phone, to download "
                + name + " videos. You can sign out from the menu at any time.");
        help.setTextSize(16);
        help.setTextColor(0xFF14202E);
        top.addView(help);
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        Button done = new Button(this);
        done.setText("Done");
        done.setAllCaps(false);
        done.setContentDescription("Done, I have signed in to " + name);
        Button cancel = new Button(this);
        cancel.setText("Cancel");
        cancel.setAllCaps(false);
        row.addView(done, new LinearLayout.LayoutParams(0, -2, 1));
        row.addView(cancel, new LinearLayout.LayoutParams(0, -2, 1));
        top.addView(row);
        root.addView(top, new LinearLayout.LayoutParams(-1, -2));

        final WebView web = new WebView(this);
        WebSettings ws = web.getSettings();
        ws.setJavaScriptEnabled(true);
        ws.setDomStorageEnabled(true);
        CookieManager.getInstance().setAcceptCookie(true);
        CookieManager.getInstance().setAcceptThirdPartyCookies(web, true);
        web.setWebViewClient(new WebViewClient());
        root.addView(web, new LinearLayout.LayoutParams(-1, 0, 1));
        web.loadUrl(Login.page(site));

        done.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                CookieManager.getInstance().flush();
                if (!Login.pageSignedIn(site)) {
                    help.setText("You are not signed in yet. Sign in on " + name + "'s page below, then press Done. Or press Cancel.");
                    help.announceForAccessibility(help.getText());
                    return;
                }
                boolean ok = Login.save(LoginActivity.this, site);
                Toast.makeText(LoginActivity.this, ok ? "Signed in to " + name + ". You can download its videos now."
                        : "The sign-in could not be kept. Please try again.", Toast.LENGTH_LONG).show();
                if (ok) finish();
            }
        });
        cancel.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) { finish(); } });

        if (Build.VERSION.SDK_INT >= 35) {
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
        setContentView(root);
    }
}
