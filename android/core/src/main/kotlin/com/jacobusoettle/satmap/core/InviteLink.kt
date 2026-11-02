package com.jacobusoettle.satmap.core

/** An invite link (docs/groups/SPEC.md §5.2): `https://{domain}/j/{inviteToken}#{groupKey}`. The token's first 16
 *  bytes are the group id; the key travels only in the fragment, never to the server. The landing page's fallback is
 *  `satmap://join/{inviteToken}#{groupKey}` (SD15); the apps never share it.
 *
 *  The fields as they are; `parse` and `InviteLink(token =, groupKey =)` are the checked ways to make one. */
class InviteLink(
    /** 43 base64url characters: the group id and the invite secret. */
    val token: String,
    /** 16 bytes. */
    groupId: ByteArray,
    /** 32 bytes. */
    groupKey: ByteArray,
) {
    private val groupIdBytes = groupId.copyOf()
    private val groupKeyBytes = groupKey.copyOf()

    /** 16 bytes (a copy). */
    val groupId: ByteArray get() = groupIdBytes.copyOf()

    /** 32 bytes (a copy). */
    val groupKey: ByteArray get() = groupKeyBytes.copyOf()

    val groupIdText: String get() = Base64URL.encode(groupIdBytes)

    fun url(domain: String): String = "https://$domain/j/$token#${Base64URL.encode(groupKeyBytes)}"

    val schemeURL: String get() = "satmap://join/$token#${Base64URL.encode(groupKeyBytes)}"

    override fun equals(other: Any?): Boolean =
        other is InviteLink && other.token == token && other.groupIdBytes.contentEquals(groupIdBytes) &&
            other.groupKeyBytes.contentEquals(groupKeyBytes)

    override fun hashCode(): Int = (token.hashCode() * 31 + groupIdBytes.contentHashCode()) * 31 + groupKeyBytes.contentHashCode()

    /** The token only: the key never goes into logs. */
    override fun toString(): String = "InviteLink(token=$token)"

    sealed interface Parsed {
        data class Invite(val link: InviteLink) : Parsed

        /** An invite link without its key (the "incomplete link" text). */
        data object MissingKey : Parsed

        data object NotInvite : Parsed
    }

    companion object {
        /** The link for a token and key, or null if the token isn't 43 base64url characters or the key isn't 32 bytes
         *  (Swift's failable `init?(token:groupKey:)`). */
        operator fun invoke(token: String, groupKey: ByteArray): InviteLink? {
            if (token.length != 43 || groupKey.size != 32) return null
            val tokenBytes = Base64URL.decode(token, count = 32) ?: return null
            return InviteLink(token = token, groupId = tokenBytes.copyOf(16), groupKey = groupKey)
        }

        /** Finds an invite link in any text, such as a whole pasted message (§5.2): the earliest full link for
         *  `domain` (the host compared ignoring ASCII case) or in the `satmap://` form; otherwise `MissingKey` if
         *  there is a link whose key is missing or damaged; otherwise `NotInvite`. */
        fun parse(text: String, domain: String): Parsed {
            val host = domain.lowercase()
            val found = mutableListOf<Triple<Int, String, String>>()
            for (m in fullHTTPS.findAll(text)) {
                if (m.groupValues[1].lowercase() == host) found.add(Triple(m.range.first, m.groupValues[2], m.groupValues[3]))
            }
            for (m in fullScheme.findAll(text)) {
                found.add(Triple(m.range.first, m.groupValues[1], m.groupValues[2]))
            }
            val first = found.minByOrNull { it.first }
            if (first != null) {
                val keyBytes = Base64URL.decode(first.third, count = 32)
                val link = keyBytes?.let { InviteLink(token = first.second, groupKey = it) }
                if (link != null) return Parsed.Invite(link)
            }
            val keylessHere = keylessHTTPS.findAll(text).any { it.groupValues[1].lowercase() == host }
            if (keylessHere || keylessScheme.containsMatchIn(text)) return Parsed.MissingKey
            return Parsed.NotInvite
        }

        // The patterns of §5.2, case-sensitive (only the host comparison ignores case).
        private val fullHTTPS = Regex("https://([A-Za-z0-9.-]+)/j/([A-Za-z0-9_-]{43})/?#([A-Za-z0-9_-]{43})(?![A-Za-z0-9_-])")
        private val fullScheme = Regex("satmap://join/([A-Za-z0-9_-]{43})/?#([A-Za-z0-9_-]{43})(?![A-Za-z0-9_-])")
        private val keylessHTTPS = Regex("https://([A-Za-z0-9.-]+)/j/([A-Za-z0-9_-]{43})(?![A-Za-z0-9_-])")
        private val keylessScheme = Regex("satmap://join/([A-Za-z0-9_-]{43})(?![A-Za-z0-9_-])")
    }
}
