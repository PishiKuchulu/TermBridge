package com.pishi.termbridge.domain

/**
 * RFB (Remote Framebuffer) protocol constants and negotiation
 * decisions — prompt 13, pure-Kotlin VNC protocol core.
 *
 * Source of truth: RFC 6143 (RFB 3.8) and the community RFB
 * protocol document. Wire facts used here:
 * - Version string: exactly 12 bytes, "RFB 003.008\n" style.
 * - 3.8 / 3.7: server sends a LIST of security types (U8 count,
 *   then U8 types); count 0 means failure + reason string.
 * - 3.3: server sends a SINGLE U32 security type; 0 means failure.
 * - SecurityResult (U32, 0 = OK) follows authentication in 3.7 /
 *   3.8 always, and in 3.3 only for VNC Authentication — for 3.3 +
 *   None there is no SecurityResult at all.
 * - All multi-byte integers are big-endian, EXCEPT pixel values,
 *   which follow the negotiated pixel format's endianness flag.
 *
 * This file contains no I/O and no secrets. Error messages are
 * fixed strings: they never embed hostnames, passwords, or
 * server-supplied text (a failure reason string is read and
 * discarded, never surfaced).
 */
class RfbException(message: String) : Exception(message)

/** Negotiated RFB version plus which security handshake applies. */
enum class RfbVersion(val wireString: String) {
    /** 3.3: single security type, no SecurityResult for None. */
    V3_3("RFB 003.003\n"),

    /** 3.7: security-type list, SecurityResult always. */
    V3_7("RFB 003.007\n"),

    /** 3.8: security-type list, SecurityResult always. */
    V3_8("RFB 003.008\n");

    /** True for the list-based handshake (3.7 and 3.8). */
    val usesSecurityList: Boolean
        get() = this != V3_3
}

object RfbProtocol {

    /** The version string is always exactly this many bytes. */
    const val VERSION_LENGTH = 12

    /** Default VNC port (display :0). Display :N is 5900 + N. */
    const val DEFAULT_PORT = 5900

    /** Hard caps on framebuffer size, against a hostile server
     *  forcing a huge allocation. A real desktop fits comfortably
     *  inside these; anything larger is rejected, not attempted. */
    const val MAX_FRAMEBUFFER_WIDTH = 16384
    const val MAX_FRAMEBUFFER_HEIGHT = 16384
    const val MAX_FRAMEBUFFER_PIXELS = 33_554_432

    /** Clipboard text cap (ServerCutText / ClientCutText). */
    const val MAX_CUT_TEXT_BYTES = 1_048_576

    /**
     * Select the protocol version from the server's 12-byte
     * version string.
     *
     * Exact matches for 3.3 / 3.7 / 3.8 select that version. Any
     * other 3.x version falls back to 3.3 semantics (the lowest
     * common denominator, which every RFB server must still
     * honour) — this is the standard robust-client behaviour for
     * servers advertising versions such as 3.6, not a silent
     * upgrade: the negotiated version is recorded on the session.
     * A non-3 major version, or a malformed string, is rejected.
     */
    fun negotiateVersion(serverVersion: String): RfbVersion {
        if (serverVersion.length != VERSION_LENGTH ||
            !serverVersion.startsWith("RFB ") ||
            !serverVersion.endsWith("\n")
        ) {
            throw RfbException("Unsupported RFB version.")
        }
        val major = serverVersion.substring(4, 7)
        val minor = serverVersion.substring(8, 11)
        if (major != "003" || !minor.all { it.isDigit() }) {
            throw RfbException("Unsupported RFB version.")
        }
        return when (minor) {
            "003" -> RfbVersion.V3_3
            "007" -> RfbVersion.V3_7
            "008" -> RfbVersion.V3_8
            else -> RfbVersion.V3_3
        }
    }

    /** Validate framebuffer dimensions against the hard caps. */
    fun checkDimensions(width: Int, height: Int) {
        if (width <= 0 || height <= 0 ||
            width > MAX_FRAMEBUFFER_WIDTH ||
            height > MAX_FRAMEBUFFER_HEIGHT ||
            width.toLong() * height.toLong() > MAX_FRAMEBUFFER_PIXELS
        ) {
            throw RfbException("Framebuffer size is out of range.")
        }
    }
}

/** Security types the client understands. Anything else offered
 *  by a server is rejected — never silently downgraded or
 *  accepted. VeNCrypt (19), TLS (18), Tight (16) and X.509
 *  variants are NOT implemented in this phase (see VNC_NOTES.md);
 *  offering only those is a clean failure, not a fallback. */
object RfbSecurityType {
    const val INVALID = 0
    const val NONE = 1
    const val VNC_AUTH = 2

    /** True if this type is one the client can actually perform. */
    fun isSupported(type: Int): Boolean = type == NONE || type == VNC_AUTH
}

/** Client-to-server message types (RFB 3.8 §6.2). */
object RfbClientMessage {
    const val SET_PIXEL_FORMAT = 0
    const val SET_ENCODINGS = 2
    const val FRAMEBUFFER_UPDATE_REQUEST = 3
    const val KEY_EVENT = 4
    const val POINTER_EVENT = 5
    const val CLIENT_CUT_TEXT = 6
}

/** Server-to-client message types (RFB 3.8 §6.3). */
object RfbServerMessageType {
    const val FRAMEBUFFER_UPDATE = 0
    const val SET_COLOUR_MAP_ENTRIES = 1
    const val BELL = 2
    const val SERVER_CUT_TEXT = 3
}

/**
 * Encoding types. Only the first four are implemented in this
 * phase; the rest are recognised by number so the decoder can
 * reject them with a clear message instead of misinterpreting
 * their bytes as another encoding.
 */
object RfbEncoding {
    const val RAW = 0
    const val COPY_RECT = 1
    const val RRE = 2
    const val CORRE = 4
    const val HEXTILE = 5
    const val ZLIB = 6
    const val TIGHT = 7
    const val ZLIB_HEX = 8
    const val TRLE = 15
    const val ZRLE = 16

    /** Pseudo-encodings (capability flags, not pixel data). */
    const val DESKTOP_SIZE = -223
    const val CURSOR = -239

    /** True if the decoder can apply a rectangle of this type. */
    fun isImplemented(encoding: Int): Boolean =
        encoding == RAW || encoding == COPY_RECT ||
            encoding == RRE || encoding == CORRE ||
            encoding == HEXTILE

    /** Encodings the client advertises in SetEncodings, in
     *  preference order. Raw is always listed last as the
     *  guaranteed fallback (every server must support it). */
    val ADVERTISED: IntArray =
        intArrayOf(HEXTILE, RRE, COPY_RECT, RAW)
}
