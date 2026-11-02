package com.pishi.termbridge.domain

/**
 * Which addresses to probe when looking for devices on the local
 * network. The scan is deliberately limited to at most one /24 around
 * the phone's own address (254 hosts), so a large corporate network
 * can never turn "discover" into a sweep of thousands of addresses.
 */
object SubnetPlanner {
    fun hostsToScan(ownIpv4: String, prefixLength: Int): List<String> {
        val parts = ownIpv4.split('.')
        if (parts.size != 4) return emptyList()
        val octets = parts.map { it.toIntOrNull() ?: return emptyList() }
        if (octets.any { it !in 0..255 }) return emptyList()
        // Never scan wider than a /24 around the own address.
        val prefix = prefixLength.coerceIn(24, 30)
        val own = (octets[0] shl 24) or (octets[1] shl 16) or (octets[2] shl 8) or octets[3]
        val mask = (-1 shl (32 - prefix))
        val network = own and mask
        val broadcast = network or mask.inv()
        val result = ArrayList<String>()
        var address = network + 1
        while (address < broadcast) {
            if (address != own) result += formatIpv4(address)
            address++
        }
        return result
    }

    private fun formatIpv4(value: Int): String =
        "${(value ushr 24) and 255}.${(value ushr 16) and 255}.${(value ushr 8) and 255}.${value and 255}"
}
