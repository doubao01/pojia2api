package io.modelbridge.core

import java.net.Inet4Address
import java.net.InetAddress
import java.net.NetworkInterface

/** 首页 Agent 接入地址：从网卡枚举结果里选出可复制的局域网地址。 */
object LanAddresses {
    data class Candidate(
        val interfaceName: String,
        val address: InetAddress,
    )

    /** 优先常见 Wi-Fi / 以太网 / 蜂窝网卡，跳过回环、未启用与虚拟网卡。 */
    fun pick(candidates: Sequence<Candidate>): InetAddress? {
        val usable = candidates.filter { (name, address) ->
            address is Inet4Address &&
                !address.isLoopbackAddress &&
                address.isSiteLocalAddress &&
                !address.isLinkLocalAddress &&
                address.hostAddress?.isNullOrBlank() == false &&
                !name.startsWith("dummy", ignoreCase = true)
        }.toList()
        return usable.firstOrNull { (name, _) ->
            name.startsWith("wlan", ignoreCase = true) || name.startsWith("eth", ignoreCase = true)
        }?.address ?: usable.firstOrNull()?.address
    }

    /** Android 运行时枚举网卡；异常时返回空列表，不影响首页显示。 */
    fun current(): Sequence<Candidate> = runCatching {
        NetworkInterface.getNetworkInterfaces().asSequence().flatMap { nic ->
            nic.inetAddresses.asSequence().map { address ->
                Candidate(nic.name.orEmpty(), address)
            }
        }
    }.getOrDefault(emptySequence())

    fun baseUrl(address: InetAddress?, port: Int): String? {
        if (port <= 0) return null
        val host = address?.hostAddress?.takeIf { it.isNotBlank() } ?: return null
        return "http://$host:$port/v1"
    }
}
