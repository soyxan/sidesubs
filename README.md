# SideSubs

**SideSubs** is a second-screen subtitle companion for Plex.

It displays synchronized subtitles on a phone or tablet while Plex plays the video on another screen. Subtitle language and track selection are independent from the subtitles shown by the Plex player itself.

SideSubs is designed primarily for trusted home-LAN use.

## Features

- Second-screen subtitles synchronized with Plex playback.
- Explicit Plex session selection.
- Smooth playback clock to compensate for coarse Plex `viewOffset` updates.
- Current and next subtitle shown together.
- Adjustable subtitle delay stored per browser/device.
- Preferred subtitle language stored per browser/device.
- Per-title manual subtitle track selection.
- Unified discovery of external SRT files and embedded subtitle streams.
- Embedded text subtitle extraction through FFmpeg/FFprobe.
- Custom mobile-friendly selectors for sessions, tracks and language.
- Cinema mode optimized for subtitle viewing.
- Native Android companion app.
- Docker deployment with no database, webhooks or reverse proxy required.
- Media mounted read-only.

## Docker image

Every push to `main` is built automatically by GitHub Actions and published to GitHub Container Registry:

```text
ghcr.io/soyxan/sidesubs:latest
```

Each build also receives a commit-specific tag:

```text
ghcr.io/soyxan/sidesubs:sha-abc1234
```

The `latest` tag is intended for normal home deployments. Commit-specific tags can be used when a deployment needs to stay pinned to a known version.

## Docker Compose / Portainer

The included `docker-compose.yml` uses the published GHCR image directly.

### Required configuration

```text
PLEX_TOKEN
```

### Common configuration

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

Never commit your Plex token to GitHub.

Open SideSubs at:

```text
http://YOUR-SERVER-IP:8085
```

## How it works

1. SideSubs asks Plex for the currently available playback sessions.
2. You choose the Plex player that SideSubs should follow.
3. The selected player is remembered locally on that browser/device.
4. Plex provides playback position and media metadata.
5. SideSubs maps the Plex media path to the read-only media volume.
6. External and embedded subtitle tracks are discovered.
7. SideSubs chooses a compatible track using the preferred language unless a specific track has been selected for that title.
8. The backend smooths Plex playback-position updates.
9. The client polls SideSubs and renders the synchronized current and next subtitle.
10. An optional client-side delay can shift subtitle presentation without changing Plex playback.

If the selected Plex session disappears, SideSubs does **not** silently switch to another player. The UI indicates that the selected session is no longer available.

## Subtitle selection

SideSubs presents external and embedded subtitles in one selector.

The preferred language can be changed under **Settings**. Common language choices are provided in a mobile-friendly selector. When a new title starts, SideSubs automatically chooses a compatible track matching that preference.

The subtitle selector in the main control bar shows the tracks available for the current media item. A manually selected track is remembered for that Plex title and overrides automatic language selection. Choosing **Auto** returns to preferred-language selection.

### External subtitles

Matching external SRT files are discovered automatically. Language is inferred from the filename suffix when possible.

Example:

```text
Movie.mkv
Movie.en.srt
Movie.es.srt
Movie.fr.forced.srt
Movie.commentary.srt
```

### Embedded subtitles

SideSubs inspects embedded subtitle streams with FFprobe. Language is read from stream metadata when available and can also be inferred from common language names in the stream title.

Supported text subtitle codecs include SRT/SubRip, ASS/SSA, WebVTT and mov_text.

Image-based subtitle formats such as PGS may appear in the selector as unsupported, but SideSubs does not currently render them as text.

## Interface

Subtitles are the primary content of the interface.

In normal portrait use, the status panel at the top and the controls at the bottom remain visible. The top panel shows playback/media information while the bottom controls provide access to session selection, subtitle selection, delay, Settings and Cinema mode.

In **Cinema mode**, the interface is optimized for landscape viewing. The status panel and controls automatically hide together and reappear when the screen is touched.

## Android app

SideSubs includes a small native Android client in the `android/` directory.

The app wraps the SideSubs web interface in a WebView while adding native behavior that is useful for a second-screen subtitle display:

- first-run server configuration;
- persistent SideSubs server URL;
- native connection and startup error screens;
- validation that the SideSubs web interface becomes ready;
- support for trusted LAN HTTP servers;
- portrait orientation during normal use;
- Cinema mode switches to landscape;
- Cinema mode enters immersive fullscreen;
- the screen is kept awake while Cinema mode is active;
- leaving Cinema mode restores portrait orientation and normal screen timeout;
- native app settings are accessible from the SideSubs web Settings screen.

On first launch, enter the address of your SideSubs server, for example:

```text
http://192.168.1.50:8085
```

### Android releases

Signed Android APKs are published through GitHub Releases:

https://github.com/soyxan/sidesubs/releases

Version tags such as `v0.9.0` trigger the signed release workflow. The generated APK is cryptographically signed with the SideSubs release key so later versions can be installed as updates to the same application.

Development changes under `android/` also trigger a separate debug APK build in GitHub Actions. Debug artifacts are intended for development and testing; GitHub Releases are the recommended source for installable public builds.

No Android IDE is required to build the project in CI.

## Plex token

SideSubs authenticates to your Plex server using a Plex token.

Plex documentation:

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

- The Plex token remains server-side.
- The browser and Android client do not receive the Plex token.
- Media is mounted read-only.
- The web UI currently has no authentication layer.
- Local HTTP is supported for home-LAN deployments.
- Do not expose SideSubs directly to the public Internet without adding an appropriate security layer.

## Development

Backend:

- Python 3.12
- FastAPI
- Uvicorn
- python-plexapi
- FFmpeg / FFprobe

Android:

- Native Java
- Android WebView
- Minimum SDK 26
- Target SDK 36

## License

SideSubs is released under the [MIT License](LICENSE).
