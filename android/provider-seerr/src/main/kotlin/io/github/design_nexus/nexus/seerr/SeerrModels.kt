package io.github.design_nexus.nexus.seerr

import io.github.design_nexus.nexus.media.MediaDetails
import io.github.design_nexus.nexus.media.MediaKind

enum class SeerrAuthMode { PLEX, LOCAL, API_KEY }

/**
 * Stored Seerr session. Holds credentials (`sessionCookie` or scoped `apiKey`);
 * store encrypted in [SecureSessionStore] and never back up.
 */
data class SeerrSession(
    val serverUrl: String,
    val authMode: SeerrAuthMode,
    val sessionCookie: String? = null,
    val apiKey: String? = null,
    val userId: Int? = null,
    val displayName: String? = null,
    val permissions: Long = 0L,
    val serverVersion: String? = null,
) {
    override fun toString(): String = "SeerrSession($serverUrl, $authMode, user=$displayName)"
}

interface SeerrSessionStore {
    fun loadSeerr(): SeerrSession?
    fun saveSeerr(session: SeerrSession?)
}

enum class SeerrMediaStatus(val code: Int) {
    UNKNOWN(1),
    PENDING(2),
    PROCESSING(3),
    PARTIALLY_AVAILABLE(4),
    AVAILABLE(5),
    DELETED(6);

    companion object {
        fun fromCode(code: Int): SeerrMediaStatus = entries.firstOrNull { it.code == code } ?: UNKNOWN
    }
}

enum class SeerrRequestStatus(val code: Int) {
    PENDING(1),
    APPROVED(2),
    DECLINED(3),
    FAILED(4),
    COMPLETED(5);

    val label: String
        get() = when (this) {
            PENDING -> "Pending Approval"
            APPROVED -> "Approved"
            DECLINED -> "Declined"
            FAILED -> "Failed"
            COMPLETED -> "Available"
        }

    companion object {
        fun fromCode(code: Int): SeerrRequestStatus = entries.firstOrNull { it.code == code } ?: PENDING
    }
}

data class SeerrSeasonInfo(
    val seasonNumber: Int,
    val name: String,
    val episodeCount: Int = 0,
    val airDate: String? = null,
    val posterPath: String? = null,
    val mediaStatus: SeerrMediaStatus = SeerrMediaStatus.UNKNOWN,
    val requestStatus: SeerrRequestStatus? = null,
)

data class SeerrRequest(
    val id: Int,
    val mediaKind: MediaKind,
    val tmdbId: Int,
    val tvdbId: Int? = null,
    val title: String,
    val year: Int? = null,
    val posterPath: String? = null,
    val backdropPath: String? = null,
    val status: SeerrRequestStatus,
    val mediaStatus: SeerrMediaStatus,
    val seasons: List<Int> = emptyList(),
    val requestedBy: String? = null,
    val createdAt: String? = null,
    val is4k: Boolean = false,
)

data class SeerrMediaInfo(
    val id: Int? = null,
    val tmdbId: Int,
    val tvdbId: Int? = null,
    val status: SeerrMediaStatus,
    val requests: List<SeerrRequest> = emptyList(),
    val seasonStatuses: Map<Int, SeerrMediaStatus> = emptyMap(),
)

data class SeerrGenre(val id: Int, val name: String)

data class SeerrStatusResult(
    val version: String,
    val commitTag: String? = null,
    val updateAvailable: Boolean = false,
)

data class SeerrUser(
    val id: Int,
    val displayName: String,
    val email: String? = null,
    val plexUsername: String? = null,
    val permissions: Long = 0L,
    val avatar: String? = null,
)

data class SeerrDetailBundle(
    val details: MediaDetails,
    val mediaInfo: SeerrMediaInfo? = null,
    val seasons: List<SeerrSeasonInfo> = emptyList(),
)

open class SeerrException(message: String, cause: Throwable? = null) : Exception(message, cause)
class SeerrUnavailableException(message: String, cause: Throwable? = null) : SeerrException(message, cause)
class SeerrAuthExpiredException(message: String = "Seerr session expired — please sign in again") : SeerrException(message)
class SeerrPermissionException(message: String = "You do not have permission for this action in Seerr") : SeerrException(message)
class SeerrDuplicateRequestException(message: String = "This title has already been requested") : SeerrException(message)
class SeerrBadRequestException(message: String) : SeerrException(message)
