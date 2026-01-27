package com.streamplayer.tv

import android.net.Uri
import android.util.Log
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import io.github.thibaultbee.srtdroid.core.enums.SockOpt
import io.github.thibaultbee.srtdroid.core.enums.Transtype
import io.github.thibaultbee.srtdroid.core.models.SrtSocket
import java.io.IOException
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * Media3 DataSource for SRT protocol using srtdroid (Haivision libsrt bindings).
 *
 * Accepts standard SRT URLs:
 *   srt://host:port?streamid=mystream&latency=200&passphrase=secret&pbkeylen=24&tsbpdmode
 *
 * Passes raw received bytes directly to ExoPlayer — the TsExtractor handles
 * MPEG-TS packet boundary detection internally.
 */
@UnstableApi
class SrtDataSource : BaseDataSource(/* isNetwork = */ true) {

    companion object {
        private const val TAG = "SrtDataSource"
        private const val SRT_PAYLOAD_SIZE = 1316
        private const val DEFAULT_CONNECT_TIMEOUT_MS = 10000
        // Default receive buffer 8MB to prevent audio dropouts
        private const val DEFAULT_RCVBUF_SIZE = 8 * 1024 * 1024
    }

    // Buffer for received SRT data not yet consumed by ExoPlayer
    private var pendingData: ByteArray? = null
    private var pendingOffset = 0

    private var socket: SrtSocket? = null
    private var currentUri: Uri? = null

    override fun open(dataSpec: DataSpec): Long {
        transferInitializing(dataSpec)

        val uri = dataSpec.uri
        currentUri = uri

        val host = uri.host ?: throw IOException("SRT URL missing host")
        val port = uri.port.takeIf { it > 0 } ?: throw IOException("SRT URL missing port")

        val params = parseAndNormalizeParams(uri)

        Log.i(TAG, "Opening SRT connection to $host:$port with params: $params")

        try {
            val sock = SrtSocket()

            // Transport type: always live for streaming
            sock.setSockFlag(SockOpt.TRANSTYPE, Transtype.LIVE)

            // Synchronous receive
            sock.setSockFlag(SockOpt.RCVSYN, true)

            // Connection timeout
            val connectTimeout = params["connect_timeout"]?.toIntOrNull() ?: DEFAULT_CONNECT_TIMEOUT_MS
            sock.setSockFlag(SockOpt.CONNTIMEO, connectTimeout)

            // Latency
            params["latency"]?.toIntOrNull()?.let {
                sock.setSockFlag(SockOpt.LATENCY, it)
            }

            // Receiver latency
            params["rcvlatency"]?.toIntOrNull()?.let {
                sock.setSockFlag(SockOpt.RCVLATENCY, it)
            }

            // Peer latency
            params["peerlatency"]?.toIntOrNull()?.let {
                sock.setSockFlag(SockOpt.PEERLATENCY, it)
            }

            // Encryption passphrase (must be set before pbkeylen)
            params["passphrase"]?.let {
                if (it.isNotEmpty()) {
                    sock.setSockFlag(SockOpt.PASSPHRASE, it)
                }
            }

            // Encryption key length (16, 24, or 32)
            params["pbkeylen"]?.toIntOrNull()?.let {
                sock.setSockFlag(SockOpt.PBKEYLEN, it)
            }

            // Timestamp-based packet delivery mode
            params["tsbpd"]?.let {
                val enabled = it.isEmpty() || it == "1" || it.equals("true", ignoreCase = true)
                sock.setSockFlag(SockOpt.TSBPDMODE, enabled)
            }

            // Stream ID
            params["streamid"]?.let {
                sock.setSockFlag(SockOpt.STREAMID, it)
            }
            params["srt_streamid"]?.let {
                sock.setSockFlag(SockOpt.STREAMID, it)
            }

            // Too-late packet drop (enabled by default to prevent audio glitches)
            val tlpktdrop = params["tlpktdrop"]?.let {
                it.isEmpty() || it == "1" || it.equals("true", ignoreCase = true)
            } ?: true
            sock.setSockFlag(SockOpt.TLPKTDROP, tlpktdrop)

            // NAK report
            params["nakreport"]?.let {
                val enabled = it.isEmpty() || it == "1" || it.equals("true", ignoreCase = true)
                sock.setSockFlag(SockOpt.NAKREPORT, enabled)
            }

            // Max segment size
            params["mss"]?.toIntOrNull()?.let {
                sock.setSockFlag(SockOpt.MSS, it)
            }

            // Payload size
            params["payload_size"]?.toIntOrNull()?.let {
                sock.setSockFlag(SockOpt.PAYLOADSIZE, it)
            }
            params["pkt_size"]?.toIntOrNull()?.let {
                sock.setSockFlag(SockOpt.PAYLOADSIZE, it)
            }

            // Input bandwidth
            params["inputbw"]?.toLongOrNull()?.let {
                sock.setSockFlag(SockOpt.INPUTBW, it)
            }

            // Max bandwidth
            params["maxbw"]?.toLongOrNull()?.let {
                sock.setSockFlag(SockOpt.MAXBW, it)
            }

            // Overhead bandwidth
            params["oheadbw"]?.toIntOrNull()?.let {
                sock.setSockFlag(SockOpt.OHEADBW, it)
            }

            // Enforced encryption
            params["enforced_encryption"]?.let {
                val enabled = it.isEmpty() || it == "1" || it.equals("true", ignoreCase = true)
                sock.setSockFlag(SockOpt.ENFORCEDENCRYPTION, enabled)
            }

            // Linger
            params["linger"]?.toIntOrNull()?.let {
                sock.setSockFlag(SockOpt.LINGER, it)
            }

            // Flight flag size
            params["ffs"]?.toIntOrNull()?.let {
                sock.setSockFlag(SockOpt.FC, it)
            }

            // Send/recv buffer sizes (default larger rcvbuf to prevent audio dropouts)
            params["sndbuf"]?.toIntOrNull()?.let {
                sock.setSockFlag(SockOpt.SNDBUF, it)
            }
            val rcvBufSize = params["rcvbuf"]?.toIntOrNull() ?: DEFAULT_RCVBUF_SIZE
            sock.setSockFlag(SockOpt.RCVBUF, rcvBufSize)

            // IP TOS / TTL
            params["iptos"]?.toIntOrNull()?.let {
                sock.setSockFlag(SockOpt.IPTOS, it)
            }
            params["ipttl"]?.toIntOrNull()?.let {
                sock.setSockFlag(SockOpt.IPTTL, it)
            }

            // Connect
            Log.i(TAG, "Connecting to $host:$port...")
            sock.connect(host, port)

            socket = sock
            Log.i(TAG, "SRT connected successfully to $host:$port")
            transferStarted(dataSpec)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to open SRT connection to $host:$port — ${e.message}", e)
            throw IOException("SRT connection failed: ${e.message}", e)
        }

        return C.LENGTH_UNSET.toLong()
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0

        val sock = socket ?: throw IOException("SRT socket is not connected")

        try {
            // If we have leftover data from a previous recv, serve that first
            val pending = pendingData
            if (pending != null && pendingOffset < pending.size) {
                val remaining = pending.size - pendingOffset
                val toCopy = minOf(remaining, length)
                System.arraycopy(pending, pendingOffset, buffer, offset, toCopy)
                pendingOffset += toCopy
                if (pendingOffset >= pending.size) {
                    pendingData = null
                    pendingOffset = 0
                }
                bytesTransferred(toCopy)
                return toCopy
            }

            // Receive a new SRT payload (blocking call)
            val received = sock.recv(SRT_PAYLOAD_SIZE)
            if (received.isEmpty()) {
                Log.w(TAG, "SRT recv returned empty — stream may have ended")
                return C.RESULT_END_OF_INPUT
            }

            // Copy as much as fits into the output buffer
            val toCopy = minOf(received.size, length)
            System.arraycopy(received, 0, buffer, offset, toCopy)

            // Store remainder if any
            if (toCopy < received.size) {
                pendingData = received
                pendingOffset = toCopy
            }

            bytesTransferred(toCopy)
            return toCopy

        } catch (e: Exception) {
            Log.e(TAG, "SRT read error: ${e.message}", e)
            throw IOException("SRT read failed: ${e.message}", e)
        }
    }

