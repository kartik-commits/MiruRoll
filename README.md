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
By providing a legal, authenticated client for open-source media aggregators, MiruRoll gives j*bless anime fans and tech-savvy users a reason to maintain active Crunchyroll subscriptions instead of resorting to illicit third-party sites.

### Key Advantages over the Official Client
- **Seamless Tracking:** Automatic progress syncing with AniList, MyAnimeList, Kitsu, and Shikimori.
- **Deep Player Customization:** Granular control over subtitle fonts, colors, outlines, scaling, bottom padding, and ASS(LoL) overrides.
- **Unified Seasons Hierarchy:** Groups anime into clean Parent Series → Seasons → Episodes instead of fragmented duplicate dub/sub listings.
- **Advanced Playback Controls:** Native Picture-in-Picture (PiP), AniSkip intro/outro skipping, and custom hardware gesture mapping.

---

## Build & Installation

### Prerequisites
- Android SDK (API 26+)
- JDK 17 or higher

### 1. Build Extension APK
Clone the repository and compile using Gradle:

```bash
./gradlew :app:assembleRelease
```
Output: `app/build/outputs/apk/release/app-release.apk`

### 2. Install on Device
With an Android device or emulator connected via ADB:
```bash
adb install -r app/build/outputs/apk/release/app-release.apk
```

### 3. Activating in Animiru
1. Open Animiru and go to **Browse → Extensions**.
2. Locate **MiruRoll** (marked as *Untrusted* upon first manual installation).
3. Tap **Trust** in the dialog to grant loading permission and activate the extension.

---

## How to Use & Authentication

Once installed, **MiruRoll** will appear in [**Animiru Fork**](https://github.com/kartik-commits/Animiru-fork) under **Browse → Sources**. If prompted under **Browse → Extensions**, ensure the extension is marked as **Trusted**.

### Authentication Methods
MiruRoll functions in **Guest Mode** by default with no account required for free catalog content. For subscriber access to premium and simulcast streams, choose either of the two login methods:

#### Method 1: WebView Login (Recommended)
1. In Animiru, open the **MiruRoll** source.
2. Tap the WebView icon in the top app bar to open the built-in browser.
3. Sign in to your Crunchyroll account (this method natively supports Google, Apple, and 2FA SSO sign-ins).
4. Return to Animiru once signed in. MiruRoll automatically captures your session cookie and keeps you authenticated.

#### Method 2: Email & Password Direct Login
1. In Animiru, navigate to **Browse → Extensions → MiruRoll → Settings**.
2. Enter your **MiruRoll Account Email**.
3. Enter your **MiruRoll Password**.
4. The extension will authenticate via official OAuth and acquire subscriber access tokens.

*(Optional)*: If your account uses third-party SSO or 2FA and you prefer manual token entry, you can paste your Crunchyroll `refresh_token` into the **Custom Refresh Token** field in settings.

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


