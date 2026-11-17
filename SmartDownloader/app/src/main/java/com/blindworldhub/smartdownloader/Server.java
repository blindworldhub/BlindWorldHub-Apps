package com.blindworldhub.smartdownloader;

/**
 * The Blind World Hub apps server (Cloudflare): new versions, the developer's messages and polls, problem reports.
 * Only "is there something new" is asked; a report is sent only when the person presses Send.
 */
final class Server {
    private Server() { }

    static final String URL = "https://apps.jieshuoscreenreader.workers.dev";
    static final String APP = "smartdownloader";
    static final String UA = "SmartDownloader (Blind World Hub; https://blindworldhub.github.io/)";
    static final String WEBSITE_UPDATE = "https://blindworldhub.github.io/updates/smart-downloader.json";
}
