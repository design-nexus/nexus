# Seerr Integration

Nexus includes optional, native **Seerr** integration (`:provider-seerr`) for discovering trending/popular/upcoming titles, requesting movies and TV seasons, and tracking requests from one unified interface.

When Seerr is not configured, the **Discover** tab and all Seerr-dependent UI remain hidden.

## Setup

1. Open **Settings › Seerr** in Nexus.
2. Choose an authentication method:
   - **Sign in with Plex** *(recommended for shared Android TVs)*: uses your linked Plex account token (`POST /api/v1/auth/plex`) to obtain a user-scoped `connect.sid` session cookie.
   - **Sign in with Seerr account**: signs in with a local Seerr email/username and password (`POST /api/v1/auth/local`).
   - **Sign in with scoped API key**: fallback only; avoid unrestricted administrator API keys on a shared TV.
3. Enter your Seerr server URL:
   - Local LAN IP (`http://192.168.1.20:5055`)
   - HTTPS (`https://seerr.example.com`)
   - Tailscale (`http://100.x.y.z:5055` or `https://seerr.tailnet.ts.net`)

Credentials are encrypted at rest with Android Keystore AES-256-GCM (`SecureSessionStore`) and injected only on requests matching the configured Seerr server's scheme, host, and port.

## Behavior

- **Navigation**: **Discover** appears in the top bar after **TV Shows** (`Home · Movies · TV Shows · Discover · Apps`). **My Requests** lives inside **Discover** as a filter chip.
- **Contextual Actions**: Plex is authoritative for playable content (`Watch Now` / `Resume`); Seerr is authoritative for request status and permissions (`Request`, `Request Seasons`, `Pending Approval`, `View Status`).
- **Unified Search**: Searches Plex and Seerr concurrently, deduplicating by `tmdbId`, `tvdbId`, `imdbId`, or normalized title/year, and isolates backend failures so Plex search continues working if Seerr is unreachable.

## Remaining Limitations & Verification Notes

- **Physical TV Verification**: Automated tests cover `:provider-seerr` (`MockWebServer`), `:provider-plex` GUID parsing, availability resolution, unified search deduplication, and `:app:assembleDebug`. D-pad focus ergonomics, scrolling performance, and live end-to-end playback on physical Android TV / TCL Google TV hardware should be verified on-device.
- **4K / Custom Radarr/Sonarr Profiles**: Requests currently use the Seerr server's default profile and root folder (`is4k = false`).
- **Session Expiry**: When a `connect.sid` cookie expires (`401`), Nexus marks Seerr offline and prompts the user to re-authenticate in **Settings › Seerr**.
