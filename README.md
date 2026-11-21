# Noctorium Lite

Noctorium for Android, as small as it can be: **about 120 KB**, against the full app's twenty megabytes, and it runs on
anything from Android 5.0.

- **Home** — what's on YouTube Music and SoundCloud, and what you played lately
- **Search** — songs and playlists on both, or on one, and links: paste a YouTube, YouTube Music or SoundCloud link
  and it plays, or share one to Noctorium Lite from another app
- **Library** — your liked songs and history, kept on the phone
- **Playing** — a queue with Play next and Add to queue, autoplay once it runs out, the notification, the lock screen,
  headphones and Bluetooth buttons, and it plays on with the screen off

## How it stays this small

No library but Kotlin's own: no AndroidX, no Compose, no ExoPlayer, no image loader. The screens are Android's own
views drawn in code, the audio is Android's own `MediaPlayer`, the notification and lock screen are its own media
session, and R8 strips the rest.

The music comes from the hosted web player's API (`music.noctorium.xyz/api/music`), which already reads
YouTube Music and SoundCloud. A YouTube song's audio is asked for on the phone itself, the way yt-dlp asks — YouTube
hands its addresses to the device that asks. A SoundCloud track that comes only as a stream Android's player cannot
read, or is locked to SoundCloud's app, plays as the same recording on YouTube Music, as the full Noctorium does.

## Build

```bash
./gradlew liteApk        # build/dist/Noctorium-Lite-<version>.apk
./gradlew assembleDebug  # app.noctorium.lite.debug, beside a release copy
```

The release build is signed with Noctorium's key when `NOCTORIUM_KEYSTORE` and its passwords are set, as every
Noctorium APK is, and with the debug key otherwise.

Free software under the GPL-3.0. Not affiliated with YouTube or SoundCloud.
