# Plex Subtitles Companion

A tiny personal companion for Plex: keep **English subtitles on the TV** and show the **Spanish SRT synchronized on your phone**.

It is intentionally small:

- Python + FastAPI.
- `python-plexapi` for Plex interactions.
- No database.
- No webhooks.
- No reverse proxy required.
- External `.srt` files are read directly from the same media library.
- Media is mounted read-only.

## Docker image

Every push to `main` is built automatically by GitHub Actions and published to GitHub Container Registry:

```text
ghcr.io/soyxan/plex-subtitles:latest
```

Each build also gets a commit-specific tag such as:

```text
ghcr.io/soyxan/plex-subtitles:sha-abc1234
```

That makes rollback easy if a future change causes a problem.

## Portainer

The included `docker-compose.yml` already uses the published GHCR image, so Portainer does not need to build anything locally.

For the current setup, the important defaults are:

```text
MEDIA_PATH=/mnt/data/media
PLEX_MEDIA_ROOT=/data/media
PLEX_URL=http://host.docker.internal:32400
PLEX_CLIENT_FILTER=Samsung
PORT=8085
```

Only `PLEX_TOKEN` must be provided explicitly.

Example stack:

```yaml
services:
  plex-subtitles:
    image: ghcr.io/soyxan/plex-subtitles:latest
    container_name: plex-subtitles
    restart: unless-stopped
    ports:
      - "8085:8000"
    environment:
      PLEX_URL: "http://host.docker.internal:32400"
      PLEX_TOKEN: "${PLEX_TOKEN}"
      PLEX_CLIENT_FILTER: "Samsung"
      PLEX_MEDIA_ROOT: "/data/media"
      CONTAINER_MEDIA_ROOT: "/data/media"
      POLL_INTERVAL_MS: "750"
    extra_hosts:
      - "host.docker.internal:host-gateway"
    volumes:
      - /mnt/data/media:/data/media:ro
```

`PLEX_TOKEN` must **not** be committed to GitHub.

Open the interface at:

```text
http://YOUR-SERVER-IP:8085
```

## How it works

1. The app asks Plex for the active playback sessions.
2. It prefers the configured client, for example `Samsung`.
3. `python-plexapi` supplies playback position and media metadata.
4. The app reads the matching media path from the read-only volume.
5. It finds the Spanish external SRT next to the video.
6. The SRT is cached and the current line is selected by playback time.
7. Your phone polls the app and displays the current + next Spanish subtitle.

## Supported subtitle names

The video and subtitle should share the same base filename. Recognized examples:

```text
Movie.mkv
Movie.es.srt
Movie.es-ES.srt
Movie.spa.srt
Movie.spanish.srt
Movie.español.srt
Movie.castellano.srt
```

If only one matching external SRT exists, it is used as a fallback.

## Find your Plex token

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

This app is designed for a trusted home LAN.

- The Plex token remains server-side.
- The browser never receives it.
- Media is mounted read-only.
- There is no authentication layer in the companion web UI, so do not expose port 8085 directly to the public Internet.
