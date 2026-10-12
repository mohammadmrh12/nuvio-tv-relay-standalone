# Reddit post draft

**Suggested title:** I built a standalone Android TV companion for Nuvio Re-Shaped (catalogs → Watch Next)

Hi everyone,

I’ve been working on **Nuvio TV Relay**, a small standalone Android TV / Google TV companion for Nuvio Re-Shaped.

The goal is to sync supported catalog metadata into the TV’s Watch Next / recommendation surface, with poster artwork and links that open the matching title in Nuvio Re-Shaped.

**What it currently does**
- Runs as a separate app; it does not replace or patch Nuvio Re-Shaped.
- Has a Connect workflow using Nuvio Re-Shaped’s Manage from phone (QR) screen.
- Loads catalog entries and enables automatic synchronization/rotation.
- Publishes catalog metadata and deep links; it is not a video player and does not proxy or transcode streams.

**Screenshots**

![Nuvio home with multiple posters](https://raw.githubusercontent.com/mohammadmrh12/nuvio-tv-relay-standalone/main/docs/screenshots/nuvio-posters.jpg)

![Relay app screenshot](https://raw.githubusercontent.com/mohammadmrh12/nuvio-tv-relay-standalone/main/docs/screenshots/relay-app.jpg)

![Nuvio detail screenshot](https://raw.githubusercontent.com/mohammadmrh12/nuvio-tv-relay-standalone/main/docs/screenshots/nuvio-matrix-detail.jpg)

![Second Nuvio screenshot](https://raw.githubusercontent.com/mohammadmrh12/nuvio-tv-relay-standalone/main/docs/screenshots/nuvio-second-detail.jpg)

**Source and APK:** https://github.com/mohammadmrh12/nuvio-tv-relay-standalone/releases/tag/v0.1.0-debug

This is an experimental MVP and the APK is currently a debug build, not a production-signed release. Launcher behavior and catalog compatibility still need testing across devices.

I’d appreciate feedback on compatibility, catalog behavior, and which Android TV launchers you’ve tested. Please don’t post private add-on URLs or credentials.

*Posting note: check the target subreddit’s rules first, and disclose that this is your own project. If self-promotion is restricted, ask the moderators before posting.*
