package com.blindworldhub.smartdownloader;

import android.content.Context;
import android.webkit.CookieManager;

import com.yausername.youtubedl_android.YoutubeDLRequest;

import java.io.File;
import java.io.FileOutputStream;

/**
 * Signing in to Instagram or Facebook, only when the person wants it (it is never needed for public videos). The
 * person signs in on the site's own page inside the app (LoginActivity); the site's cookies are then kept in a file
 * inside the app, which only yt-dlp reads, and only for that site's links. Nothing is sent anywhere else. Sign out
 * deletes the file and the cookies.
 */
final class Login {
    private Login() { }

    static final String INSTAGRAM = "instagram", FACEBOOK = "facebook";

    static String name(String site) { return INSTAGRAM.equals(site) ? "Instagram" : "Facebook"; }

    static String page(String site) {
        return INSTAGRAM.equals(site) ? "https://www.instagram.com/accounts/login/" : "https://m.facebook.com/login/";
    }

    private static String[] hosts(String site) {
        return INSTAGRAM.equals(site) ? new String[] { "https://www.instagram.com", "https://instagram.com", "https://i.instagram.com" }
                : new String[] { "https://www.facebook.com", "https://m.facebook.com", "https://facebook.com", "https://web.facebook.com" };
    }

    private static String domain(String site) { return INSTAGRAM.equals(site) ? ".instagram.com" : ".facebook.com"; }

    static File file(Context c, String site) { return new File(c.getFilesDir(), "cookies-" + site + ".txt"); }

    static boolean signedIn(Context c, String site) { return file(c, site).length() > 60; }

    /** The cookie a signed-in session has (Instagram: sessionid, Facebook: c_user). */
    static boolean pageSignedIn(String site) {
        String all = cookies(site);
        return all.contains(INSTAGRAM.equals(site) ? "sessionid=" : "c_user=");
    }

    private static String cookies(String site) {
        StringBuilder b = new StringBuilder();
        try {
            CookieManager cm = CookieManager.getInstance();
            for (String h : hosts(site)) {
                String s = cm.getCookie(h);
                if (s != null) b.append(s).append("; ");
            }
        } catch (Throwable ignored) { }
        return b.toString();
    }

    /** Writes the site's cookies as a cookies.txt file (the Netscape kind that yt-dlp reads). */
    static boolean save(Context c, String site) {
        String all = cookies(site);
        java.util.LinkedHashMap<String, String> kv = new java.util.LinkedHashMap<String, String>();
        for (String part : all.split(";")) {
            String p = part.trim();
            int eq = p.indexOf('=');
            if (eq <= 0) continue;
            kv.put(p.substring(0, eq).trim(), p.substring(eq + 1).trim());
        }
        if (kv.isEmpty()) return false;
        long until = System.currentTimeMillis() / 1000 + 365L * 86400;
        StringBuilder out = new StringBuilder("# Netscape HTTP Cookie File\n");
        for (java.util.Map.Entry<String, String> e : kv.entrySet()) {
            out.append(domain(site)).append("\tTRUE\t/\tTRUE\t").append(until).append('\t')
                    .append(e.getKey()).append('\t').append(e.getValue()).append('\n');
        }
        try {
            FileOutputStream f = new FileOutputStream(file(c, site));
            try { f.write(out.toString().getBytes("UTF-8")); } finally { f.close(); }
            AppLog.info(c, "Login", "signed in to " + name(site));
            return true;
        } catch (Throwable t) {
            AppLog.error(c, "Login", "could not keep the sign-in", t);
            return false;
        }
    }

    static void signOut(Context c, String site) {
        //noinspection ResultOfMethodCallIgnored
        file(c, site).delete();
        try {
            CookieManager cm = CookieManager.getInstance();
            for (String h : hosts(site)) {
                String s = cm.getCookie(h);
                if (s == null) continue;
                for (String part : s.split(";")) {
                    int eq = part.indexOf('=');
                    if (eq > 0) cm.setCookie(h, part.substring(0, eq).trim() + "=; Max-Age=0; Path=/; Domain=" + domain(site));
                }
            }
            cm.flush();
        } catch (Throwable ignored) { }
        AppLog.info(c, "Login", "signed out of " + name(site));
    }

    /** For a link of a site the person signed in to: yt-dlp uses that sign-in. */
    static void addCookies(Context c, YoutubeDLRequest r, String url) {
        String site = Formats.loginSite(url);
        if (site != null && signedIn(c, site)) r.addOption("--cookies", file(c, site).getAbsolutePath());
    }
}
