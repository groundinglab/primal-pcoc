package net.primal.android.pcoc.create

import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import net.primal.android.pcoc.admit.canonicalAccountHex
import net.primal.android.pcoc.bridge.PcocNative
import net.primal.android.pcoc.bridge.ProduceListener
import net.primal.android.pcoc.enroll.EnrollOutcome
import net.primal.android.pcoc.enroll.EnrollPcoc
import net.primal.android.pcoc.enroll.EnrolledPcocStore
import net.primal.domain.nostr.cryptography.utils.hexToNpubHrp

/**
 * One live hike for the process. Leaving the screen does not stop it.
 * The object is written only when the reference returns. Stop writes nothing.
 */
@Singleton
class ProduceSession @Inject constructor(
    @ApplicationContext private val context: Context,
    store: EnrolledPcocStore,
) {
    private val enroll = EnrollPcoc(store)
    private val _state = MutableStateFlow(CreateUiState())
    val state = _state.asStateFlow()
    private val listener = ProduceListener { json -> onNative(json) }

    fun setAccount(rawHex: String) {
        val hex = canonicalAccountHex(rawHex)
        val npub = hex?.let { value -> runCatching { value.hexToNpubHrp() }.getOrNull() }
        _state.update { current ->
            if (current.phase == ProducePhase.Running || current.phase == ProducePhase.Settling) {
                current
            } else {
                current.copy(accountHex = hex, npub = npub)
            }
        }
    }

    fun start() {
        val current = _state.value
        val account = current.accountHex ?: return
        if (current.phase == ProducePhase.Running || current.phase == ProducePhase.Settling) return
        val directory = File(context.filesDir, "pcoc/hikes").apply { mkdirs() }
        val output = File(directory, objectFileName(System.currentTimeMillis()))
        _state.update {
            it.copy(
                phase = ProducePhase.Running,
                outputPath = output.absolutePath,
                stage = 0,
                stagesClosed = 0,
                legsTotal = 0,
                legsInStage = 0,
                seedHeight = null,
                seedTimeUnixS = null,
                openBeaconHeight = null,
                tipHeight = null,
                closedStages = emptyList(),
                waypoints = emptyList(),
                legTicks = emptyList(),
                legRatePerSecond = null,
                logLines = emptyList(),
                message = null,
                objectBytes = null,
                objectSha256 = null,
                savedForAccount = false,
            )
        }
        startService()
        try {
            PcocNative.startProduce(
                publicKeyHex = account,
                outputPath = output.absolutePath,
                gossipPeer = "",
                listener = listener,
            )
        } catch (error: UnsatisfiedLinkError) {
            _state.update {
                it.copy(phase = ProducePhase.Failed, message = "The hike engine is not in this build.")
            }
            stopService()
        } catch (error: RuntimeException) {
            _state.update {
                it.copy(phase = ProducePhase.Failed, message = "The hike engine is not in this build.")
            }
            stopService()
        }
    }

    fun stop() {
        try {
            PcocNative.stopProduce()
        } catch (error: UnsatisfiedLinkError) {
            _state.update { it.copy(phase = ProducePhase.Stopped) }
            stopService()
        }
    }

    fun reset() {
        val current = _state.value
        if (current.phase == ProducePhase.Running || current.phase == ProducePhase.Settling) return
        _state.value = CreateUiState(accountHex = current.accountHex, npub = current.npub)
    }

    private fun onNative(json: String) {
        val event = parseProduceEvent(json) ?: return
        _state.update { reduce(it, event, nowMs = System.currentTimeMillis()) }
        when (event) {
            is ProduceEvent.Written -> {
                saveForAccount(event)
                stopService()
            }
            ProduceEvent.Stopped, is ProduceEvent.Failed -> stopService()
            else -> Unit
        }
    }

    private fun saveForAccount(event: ProduceEvent.Written) {
        val hex = _state.value.accountHex
        val bytes = if (hex == null) null else runCatching { File(event.path).readBytes() }.getOrNull()
        val saved = hex != null && bytes != null &&
            enroll.enroll(
                objectBytes = bytes,
                accountHex = hex,
                fileName = File(event.path).name,
                savedAtEpochMs = System.currentTimeMillis(),
            ) is EnrollOutcome.Stored
        _state.update { it.copy(savedForAccount = saved) }
    }

    private fun startService() {
        val intent = Intent(context, PcocProduceService::class.java)
        try {
            ContextCompat.startForegroundService(context, intent)
        } catch (error: IllegalStateException) {
            // The hike still runs. The system may stop the process if the screen is off.
        } catch (error: SecurityException) {
            // Same as above: no notification, the produce thread is already the job.
        }
    }

    private fun stopService() {
        context.stopService(Intent(context, PcocProduceService::class.java))
    }
}