    override fun getUri(): Uri? = currentUri

    override fun close() {
        Log.i(TAG, "Closing SRT connection")
        pendingData = null
        pendingOffset = 0
        try {
            socket?.close()
        } catch (e: Exception) {
            Log.w(TAG, "Error closing SRT socket: ${e.message}")
        }
        socket = null
        currentUri = null
        transferEnded()
    }

    /**
     * Parse URI query parameters and normalize common aliases.
     */
    private fun parseAndNormalizeParams(uri: Uri): Map<String, String> {
        val params = mutableMapOf<String, String>()
        for (key in uri.queryParameterNames) {
            val value = uri.getQueryParameter(key) ?: ""
            val normalizedKey = normalizeParamName(key)
            if (normalizedKey == "mode") continue
            params[normalizedKey] = value
        }
        return params
    }

    private fun normalizeParamName(name: String): String {
        return when (name.lowercase()) {
            "tsbpdmode", "tsbpd" -> "tsbpd"
            "pbkeylen" -> "pbkeylen"
            "passphrase" -> "passphrase"
            "streamid", "srt_streamid" -> "streamid"
            "latency" -> "latency"
            "rcvlatency" -> "rcvlatency"
            "peerlatency" -> "peerlatency"
            "connect_timeout", "conntimeo" -> "connect_timeout"
            "snddropdelay" -> "snddropdelay"
            "tlpktdrop" -> "tlpktdrop"
            "nakreport" -> "nakreport"
            "payload_size", "payloadsize" -> "payload_size"
            "pkt_size", "pktsize" -> "pkt_size"
            "inputbw" -> "inputbw"
            "maxbw" -> "maxbw"
            "oheadbw" -> "oheadbw"
            "enforced_encryption" -> "enforced_encryption"
            "linger" -> "linger"
            "ffs" -> "ffs"
            "mss" -> "mss"
            "sndbuf" -> "sndbuf"
            "rcvbuf" -> "rcvbuf"
            "iptos" -> "iptos"
            "ipttl" -> "ipttl"
            "lossmaxttl" -> "lossmaxttl"
            "minversion" -> "minversion"
            "smoother" -> "smoother"
            "messageapi" -> "messageapi"
            "transtype" -> "transtype"
            "kmrefreshrate" -> "kmrefreshrate"
            "kmpreannounce" -> "kmpreannounce"
            "recv_buffer_size" -> "rcvbuf"
            "send_buffer_size" -> "sndbuf"
            else -> name.lowercase()
        }
    }

    /**
     * Factory for creating SrtDataSource instances.
     */
    class Factory : DataSource.Factory {
        override fun createDataSource(): SrtDataSource = SrtDataSource()
    }
}
