package com.shilapi.xcertplay.network

import android.net.wifi.p2p.WifiP2pManager
import java.io.IOException

internal enum class P2pCreationMode { ALIGNED_5_GHZ, ALIGNED_2_GHZ, FIXED_5_GHZ, FIXED_2_GHZ, SYSTEM_DEFAULT, PREFERRED_CHANNEL }

internal data class P2pCreationRequest(val mode: P2pCreationMode, val frequencyMHz: Int? = null)

internal class P2pCreateRejected(val reason: Int, message: String) : IOException(message)

/** Raised only for a recognized framework defect before createGroup has been called. */
internal class P2pConfigBuildCompatibilityFailure(cause: NoSuchMethodError) :
    IOException("Wi-Fi P2P custom configuration unavailable on this Android 10 framework", cause)

/** Only a rejection or recognized pre-create defect permits retry; timeout may still create a group. */
internal object P2pStartupRecovery {
    fun rememberedFrequency(frequency: Int): P2pCreationRequest? = when {
        frequency in 2412..2462 && (frequency - 2412) % 5 == 0 ->
            P2pCreationRequest(P2pCreationMode.FIXED_2_GHZ, frequency)
        frequency in listOf(5180, 5200, 5220, 5240, 5745, 5765, 5785, 5805, 5825) ->
            P2pCreationRequest(P2pCreationMode.FIXED_5_GHZ, frequency)
        else -> null
    }

    /** A band-only request still needs channel selection, which some BYD drivers cannot do. */
    fun plan(stationFrequency: Int?, preferred: P2pCreationRequest? = null,
             preferredChannel: Int = WifiP2pChannels.AUTO): List<P2pCreationRequest> = buildList {
        WifiP2pChannels.frequencyMhz(preferredChannel)?.let {
            add(P2pCreationRequest(P2pCreationMode.PREFERRED_CHANNEL, it))
            return@buildList
        }
        val aligned24 = stationFrequency != null && stationFrequency in 2412..2462 &&
            (stationFrequency - 2412) % 5 == 0
        val aligned5 = stationFrequency in listOf(5180, 5200, 5220, 5240, 5745, 5765, 5785, 5805, 5825)
        val frequencies = mutableSetOf<Int>()
        fun channel(mode: P2pCreationMode, frequency: Int) {
            if (frequencies.add(frequency)) add(P2pCreationRequest(mode, frequency))
        }
        if (preferred?.mode == P2pCreationMode.SYSTEM_DEFAULT && preferred.frequencyMHz == null) add(preferred)
        else preferred?.frequencyMHz?.let(::rememberedFrequency)?.let { channel(it.mode, it.frequencyMHz!!) }
        if (aligned24) channel(P2pCreationMode.ALIGNED_2_GHZ, requireNotNull(stationFrequency))
        if (aligned5) channel(P2pCreationMode.ALIGNED_5_GHZ, requireNotNull(stationFrequency))
        fun twoGhz() = listOf(2437, 2412, 2462).forEach { channel(P2pCreationMode.FIXED_2_GHZ, it) }
        fun fiveGhz() = listOf(5180, 5745).forEach { channel(P2pCreationMode.FIXED_5_GHZ, it) }

        // Android TV is often not associated with a normal Wi-Fi network. In that case
        // stationFrequency is null and the old plan tried 5 GHz first. Some TV Wi-Fi
        // chipsets expose P2P correctly but have weaker legacy-station interoperability
        // with 5 GHz P2P groups. Prefer the most broadly compatible 2.4 GHz channels first
        // when there is no existing station channel to preserve; 5 GHz remains an automatic
        // fallback. If the TV is already associated, keep the existing aligned-band choice.
        if (aligned24) {
            twoGhz()
            fiveGhz()
        } else if (aligned5) {
            fiveGhz()
            twoGhz()
        } else {
            twoGhz()
            fiveGhz()
        }

        // Some vendors only implement the default-configuration API. Use it last, after
        // explicit-frequency options have been rejected.
        if (none { it.mode == P2pCreationMode.SYSTEM_DEFAULT }) add(P2pCreationRequest(P2pCreationMode.SYSTEM_DEFAULT))
    }

    fun create(
        stationFrequency: Int?,
        beforeRetry: () -> Unit,
        preferred: P2pCreationRequest? = null,
        preferredChannel: Int = WifiP2pChannels.AUTO,
        request: (P2pCreationRequest) -> Unit,
    ): P2pCreationRequest {
        val modes = plan(stationFrequency, preferred, preferredChannel)
        var lastRejection: P2pCreateRejected? = null
        for ((index, mode) in modes.withIndex()) {
            var retriedBusy = false
            while (true) {
                try {
                    request(mode)
                    return mode
                } catch (failure: P2pConfigBuildCompatibilityFailure) {
                    if (preferredChannel != WifiP2pChannels.AUTO) {
                        throw P2pChannelUnavailableException(preferredChannel, failure.message.orEmpty(), failure)
                    }
                    if (mode.mode == P2pCreationMode.SYSTEM_DEFAULT) throw failure
                    beforeRetry()
                    val systemDefault = P2pCreationRequest(P2pCreationMode.SYSTEM_DEFAULT)
                    request(systemDefault)
                    return systemDefault
                } catch (failure: P2pCreateRejected) {
                    lastRejection = failure
                    when {
                        failure.reason == WifiP2pManager.NO_PERMISSION ||
                            failure.reason == WifiP2pManager.P2P_UNSUPPORTED -> throw failure
                        failure.reason == WifiP2pManager.BUSY && !retriedBusy -> {
                            retriedBusy = true
                            beforeRetry()
                        }
                        index < modes.lastIndex -> {
                            beforeRetry()
                            break
                        }
                        else -> if (preferredChannel != WifiP2pChannels.AUTO) {
                            throw P2pChannelUnavailableException(preferredChannel, failure.message.orEmpty(), failure)
                        } else throw failure
                    }
                }
            }
        }
        throw lastRejection ?: error("No Wi-Fi Direct creation mode attempted")
    }
}
