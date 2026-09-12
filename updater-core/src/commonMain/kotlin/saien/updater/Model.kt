package saien.updater

import kotlinx.serialization.Serializable

/** Authenticated wire data. Ordering uses sequence, never the display version. */
@Serializable
public data class ReleaseManifest(
    val schema: Int = 1,
    val appId: String,
    val channel: String,
    val sequence: Long,
    val issuedAt: Long,
    val expiresAt: Long,
    val releases: List<Release>,
)

@Serializable
public data class Release(
    val sequence: Long,
    val version: String,
    val artifacts: List<Artifact>,
    val minimumInstalledSequence: Long = 0,
    val notes: Map<String, String> = emptyMap(),
)

@Serializable
public data class Artifact(
    val target: String,
    val kind: String,
    val url: String,
    val size: Long,
    val sha256: String,
    val minimumOsVersion: String = "0",
)

public data class UpdateConfiguration(
    val appId: String,
    val channel: String,
    val installedSequence: Long,
    val target: String,
    val osVersion: String,
    val manifestUrl: String,
) {
    init {
        require(validIdentifier(appId) && validIdentifier(channel) && validIdentifier(target))
        require(installedSequence >= 0)
        require(parseOsVersion(osVersion) != null)
        require(isHttpsUrl(manifestUrl))
    }

    public val scope: String
        get() = "$appId/$channel"
}

@ConsistentCopyVisibility
public data class UpdateOffer
internal constructor(
    val release: Release,
    val artifact: Artifact,
    val expiresAt: Long,
)

public enum class UpdateErrorCode {
    BUSY,
    INVALID_STATE,
    INVALID_MANIFEST,
    UNTRUSTED_SIGNATURE,
    WRONG_SCOPE,
    EXPIRED_MANIFEST,
    CLOCK_ROLLBACK,
    MANIFEST_ROLLBACK,
    MANIFEST_CONFLICT,
    NETWORK,
    STORAGE,
    ARTIFACT_INTEGRITY,
    UNSUPPORTED_INSTALLATION,
    INSTALLATION,
}

/** Codes are stable UI inputs; diagnostic messages are not localized UI copy. */
public class UpdateException(
    public val code: UpdateErrorCode,
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause)

public sealed interface UpdateState {
    public data object Idle : UpdateState

    public data object Checking : UpdateState

    public data object UpToDate : UpdateState

    public data object NoCompatibleUpdate : UpdateState

    public data class Available(val offer: UpdateOffer) : UpdateState

    public data class Downloading(val offer: UpdateOffer, val bytes: Long) : UpdateState

    public data class Preparing(val offer: UpdateOffer) : UpdateState

    public data class Ready(val offer: UpdateOffer) : UpdateState

    public data class HandingOff(val offer: UpdateOffer) : UpdateState

    /** The host may now request normal exit. This does not mean installation succeeded. */
    public data class AwaitingExit(val transactionId: String) : UpdateState

    public data class Failed(val code: UpdateErrorCode) : UpdateState
}

internal fun fail(code: UpdateErrorCode, message: String): Nothing =
    throw UpdateException(code, message)

internal fun validIdentifier(value: String): Boolean =
    value.matches(Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,127}"))

internal fun isHttpsUrl(value: String): Boolean =
    value.length <= 4096 &&
        value.matches(Regex("https://[A-Za-z0-9.-]+(?::[0-9]{1,5})?(?:/[^\\s#]*)?"))

internal fun parseOsVersion(value: String): List<Int>? {
    if (!value.matches(Regex("[0-9]{1,9}(?:\\.[0-9]{1,9}){0,3}"))) return null
    return value.split('.').map { it.toInt() }
}

internal fun osAtLeast(actual: String, minimum: String): Boolean {
    val a = parseOsVersion(actual) ?: return false
    val b = parseOsVersion(minimum) ?: return false
    for (index in 0 until maxOf(a.size, b.size)) {
        val comparison = (a.getOrElse(index) { 0 }).compareTo(b.getOrElse(index) { 0 })
        if (comparison != 0) return comparison > 0
    }
    return true
}
