# Reddit post draft

**Suggested title:** I built a standalone Android TV companion for Nuvio Re-Shaped — looks best with Liquid Launcher TV

Hi everyone,

I’ve been working on **Nuvio TV Relay**, a small standalone Android TV / Google TV companion for Nuvio Re-Shaped.

The goal is to sync supported catalog metadata into Android TV’s Watch Next / recommendation surface, with poster artwork and links that open the matching title in Nuvio Re-Shaped.

**What it currently does**
- Runs as a separate app; it does not replace or patch Nuvio Re-Shaped.
- Has a Connect workflow using Nuvio Re-Shaped’s Manage from phone (QR) screen.
- Loads catalog entries and enables automatic synchronization / rotation.
- Publishes catalog metadata and deep links; it is not a video player and does not proxy or transcode streams.

**Best experience: Liquid Launcher TV**
For my setup, the best experience is with **Liquid Launcher TV**. The screenshots below are from the launcher home screen and the relay app — no screenshots from inside Nuvio are included.

Launcher discussion: https://www.reddit.com/r/AndroidTVApps/s/5IGzeXuLrm

**Screenshots**

![Liquid Launcher TV home screen with poster rows](https://raw.githubusercontent.com/mohammadmrh12/nuvio-tv-relay-standalone/main/docs/screenshots/liquid-launcher-home.jpg)

![Nuvio TV Relay app](https://raw.githubusercontent.com/mohammadmrh12/nuvio-tv-relay-standalone/main/docs/screenshots/relay-app.jpg)

**Source and APK:** https://github.com/mohammadmrh12/nuvio-tv-relay-standalone/releases/tag/v0.1.0-debug

This is an experimental MVP and the APK is currently a debug build, not a production-signed release. Launcher behavior and catalog compatibility still need testing across devices.

I’d appreciate feedback on compatibility, catalog behavior, and which Android TV launchers you’ve tested. Please don’t post private add-on URLs or credentials.

*Posting note: check the target subreddit’s rules first, and disclose that this is your own project. If self-promotion is restricted, ask the moderators before posting.*
