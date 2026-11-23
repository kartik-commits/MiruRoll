# <img src="assets/logo.png" width="36" height="36" alt="MiruRoll Logo" valign="middle" /> MiruRoll

MiruRoll is an open-source Crunchyroll extension built for [**Animiru (Media3 Widevine Fork)**](https://github.com/kartik-commits/Animiru-fork), enabling native multi-season browsing and hardware-accelerated Widevine DRM stream playback through AndroidX Media3.

> **Important:**
> - **Subscription:** An active **Crunchyroll subscription** is required to stream subscriber-only and simulcast anime. This extension does not bypass DRM or paywalls—it provides a customized, legal viewing client for paying subscribers.
> - **Compatible App:** This extension requires [**Animiru Fork (with Media3 DRM support)**](https://github.com/kartik-commits/Animiru-fork). Upstream Animiru lacks Widevine CDM decryption pipelines and cannot play protected streams.

---

## Why MiruRoll?

### Legal Streaming for Power Users
MiruRoll is strictly **not** a piracy tool or unauthorized scraper. It communicates directly with official Crunchyroll APIs and decrypts protected streams using Android's native Widevine DRM CDM and official OAuth authentication. All content security and licensing boundaries are fully preserved.

### Merits for Crunchyroll
By providing a legal, authenticated client for open-source media aggregators, MiruRoll gives fans and power users a reason to maintain active Crunchyroll subscriptions instead of resorting to third-party sites.

---

## Features

- **Direct Crunchyroll streams**: Play original, full-quality video streams directly from Crunchyroll servers.
- **Unified series**: All seasons, OVAs and movies grouped under one show instead of cluttering search with duplicate dub entries.
- **In-player audio switching**: Swap between Japanese audio and your preferred dubs (English, Hindi, etc.) on the fly without reloading.
- **Two-way watch history**: Start on your TV or browser and pick up where you left off in Animiru and vice versa. Marking episodes watched or unwatched stays in sync.
- **Profile switching & PIN support**: Switch between family/personal profiles, with support for 4-digit PIN-protected profiles.
- **Flexible sign-in**: In-app browser login with CAPTCHA and 2FA support, plus standard email/password or guest browsing.
- **Custom subtitles**: Full control over subtitle fonts, sizing, colors and positioning.
- **Tracker integration**: Automatic progress syncing with AniList, MyAnimeList, Kitsu and others.

---

## Roadmap

- [ ] **Watchlist sync**: Browse and manage your Crunchyroll queue directly from your library.
- [ ] **Continue Watching shelf**: A dedicated row to quickly resume in-progress shows.
- [ ] **Dub & season filters**: Filter catalog searches by audio language (e.g. Hindi dubs) or release season.
- [ ] **Skip intro/outro**: One-tap buttons to skip openings and endings.
- [ ] ...plus many more features to come.

---

## Installation

### Easy Install (Recommended)

1. Open **Animiru**.
2. Navigate to **More → Settings → Browse → Extension Repositories**.
3. Tap **Add repository** and paste:

```text
https://raw.githubusercontent.com/kartik-commits/miru-extension/main/index.min.json
```

4. Go to **Browse → Extensions** and tap **Install** next to MiruRoll.

---

### Manual Build from Source

If you prefer building the extension yourself:

```bash
# Build the release APK
./gradlew assembleRelease

# Install on your device via ADB
adb install -r app/build/outputs/apk/release/app-release.apk
```

Once installed, go to **Browse → Extensions** in Animiru and tap **Trust** when prompted.

---

## How to Use & Authentication

Once installed, **MiruRoll** will appear in [**Animiru Fork**](https://github.com/kartik-commits/Animiru-fork) under **Browse → Sources**. If prompted under **Browse → Extensions**, ensure the extension is marked as **Trusted**.

### Authentication Methods
MiruRoll functions in **Guest Mode** by default with no account required for free catalog content. For subscriber access to premium and simulcast streams, choose either of the two login methods:

#### Method 1: WebView Login (Recommended)
1. In Animiru, open the **MiruRoll** source.
2. Tap the WebView icon in the top app bar to open the built-in browser.
3. Sign in to your Crunchyroll account (this method easily handles Cloudflare verification and 2FA security codes).
4. Return to Animiru once signed in. MiruRoll automatically captures your session cookie and keeps you authenticated.

#### Method 2: Email & Password Direct Login
1. In Animiru, navigate to **Browse → Extensions → MiruRoll → Settings**.
2. Enter your **MiruRoll Account Email**.
3. Enter your **MiruRoll Password**.
4. The extension will authenticate via official OAuth and acquire subscriber access tokens.

*(Optional)*: If your account uses 2FA and you prefer manual token entry, you can paste your Crunchyroll `refresh_token` into the **Custom Refresh Token** field in settings.

---

## Contributing

Contributions, bug reports, and pull requests are welcome.

1. **Reporting Issues:** When opening an issue, provide relevant logcat output from Animiru **(ensure any email addresses, passwords, or session tokens are redacted).**
2. **Submitting Pull Requests:**
   - Fork the repository and create your feature branch.
   - Maintain code consistency with existing Kotlin architecture.
   - Verify that the release build compiles with zero errors before submitting:
     ```bash
     ./gradlew assembleRelease
     ```

---

## Disclaimer & Trademarks

- **Non-Affiliation:** MiruRoll is an independent, open-source project and is not affiliated with, endorsed, sponsored, or approved by Crunchyroll, LLC, Sony Pictures Entertainment, or Sony Group Corporation. "Crunchyroll" and related trademarks, logos, and service marks are the property of their respective owners.
- **Content Hosting:** MiruRoll does not host, store, stream, or distribute any media files or copyright-protected content. All playback requests are resolved directly from official servers under standard Widevine DRM protection and require legitimate user authorization.
- **Compliance:** Users are responsible for adhering to the applicable terms of service and laws when accessing streaming services through this client.
- **Takedown Requests & Inquiries:** If you are a rights holder or representative of Crunchyroll, LLC or Sony Group Corporation and have concerns regarding this repository or believe any content infringes upon your rights, please contact the maintainer directly at [lashkare@duck.com](mailto:lashkare@duck.com). Any verifiable concerns will be addressed immediately in good faith, and the repository or specific materials will be taken down or modified promptly upon request.

---

## License

This project is licensed under the [Apache License 2.0](LICENSE).


