package com.pishi.termbridge.data

import com.pishi.termbridge.domain.JumpChain
import com.pishi.termbridge.domain.JumpChainValidator
import com.pishi.termbridge.domain.ProxyConfig
import com.pishi.termbridge.domain.ProxyKind
import com.pishi.termbridge.domain.ProxySpecParser
import com.pishi.termbridge.domain.TunnelConfig
import com.pishi.termbridge.domain.TunnelKind
import com.pishi.termbridge.domain.TunnelValidator
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/** A save/validation failure. Messages never contain a credential
 *  — this repository never sees one. */
class AdvancedRepositoryException(message: String) : Exception(message)

/**
 * Prompt 10 advanced-configuration repository. Rules enforced
 * here, not in the UI:
 * - Tunnel, chain, and proxy shapes are validated before any write.
 * - A tunnel/jump/proxy can only reference hosts that exist.
 * - A chain is replaced atomically (DAO transaction), never
 *   edited hop-by-hop in a way that could leave a half chain.
 * - Saving a NONE proxy deletes the proxy row instead of writing
 *   a meaningless row.
 * - Copy/delete-for-host are used by HostRepository's duplicate
 *   and delete paths: advanced config is non-secret, so a
 *   duplicate copies it, and a delete removes it.
 */
class AdvancedRepository(
    private val tunnelDao: TunnelDao,
    private val jumpHopDao: JumpHopDao,
    private val proxyDao: ProxyDao,
    private val hostDao: HostDao
) {
    fun getTunnels(hostId: Long): Flow<List<TunnelConfig>> =
        tunnelDao.getForHost(hostId).map { rows -> rows.map { it.toConfig() } }

    suspend fun getTunnelsOnce(hostId: Long): List<TunnelConfig> =
        tunnelDao.getForHostOnce(hostId).map { it.toConfig() }

    suspend fun saveTunnel(config: TunnelConfig): TunnelConfig {
        val issues = TunnelValidator.validate(config)
        if (issues.isNotEmpty()) {
            throw AdvancedRepositoryException(issues.joinToString("; ") { it.message })
        }
        requireHostExists(config.hostId)
        val isNew = config.id == 0L
        if (!isNew) {
            val existing = tunnelDao.getById(config.id)
                ?: throw AdvancedRepositoryException("Tunnel not found.")
            if (existing.hostId != config.hostId) {
                throw AdvancedRepositoryException("A tunnel cannot be moved to another host.")
            }
        }
        val sortOrder = if (isNew) {
            (tunnelDao.getForHostOnce(config.hostId).maxOfOrNull { it.sortOrder } ?: -1) + 1
        } else {
            tunnelDao.getById(config.id)!!.sortOrder
        }
        val entity = TunnelEntity(
            id = config.id,
            hostId = config.hostId,
            kind = config.kind.name,
            bindAddress = config.bindAddress.trim(),
            bindPort = config.bindPort,
            targetHost = config.targetHost.trim(),
            targetPort = config.targetPort,
            allowNonLoopback = config.allowNonLoopback,
            enabled = config.enabled,
            label = config.label.trim(),
            sortOrder = sortOrder
        )
        val savedId = if (isNew) tunnelDao.insert(entity) else {
            tunnelDao.update(entity)
            config.id
        }
        return config.copy(id = savedId)
    }

    suspend fun deleteTunnel(id: Long, hostId: Long) {
        val existing = tunnelDao.getById(id) ?: return
        if (existing.hostId != hostId) {
            throw AdvancedRepositoryException("A tunnel cannot be deleted from another host.")
        }
        tunnelDao.deleteById(id)
    }

    suspend fun setTunnelEnabled(id: Long, hostId: Long, enabled: Boolean) {
        val existing = tunnelDao.getById(id)
            ?: throw AdvancedRepositoryException("Tunnel not found.")
        if (existing.hostId != hostId) {
            throw AdvancedRepositoryException("A tunnel cannot be changed from another host.")
        }
        tunnelDao.update(existing.copy(enabled = enabled))
    }

    fun getJumpChain(targetHostId: Long): Flow<JumpChain> =
        jumpHopDao.getForTarget(targetHostId).map { rows ->
            JumpChain(targetHostId, rows.map { it.jumpHostId })
        }

    suspend fun getJumpChainOnce(targetHostId: Long): JumpChain {
        val rows = jumpHopDao.getForTargetOnce(targetHostId)
        return JumpChain(targetHostId, rows.map { it.jumpHostId })
    }

    suspend fun saveJumpChain(chain: JumpChain) {
        val issues = JumpChainValidator.validate(chain)
        if (issues.isNotEmpty()) {
            throw AdvancedRepositoryException(issues.joinToString("; ") { it.message })
        }
        requireHostExists(chain.targetHostId)
        for (jumpHostId in chain.jumpHostIds) {
            requireHostExists(jumpHostId)
        }
        jumpHopDao.replaceChain(chain.targetHostId, chain.jumpHostIds)
    }

    suspend fun clearJumpChain(targetHostId: Long) {
        jumpHopDao.deleteForTarget(targetHostId)
    }

    fun getProxy(hostId: Long): Flow<ProxyConfig> =
        proxyDao.getForHost(hostId).map { it?.toConfig() ?: ProxyConfig(hostId) }

    suspend fun getProxyOnce(hostId: Long): ProxyConfig =
        proxyDao.getForHostOnce(hostId)?.toConfig() ?: ProxyConfig(hostId)

    suspend fun saveProxy(config: ProxyConfig) {
        val issues = ProxySpecParser.validate(config)
        if (issues.isNotEmpty()) {
            throw AdvancedRepositoryException(issues.joinToString("; ") { it.message })
        }
        requireHostExists(config.hostId)
        if (config.kind == ProxyKind.NONE) {
            proxyDao.deleteForHost(config.hostId)
            return
        }
        val existing = proxyDao.getForHostOnce(config.hostId)
        val entity = ProxyEntity(
            hostId = config.hostId,
            kind = config.kind.name,
            proxyHost = config.proxyHost.trim(),
            proxyPort = config.proxyPort
        )
        if (existing == null) proxyDao.insert(entity) else proxyDao.update(entity)
    }

    /** Copy non-secret advanced config to a duplicated host. */
    suspend fun copyForDuplicate(sourceHostId: Long, newHostId: Long) {
        for (tunnel in tunnelDao.getForHostOnce(sourceHostId)) {
            tunnelDao.insert(tunnel.copy(id = 0L, hostId = newHostId))
        }
        val hops = jumpHopDao.getForTargetOnce(sourceHostId)
        if (hops.isNotEmpty()) {
            jumpHopDao.replaceChain(newHostId, hops.map { it.jumpHostId })
        }
        val proxy = proxyDao.getForHostOnce(sourceHostId)
        if (proxy != null) {
            proxyDao.insert(proxy.copy(hostId = newHostId))
        }
    }

    suspend fun deleteForHost(hostId: Long) {
        tunnelDao.deleteForHost(hostId)
        jumpHopDao.deleteForTarget(hostId)
        proxyDao.deleteForHost(hostId)
    }

    private suspend fun requireHostExists(hostId: Long) {
        if (hostDao.getById(hostId) == null) {
            throw AdvancedRepositoryException("Host not found.")
        }
    }

    private fun TunnelEntity.toConfig(): TunnelConfig = TunnelConfig(
        id = id,
        hostId = hostId,
        kind = TunnelKind.valueOf(kind),
        bindAddress = bindAddress,
        bindPort = bindPort,
        targetHost = targetHost,
        targetPort = targetPort,
        allowNonLoopback = allowNonLoopback,
        enabled = enabled,
        label = label
    )

    private fun ProxyEntity.toConfig(): ProxyConfig = ProxyConfig(
        hostId = hostId,
        kind = ProxyKind.valueOf(kind),
        proxyHost = proxyHost,
        proxyPort = proxyPort
    )
}
