# SideSubs

**SideSubs** is a second-screen subtitle companion for Plex.

It is designed for situations where you want subtitles on a phone or tablet while watching the actual video on another screen. A common use case is keeping English subtitles on the TV and displaying Spanish subtitles on your phone, but the TV does not need to show subtitles at all.

## Features

- Second-screen subtitles synchronized with Plex playback.
- Explicit Plex session selection from the web interface.
- Selected player is remembered in the browser with `localStorage`.
- Optional subtitle display delay, adjustable from the interface.
- Current and next subtitle shown together for quick reference.
- External SRT subtitle support.
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
6. It loads a compatible Spanish subtitle track, either from an external SRT or from an embedded text subtitle stream.
7. The backend smooths coarse Plex position updates.
8. The phone polls the backend and displays the synchronized current and next subtitles.
9. An optional client-side delay lets the subtitle remain visible slightly after it appeared on the TV.

If the selected Plex session disappears, SideSubs does **not** silently switch to another player. The interface asks you to select a session again.

## Subtitle support

### External SRT

The video and subtitle should share the same base filename. Recognized examples include:

```text
Movie.mkv
Movie.es.srt
Movie.es-ES.srt
Movie.spa.srt
Movie.spanish.srt
Movie.español.srt
Movie.castellano.srt
```

If only one matching external SRT exists, it may be used as a fallback.

### Embedded subtitles

SideSubs can inspect subtitle streams inside media containers using FFprobe and extract compatible text-based Spanish streams with FFmpeg.

Supported text subtitle codecs include SRT/SubRip, ASS/SSA, WebVTT and mov_text.

Image-based subtitle formats such as PGS cannot currently be converted to text automatically.

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
