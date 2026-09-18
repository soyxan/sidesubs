# SideSubs

**SideSubs** is a second-screen subtitle companion for Plex.

It is designed for situations where you want subtitles on a phone or tablet while watching the actual video on another screen. The subtitle language and the exact subtitle track are selectable independently from whatever is shown on the TV.

## Features

- Second-screen subtitles synchronized with Plex playback.
- Explicit Plex session selection from the web interface.
- Selected player is remembered in the browser with `localStorage`.
- Optional subtitle display delay, adjustable from the interface.
- Current and next subtitle shown together for quick reference.
- Preferred subtitle language stored per browser/device.
- Per-title subtitle track selection from the main interface.
- Unified discovery of external SRT and embedded subtitle tracks.
- Embedded text subtitle support through FFmpeg/FFprobe.
- Smooth local playback clock to compensate for coarse Plex `viewOffset` updates.
- Cinema mode with fullscreen and Screen Wake Lock support when the browser allows it.
- Python + FastAPI backend.
- `python-plexapi` for Plex integration.
- No database.
- No webhooks.
- No reverse proxy required.
- Media mounted read-only.

## Docker image

Every push to `main` is built automatically by GitHub Actions and published to GitHub Container Registry:

```text
ghcr.io/soyxan/sidesubs:latest
```

Each build also gets a commit-specific tag:

```text
ghcr.io/soyxan/sidesubs:sha-abc1234
```

## Docker Compose / Portainer

The included `docker-compose.yml` uses the published GHCR image directly.

Required:

```text
PLEX_TOKEN
```

Useful defaults:

```text
MEDIA_PATH=/mnt/data/media
PLEX_MEDIA_ROOT=/data/media
PLEX_URL=http://host.docker.internal:32400
PORT=8085
POLL_INTERVAL_MS=750
```

Example:

```yaml
services:
  sidesubs:
    image: ghcr.io/soyxan/sidesubs:latest
    container_name: sidesubs
    restart: unless-stopped

    ports:
      - "8085:8000"

    environment:
      PLEX_URL: "http://host.docker.internal:32400"
      PLEX_TOKEN: "${PLEX_TOKEN}"
      PLEX_MEDIA_ROOT: "/data/media"
      CONTAINER_MEDIA_ROOT: "/data/media"
      POLL_INTERVAL_MS: "750"

    extra_hosts:
      - "host.docker.internal:host-gateway"

    volumes:
      - /mnt/data/media:/data/media:ro
```

Never commit your `PLEX_TOKEN` to GitHub.

Open the interface at:

```text
http://YOUR-SERVER-IP:8085
```

## How it works

1. SideSubs asks Plex for the currently available playback sessions.
2. The web interface lets you explicitly choose the player you want to follow.
3. The selected player ID is stored locally in that browser.
4. Plex provides the playback position and media metadata.
5. SideSubs maps the Plex media path to the read-only media volume.
6. It discovers all matching external SRT files and embedded subtitle streams.
7. It automatically selects a compatible track in the preferred language, unless a specific track has been selected for that title.
8. The backend smooths coarse Plex position updates.
9. The phone polls the backend and displays the synchronized current and next subtitles.
10. An optional client-side delay lets the subtitle remain visible slightly after it appeared on the TV.

If the selected Plex session disappears, SideSubs does **not** silently switch to another player. The interface asks you to select a session again.

## Subtitle selection

SideSubs treats external SRT files and embedded MKV subtitle streams as tracks in the same selector.

The **Settings** button lets each browser/device store a preferred subtitle language, such as `en`, `es`, `fr` or `de`. When a title starts, SideSubs automatically picks the first compatible track matching that language.

The subtitle selector in the main control bar lists the tracks available for the current media item. A manual selection is remembered for that Plex title and overrides the preferred language for that title only. Selecting **Auto** returns to preferred-language selection.

### External SRT

External SRT files whose names begin with the media filename are discovered automatically. Language is inferred from the suffix when possible.

Examples:

```text
Movie.mkv
Movie.en.srt
Movie.es.srt
Movie.fr.forced.srt
Movie.commentary.srt
```

### Embedded subtitles

SideSubs inspects embedded subtitle streams with FFprobe. Language comes from stream metadata when available and can also be inferred from common language names in the stream title.

Supported text subtitle codecs include SRT/SubRip, ASS/SSA, WebVTT and mov_text. Image-based tracks such as PGS are shown in the selector as unsupported but cannot currently be rendered as text.

## Android client

SideSubs also includes a minimal native Android client in the `android/` directory.

The Android app is intentionally small: it wraps the SideSubs web interface in a full-screen `WebView`, keeps the screen awake while the app is open, and supports local HTTP URLs for trusted home-LAN deployments.

On first launch, the app asks for the SideSubs server URL, for example:

```text
http://192.168.1.50:8085
```

The URL is stored locally on the device. To change it later, **long-press anywhere in the WebView** to reopen the server settings dialog.

### Automatic APK builds

GitHub Actions builds a debug APK automatically whenever the Android project changes. You can also run the **Build Android APK** workflow manually.

The generated artifact is named:

```text
SideSubs-debug
```

and contains:

```text
app-debug.apk
```

No Android IDE is required to build the APK.


## Plex token

See Plex's official guide:

https://support.plex.tv/articles/204059436-finding-an-authentication-token-x-plex-token/

## Health check

```text
GET /api/health
```

returns:

```json
{"ok": true}
```

## Security

SideSubs is intended for a trusted home LAN.

- The Plex token stays server-side.
- The browser never receives the Plex token.
- Media is mounted read-only.
- The web UI currently has no authentication layer.
- Do not expose the service directly to the public Internet.
