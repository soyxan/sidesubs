# SideSubs

**SideSubs** is an Android-first second-screen subtitle companion for Plex.

It displays a second subtitle, synchronized with Plex playback on a TV or another screen, independently from the subtitle selected in the Plex player itself.

The native Android app is the main SideSubs application and the primary focus of development. A Docker/Web client is also available as an experimental alternative for browser-based use.

## Android app

The Android application is standalone and connects directly to Plex. It does **not** depend on the Docker service.

Main capabilities include:

- Modern Plex sign-in through the official Plex authentication flow.
- Automatic Plex Media Server discovery and reconnection.
- Automatic playback-session selection.
- Second-screen subtitles synchronized with the selected Plex session.
- External and embedded text subtitle support.
- Complete subtitle timeline download and local cue caching.
- Preferred subtitle language and regional language variants.
- Manual subtitle-track selection.
- Adjustable subtitle delay.
- Configurable subtitle size with visual preview.
- Current and next subtitle display.
- Smoothed playback clock between Plex polls.
- Cinema mode for landscape/fullscreen viewing.
- Safe-area handling for camera cutouts.
- Foreground-aware polling and lifecycle handling.
- Persistent diagnostic log without exposing Plex tokens or subtitle text.

The user does not need to enter a Plex server URL or token manually.

### Android architecture

The Android UI depends on a provider-neutral media contract:

```text
MediaProvider
    |
    +-- PlexClient          (implemented)
    +-- JellyfinProvider    (future)
    +-- EmbyProvider        (future)
```

Shared Android models use generic identifiers such as `mediaId`, while provider-specific data such as Plex stream IDs and part IDs remain inside provider-owned metadata.

Only Plex is currently exposed in the application. Jellyfin and Emby are architectural extension points, not implemented providers.

### Plex sign-in

SideSubs uses Plex's modern authentication flow:

1. SideSubs creates a persistent per-device identity.
2. It generates an Ed25519 key pair and public JWK.
3. It requests a Plex PIN and opens the official Plex sign-in page.
4. After authorization, SideSubs obtains the account authorization and discovers available Plex Media Servers.
5. The app validates advertised server connections and selects a reachable PMS.
6. The PMS-specific authorization required by the server is obtained and stored internally.
7. On later launches SideSubs reconnects automatically and refreshes authentication when needed.

Tokens are never shown in the Android UI.

### Playback and lifecycle

SideSubs follows an active Plex playback session automatically.

Playback polling only runs while the application is in the foreground. When the app leaves the foreground, polling stops and stale in-flight results are discarded. When the user returns, polling resumes.

If Cinema mode was active before switching to another app, SideSubs restores landscape/immersive mode and briefly shows the status and control bars before hiding them again.

### Subtitle handling

Subtitle discovery is based on Plex metadata.

External text subtitles are fetched directly from Plex when a stream key is available.

Embedded text subtitles use the Plex universal subtitle-transcode flow:

1. Read the subtitle stream from metadata.
2. Temporarily select the requested stream on the Plex Part when required.
3. Request a universal transcode decision.
4. Download the complete subtitle document.
5. Restore the previously selected Plex subtitle stream.
6. Parse all cues once.
7. Cache the complete cue timeline.

SideSubs does not continuously stream subtitle segments.

Supported text formats include SRT/SubRip, ASS/SSA, WebVTT and mov_text. Image-based formats such as PGS are not currently rendered as text.

### Subtitle language

The Android app includes a language selector rather than a free-text field.

It supports common languages and useful regional variants, including examples such as:

- Spanish (Spain)
- Spanish (Latin America)
- Spanish (Mexico)
- English (United States)
- English (United Kingdom)
- Portuguese (Portugal)
- Portuguese (Brazil)
- French (France)
- French (Canada)
- Chinese (Simplified)
- Chinese (Traditional)

When Plex exposes regional information, SideSubs prefers the exact regional match. If Plex only identifies the base language, SideSubs falls back to that base language.

### Interface

Subtitles are the primary content of the Android interface.

The top status area shows the current media/session reference and playback time. The bottom control bar provides access to:

- Playback session
- Subtitle track
- Subtitle delay
- Settings
- Cinema mode

In Cinema mode, the status and control bars hide automatically and reappear temporarily when the screen is touched or when SideSubs returns to the foreground.

## Docker / Web client — experimental

SideSubs also includes a Docker/Web implementation.

This client predates the native Android architecture and remains available as an optional browser-based alternative for users who want to run SideSubs from a laptop, tablet, desktop browser or another device without installing the Android app.

The Docker/Web client is **not** the primary development target and feature parity with Android is not guaranteed.

It currently:

- Connects directly to Plex.
- Provides second-screen subtitle synchronization in a browser.
- Supports session and subtitle-track selection.
- Supports subtitle delay and subtitle-size controls.
- Handles external and embedded text subtitles.
- Runs independently from the Android application.

### Docker image

Every push to `main` is published to GitHub Container Registry:

```text
ghcr.io/soyxan/sidesubs:latest
```

Each build also receives a commit-specific tag:

```text
ghcr.io/soyxan/sidesubs:sha-abc1234
```

### Docker Compose / Portainer

The included `docker-compose.yml` uses the published GHCR image.

Required configuration:

```text
PLEX_TOKEN
```

Common configuration:

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

There is no media volume. The Docker client obtains metadata and subtitle text from Plex over HTTP.

Never commit your Plex token to GitHub.

Open the web client at:

```text
http://YOUR-SERVER-IP:8085
```

## Development priorities

Development priority is:

1. Native Android application.
2. Shared provider-neutral concepts and future provider support.
3. Docker/Web client maintenance and selected feature backports.

New UX decisions should be designed for the native Android experience first. The Docker/Web client can remain useful without maintaining strict feature parity.

## Versioning

SideSubs uses Git tags as the source of truth for official releases.

- Android release builds created from a version tag use the same semantic version as `versionName`.
- Pushes to `main` publish Docker `latest` plus a commit-specific `sha-...` tag.
- Version tags such as `v0.9.1` publish the corresponding Docker image tag.
- Development builds identify themselves by their commit-based version rather than pretending to be an official release.

## Security

### Android

- Plex authorization is stored internally.
- Tokens are never displayed in the UI.
- Diagnostic logs do not contain tokens, credentials or subtitle text.
- SideSubs does not need filesystem access to the Plex media library.

### Docker/Web

The Docker/Web client is intended primarily for trusted home-LAN use.

- The Plex token remains server-side.
- The browser does not receive the Plex token.
- The web UI currently has no authentication layer.
- Do not expose it directly to the public Internet without an appropriate security layer.

## Development stack

Android:

- Kotlin
- Native Android UI
- No Jetpack Compose
- Minimum SDK 26
- Target SDK 36
- Tink Android for Plex device identity / Ed25519 support

Docker/Web:

- Python 3.12
- FastAPI
- Uvicorn
- python-plexapi
- requests
- Provider adapter boundary under `app/providers/`

## License

SideSubs is released under the [MIT License](LICENSE).
