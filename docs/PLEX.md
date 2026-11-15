# Plex API notes

Nyx TV talks to the user's Plex Media Server for library and playback. Account PIN login uses plex.tv.

## Published vs observed

| Call | Path | Notes |
|------|------|--------|
| PIN create / check | `plex.tv/api/v2/pins` | Published |
| Resources (servers) | `clients.plex.tv/api/v2/resources` | Published |
| Identity | `GET /` | machineIdentifier, friendlyName |
| Libraries / browse / metadata | `/library/…` | Standard PMS |
| Continue Watching | `/hubs/continueWatching/items`, fallback On Deck | Observed hub paths |
| Direct play | Part `key` under `/library/parts/…` | Token via header |
| Transcode decision / start | `/video/:/transcode/universal/decision`, `…/start.mkv` | Same pattern as Theater |
| Timeline | `POST /:/timeline` | Progress for Continue Watching |
| Stop transcode | `/video/:/transcode/universal/stop` | Per-session UUID only |

## Playback

Default: built-in Media3 **Gradient** player (Theater-style chrome).

| Call | Path / field | Notes |
|------|----------------|--------|
| Markers | `GET /library/metadata/{id}?includeMarkers=1` → `Marker` | `type` `intro` / `credits`, `startTimeOffset` / `endTimeOffset` (ms) |
| Streams | `Media[].Part[].Stream[]` | `streamType` 1 video, 2 audio, 3 subtitle |
| Chapters | `Chapter[]` on metadata | `startTimeOffset`, `tag` |
| Direct play | Part `key` + optional `audioStreamID` / `subtitleStreamID` | Token via header |
| Transcode | `…/decision`, `…/start.mkv` | Same pattern as Theater |

Audio sync offset is a user setting (often about −500 ms on this TV until the glitch clears). Opening the official Plex app remains available as a fallback.

Deep links into the Plex Android TV app are not documented; candidates live in `PlexLinks` and are recorded in COMPATIBILITY.md as tested.
