# SideSubs Development Guide

This document captures stable development decisions for SideSubs so future work can start from the current architecture and product direction without relying on conversation history.

## Product direction

- **Android is the primary SideSubs product.**
- The Android app receives the complete feature development, UX improvements, provider work, compatibility fixes and release attention.
- The Docker/Web client is an **experimental prototype** for testing and self-hosted experimentation.
- Docker/Web feature parity with Android is **not a goal** unless explicitly decided otherwise.
- Do not modify Docker/Web as part of Android work unless the change is intentionally meant for both implementations.

## Repository and source of truth

The repository is the source of truth for implementation details.

Before changing code:
1. Read the current version of the relevant files from `main`.
2. Do not rely on stale snippets from previous discussions.
3. Prefer small, targeted changes that preserve existing behavior unless the requested change requires otherwise.
4. Keep code, comments, commit messages and repository-facing documentation in English.

## Android stack

- Kotlin
- Native Android Views
- No Jetpack Compose
- Minimum SDK 26
- Target SDK 36
- Java 17 in CI
- Gradle build through GitHub Actions

Main Android package:

`android/app/src/main/java/com/soyxan/sidesubs/`

Key files include:

- `MainActivity.kt` — primary UI, lifecycle, polling and playback/subtitle state coordination
- `MediaProvider.kt` — provider-neutral media interface and connection model
- `PlexClient.kt` — Plex sessions, tracks and subtitle retrieval
- `PlexAuthManager.kt` — Plex authentication and server discovery
- `JellyfinClient.kt` — Jellyfin sessions, tracks and subtitle retrieval
- `JellyfinAuthManager.kt` — Jellyfin authentication / Quick Connect
- `SubtitleParser.kt` — SRT and ASS/SSA parsing
- `PlaybackClock.kt` — playback position smoothing
- `DiagnosticLog.kt` — local diagnostics

## Provider architecture

The Android app uses a provider-neutral interface:

```text
MediaProvider
    |
    +-- PlexClient
    +-- JellyfinClient
```

Current providers:

- Plex — implemented
- Jellyfin — implemented

Possible future providers such as Emby should fit the same abstraction instead of leaking provider-specific logic into the UI.

The UI should work with generic playback/session/subtitle models. Provider-specific identifiers and protocol behavior should remain inside the corresponding provider implementation where possible.

## Playback session behavior

- SideSubs follows a selected playback session.
- When multiple sessions are available, the user can choose which one to follow.
- A manually selected session should not silently fall back to another player if it disappears.
- If no manual selection exists, SideSubs may automatically select a suitable active session.
- Session polling is frequent and should remain responsive without blocking the UI.

## Subtitle behavior

SideSubs supports text subtitle tracks exposed by the media server, including:

- SRT / SubRip
- ASS / SSA
- WebVTT
- mov_text

Image-based subtitles such as PGS are not OCR'd or rendered as text.

### Language selection

- A preferred subtitle language can be configured.
- Regional preferences are supported when metadata allows it, for example `es-ES` and `es-419`.
- If an exact regional variant is unavailable, SideSubs may fall back to the base language.
- Manual track selection overrides automatic language selection for that media item.

### Current and next cue

- The UI shows the current subtitle and may show the next cue shortly before it starts.
- Subtitle delay is applied locally and does not change media playback.

## Plex-specific decisions

### Embedded subtitle extraction

Plex does not reliably honor a requested embedded subtitle stream through a fully passive extraction flow.

The current approach for embedded text subtitles is therefore:

1. Read current metadata and selected subtitle stream.
2. Temporarily select the target subtitle stream when required.
3. Verify that Plex applied the temporary selection.
4. Request the subtitle transcode/extraction.
5. Restore the previous subtitle selection in a `finally` block.
6. Verify restoration and retry when appropriate.

External subtitle tracks are downloaded directly and do not require the temporary selection flow.

### ASS / SSA subtitles

Embedded ASS/SSA subtitles require Plex to treat advanced subtitles as text.

For ASS/SSA extraction, SideSubs sends:

```text
advancedSubtitles=text
```

This was validated against a reported Plex issue where embedded ASS returned HTTP 400 while embedded SRT worked correctly.

Do not apply ASS/SSA-specific parameters globally to SRT unless testing shows that it is safe and useful.

### Subtitle restore safety

