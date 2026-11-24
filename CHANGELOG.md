# Changelog

## v1.0 — first public release

The first version published on GitHub. Everything below is included.

**Playback**
- Real web player in a single WebView, ad-free requests, automatic ad skipping.
- Background audio with a media notification, picture-in-picture and a mini player.
- Playback speed, quality picker, full screen on rotation.
- Full screen fills the display immediately in both directions: the video's own frame covers the
  screen while the phone rotates, so the player never appears small and then jumps into place.
- When a video opens, the picture is already in its final place and size (a 16:9 still fitted into
  the player), with no enlarged preview flashing up first.

**VidaraMusic**
- Songs, albums, playlists and radio in the same app, with its own logo, colours and player bar.

**Interface**
- Glass-purple theme, custom icon set, Vidara lettering instead of YouTube branding.
- Home and Shorts only in the bottom bar; every sign-in prompt removed (including the
  "Sign in to create & share playlists" panel and the artist onboarding shelf), plus "Open app"
  banners and Shorts shelves.

**Screen sizes**
- Phones: single column, full-width cards.
- Tablets and foldables: four-column feed at 1280 dp, full-width rows between 600 and 1099 dp,
  two-pane video page between 820 and 1099 dp. Rotating keeps the layout.

**Feedback and help**
- **Send feedback to Vidara** — own dialog, message goes to the developer's mailbox (never to Google),
  with app version, Android version, device and current page attached. The address never appears in the UI.
- **Vidara Help** — own help window with eight short topics and a fixed footer.

**Performance**
- Timers stop in the background, full-page scans are throttled to at most one per 1.2 s, the video
  element is reused between videos, images come from a memory cache.

**Privacy**
- No analytics, no crash reporting, no Vidara server. Playback happens in the real player on your own
  connection, with no account.

**Updates**
- Built-in updater: reads the latest GitHub release and offers the new APK.

---

APK: `Vidara-v1.0.apk`. The app installs as an update and keeps your data (same signing key).
