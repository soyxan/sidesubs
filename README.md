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

## How it works

1. The app asks Plex for the active playback sessions.
2. It prefers the configured client (for example `Samsung`).
3. `python-plexapi` supplies the playback position and media metadata.
4. The app maps Plex's media path to the `/media` Docker mount.
5. It finds the Spanish external SRT next to the video.
6. The SRT is cached and the current line is selected by playback time.
7. Your phone polls the app and displays the current + next Spanish subtitle.

## Portainer + Git deployment

Create the stack from this Git repository and define these environment variables in Portainer:

| Variable | Example | Required |
|---|---|---|
| `PLEX_TOKEN` | `xxxxxxxx` | Yes |
| `MEDIA_PATH` | `/mnt/media` | Yes |
| `PLEX_MEDIA_ROOT` | `/mnt/media` | Usually |
| `PLEX_URL` | `http://host.docker.internal:32400` | No |
| `PLEX_CLIENT_FILTER` | `Samsung` | No |
| `PORT` | `8085` | No |
| `POLL_INTERVAL_MS` | `750` | No |

`PLEX_TOKEN` must **not** be committed to GitHub.

Then deploy/redeploy the stack and open:

```text
http://YOUR-SERVER-IP:8085
```

## Path mapping

If Plex reports:

```text
/mnt/media/Series/Severance/Season 02/Episode.mkv
```

and the Docker host stores that directory under `/mnt/media`, use:

```text
MEDIA_PATH=/mnt/media
PLEX_MEDIA_ROOT=/mnt/media
```

The container sees the file under `/media/...`.

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

## Local Docker Compose

Copy `.env.example` to `.env`, edit the values and run:

```bash
docker compose up -d --build
```

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