Restoration must avoid overwriting a newer user selection.

If Plex reports that the subtitle selection changed externally while SideSubs was extracting a track, SideSubs should not blindly restore the older value.

### New-media startup delay

When SideSubs is already following playback and detects a new media item near the beginning of playback, subtitle loading is intentionally deferred for a short period.

Current behavior:

- delay embedded subtitle loading until playback is at least 10 seconds into the new media item;
- this reduces the chance of interfering with Plex client initialization/autoplay;
- opening SideSubs on an already-running item should not introduce the same unnecessary delay.

### Persistent subtitle cache

Plex embedded text subtitle extraction can sometimes take many seconds.

SideSubs therefore keeps a persistent on-device cache for extracted Plex subtitles:

- keyed by media + subtitle stream;
- reused across app restarts;
- limited to a bounded number of cached subtitles;
- oldest/least-recently-used entries are removed over time.

The in-memory cache remains useful for repeated access during the same app session.

### Loading state

The subtitle track list and the subtitle content are separate concerns.

SideSubs should:

1. retrieve and publish the available subtitle track list as soon as possible;
2. keep the track selector available while the selected subtitle timeline is still downloading;
3. show a visible loading state such as `Downloading subtitles…`;
4. discard stale subtitle results if the user changes track while a previous download is still in progress.

## Jellyfin-specific decisions

- Authentication uses Jellyfin Quick Connect.
- Session discovery and subtitle retrieval are implemented through the same provider-neutral interface used by Plex.
- Jellyfin behavior should remain isolated inside the Jellyfin provider and auth manager.
- Provider-specific shortcuts should not be introduced into the generic UI unless they are unavoidable.

## UI principles

- Keep the main playback/subtitle screen simple and usable as a second-screen companion.
- Cinema mode is intended for fullscreen landscape subtitle viewing.
- Network or subtitle-loading waits should have explicit UI feedback.
- Avoid presenting temporary loading states as missing data. For example, do not show `No subtitles` when the track list exists but the timeline is still downloading.
- Preserve user selections whenever possible.
- Avoid silent behavior changes that affect the subtitle selected on the TV or primary playback client.

## Diagnostics

Diagnostic logging is an important part of SideSubs support.

Logs should help diagnose:

- provider authentication
- server discovery
- connection probing
- session discovery
- subtitle track selection
- subtitle extraction
- cache hits
- restoration failures
- provider HTTP errors

Logs should **not** include:

- access tokens
- credentials
- subtitle text

When investigating user reports, prefer asking for SideSubs logs before making speculative protocol changes when the current diagnostics can distinguish the likely causes.

## Docker / Web

The Docker/Web implementation is an experimental prototype.

- It is useful for testing and self-hosted experimentation.
- It does not need to match Android feature-for-feature.
- Android improvements should not automatically be ported to Docker/Web.
- Fix Docker/Web issues when they are important to the prototype itself or when a shared protocol fix clearly applies.
- Avoid letting Docker/Web compatibility constrain Android architecture unless there is a deliberate product decision to do so.

## Development workflow

For Android changes:

1. Inspect the current implementation on `main`.
2. Make the smallest change that satisfies the requested behavior.
3. Preserve existing Plex/Jellyfin behavior unless intentionally changing it.
4. Check interactions with:
   - playback/session switching
   - manual subtitle selection
   - automatic language selection
   - app lifecycle
   - Cinema mode
   - slow network/subtitle extraction
5. Verify the Android build workflow when code changes are made.

Android CI workflow:

`.github/workflows/build-android.yml`

The workflow builds a debug APK and publishes it as the `SideSubs-debug` artifact.

## Releases and testing

Before an Android release:

- verify the Android build succeeds;
- test the changed provider path with real playback when possible;
- check that existing SRT behavior still works after subtitle extraction changes;
- test manual and automatic track selection where relevant;
- test resume/reopen behavior for lifecycle-related changes;
- review logs for unexpected retries, stale loads or restoration warnings.

For protocol fixes reported by users, a dev/debug APK may be shared from a successful GitHub Actions build for validation before publishing a stable release.

## Documentation maintenance

Update this file when a **stable architectural or product decision** changes.

Do not use it as a changelog. Temporary experiments, one-off debugging steps and release-specific notes should not be added unless they become part of the long-term development model.

The public README files should remain focused on users. This document is for maintainers and future development work.
