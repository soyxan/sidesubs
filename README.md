# SideSubs

**SideSubs** is a second-screen subtitle companion for Plex.

It displays synchronized subtitles on a phone or tablet while Plex plays the video on another screen. Subtitle language and track selection are independent from the subtitles shown by the Plex player itself.

SideSubs is designed primarily for trusted home-LAN use.

## Features

- Second-screen subtitles synchronized with Plex playback.
- Explicit Plex session selection.
- Smooth playback clock to compensate for coarse Plex `viewOffset` updates.
- Current and next subtitle shown together.
- Adjustable subtitle delay stored per browser/device and applied directly to the subtitle playback clock.
- Preferred subtitle language stored per browser/device.
- Per-title manual subtitle track selection.
- Subtitle discovery from Plex metadata, including external and embedded text tracks.
- Embedded text subtitle extraction through the Plex Media Server transcode API.
- No media filesystem mount required by SideSubs.
- Custom mobile-friendly selectors for sessions, tracks and language.
- Cinema mode optimized for subtitle viewing.
- Native standalone Android app that connects directly to Plex.
- Docker deployment with no database, webhooks or reverse proxy required.

## Architecture

The Docker/web application is split into provider-neutral SideSubs logic and media-server adapters.

```text
SideSubs UI / API
       |
       v
provider interface
       |
       +-- PlexProvider       (implemented)
       +-- EmbyProvider       (future)
       +-- JellyfinProvider   (future)
```

The provider boundary exposes playback sessions, subtitle tracks and complete timed-text cue timelines. Synchronization, language selection, delay, cue rendering and the HTTP API are independent of Plex-specific objects.

Only Plex is implemented today. `MEDIA_PROVIDER=plex` is therefore the only supported provider value, but the separation is intentional so a future Emby or Jellyfin adapter does not require rewriting the SideSubs UI or subtitle-selection logic.

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
MEDIA_PROVIDER=plex
PLEX_URL=http://host.docker.internal:32400
PLEX_CLIENT_FILTER=
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
      MEDIA_PROVIDER: "plex"
      PLEX_URL: "http://host.docker.internal:32400"
      PLEX_TOKEN: "${PLEX_TOKEN}"
      POLL_INTERVAL_MS: "750"

    extra_hosts:
      - "host.docker.internal:host-gateway"
```

There is no media volume. SideSubs obtains track metadata and subtitle text from Plex over HTTP.

Never commit your Plex token to GitHub.

Open SideSubs at:

```text
http://YOUR-SERVER-IP:8085
```

## How it works

1. SideSubs asks the configured provider for currently available playback sessions.
2. You choose the player that SideSubs should follow.
3. The selected player is remembered locally on that browser/device.
4. Plex provides playback position and media metadata.
5. SideSubs discovers subtitle streams from Plex metadata rather than inspecting the media file itself.
6. SideSubs chooses a compatible track using the preferred language unless a specific track has been selected for that title.
7. External text subtitles are fetched directly from Plex when a stream key is available.
8. Embedded text subtitles are fetched once as a complete subtitle file through Plex HTTP subtitle transcoding, parsed into timed cues and cached in memory per title/track.
9. The backend smooths Plex playback-position updates and uses that clock to choose the current and next cue from the cached timeline. Seeks require no subtitle reload because the complete timeline is already available.
10. The client renders the synchronized current and next subtitle. The configured delay is applied to the subtitle playback clock, without delaying API responses or changing Plex playback.

If the selected Plex session disappears, SideSubs does **not** silently switch to another player. The UI indicates that the selected session is no longer available.

## Subtitle selection

SideSubs presents external and embedded subtitles in one selector.

The preferred language can be changed under **Settings**. Common language choices are provided in a mobile-friendly selector. When a new title starts, SideSubs automatically chooses a compatible track matching that preference.

The subtitle selector in the main control bar shows the tracks available for the current media item. A manually selected track is remembered for that Plex title and overrides automatic language selection. Choosing **Auto** returns to preferred-language selection.

### External subtitles

External subtitle tracks exposed by Plex are fetched through Plex itself. SideSubs no longer depends on matching filenames or a local media directory.

### Embedded subtitles

Embedded text subtitle streams are identified from Plex metadata. SideSubs asks PMS for the complete selected subtitle through the HTTP universal subtitle-transcode flow, parses the returned ASS/SRT/WebVTT-compatible document into one in-memory cue timeline and caches it for the title/track.

Supported text codecs include SRT/SubRip, ASS/SSA, WebVTT and mov_text. Image-based subtitle formats such as PGS may appear in the selector as unsupported, but SideSubs does not currently render them as text.

For embedded tracks, the Plex adapter briefly selects the requested stream on the Plex Part, establishes the transcode decision, fetches the complete subtitle file with `protocol=http`, and immediately restores the previous Plex subtitle selection. Subsequent status polls use only the cached cue timeline; Plex is not repeatedly asked for subtitle segments during playback.

## Interface

Subtitles are the primary content of the interface.

In normal portrait use, the status panel at the top and the controls at the bottom remain visible. The top panel shows playback/media information while the bottom controls provide access to session selection, subtitle selection, delay, Settings and Cinema mode.

In **Cinema mode**, the interface is optimized for landscape viewing. The status panel and controls automatically hide together and reappear when the screen is touched.

## Android app

SideSubs includes a native standalone Android client in the `android/` directory.

The Android app does **not** require the Docker/web SideSubs server. It connects directly to Plex Media Server over HTTP, discovers active Plex sessions and subtitle tracks, fetches complete embedded text subtitles through Plex HTTP subtitle transcoding, caches the parsed cue timeline in memory, and synchronizes current/next subtitles from Plex playback position.

On first launch, configure the Plex server URL and Plex token, for example:

```text
http://192.168.1.50:32400
```

The Android app stores its Plex connection settings, preferred subtitle language, selected player, per-title subtitle choice and delay locally on the device. Cinema mode keeps the screen awake and switches to immersive landscape viewing.

### Android releases

Signed Android APKs are published through GitHub Releases:

https://github.com/soyxan/sidesubs/releases

Version tags such as `v0.9.0` trigger the signed release workflow. The generated APK is cryptographically signed with the SideSubs release key so later versions can be installed as updates to the same application.

Development changes under `android/` also trigger a separate debug APK build in GitHub Actions. Debug artifacts are intended for development and testing; GitHub Releases are the recommended source for installable public builds.

## Versioning

SideSubs uses the Git tag as the source of truth for official releases.

- Pushes to `main` publish Docker `latest` plus a commit-specific `sha-...` tag.
- Tags such as `v0.9.1` publish a Docker image tagged `0.9.1`.
- Android release builds created from a version tag use the same semantic version as `versionName`.
- The web Settings screen shows the running SideSubs Server version. Inside the Android app it also shows the native Android app version.
- Development Docker builds identify themselves by their commit-based `sha-...` version rather than pretending to be an official release.

## Plex token

SideSubs authenticates to your Plex server using a Plex token. The token remains in the Docker container and is not sent to the SideSubs browser UI.

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
- SideSubs no longer requires filesystem access to the media library.
- The web UI currently has no authentication layer.
- Local HTTP is supported for home-LAN deployments.
- Do not expose SideSubs directly to the public Internet without adding an appropriate security layer.

## Development

Backend:

- Python 3.12
- FastAPI
- Uvicorn
- python-plexapi
- requests
- provider adapter boundary under `app/providers/`

Android:

- Native Java
- Android WebView
- Minimum SDK 26
- Target SDK 36

## License

SideSubs is released under the [MIT License](LICENSE).
