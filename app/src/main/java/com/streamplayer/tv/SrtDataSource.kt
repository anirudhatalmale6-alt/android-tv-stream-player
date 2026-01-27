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
import io.github.thibaultbee.srtdroid.core.extensions.connect
import io.github.thibaultbee.srtdroid.core.models.SrtSocket
import io.github.thibaultbee.srtdroid.core.models.SrtUrl
import java.io.IOException
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * Media3 DataSource for SRT protocol using srtdroid (Haivision libsrt bindings).
 *
 * Accepts URIs like: srt://host:port?streamid=mystream&latency=200
 *
 * Works in caller mode (connects to an SRT listener/server).
 * Receives MPEG-TS packets (188 bytes each) packed into 1316-byte SRT payloads.
 */
@UnstableApi
class SrtDataSource : BaseDataSource(/* isNetwork = */ true) {

    companion object {
        private const val TAG = "SrtDataSource"
        private const val SRT_PAYLOAD_SIZE = 1316        // Standard SRT payload (7 * 188)
        private const val TS_PACKET_SIZE = 188            // MPEG-TS packet size
    }

    private val packetQueue = ConcurrentLinkedQueue<ByteArray>()
    private var socket: SrtSocket? = null
    private var currentUri: Uri? = null

    override fun open(dataSpec: DataSpec): Long {
        transferInitializing(dataSpec)

        val uri = dataSpec.uri
        currentUri = uri

        // Parse SRT URL — extracts host, port, and all SRT options from query params
        val srtUrl = SrtUrl(uri)

        Log.i(TAG, "Opening SRT connection to ${srtUrl.hostname}:${srtUrl.port}")

        try {
            val sock = SrtSocket()

            // Set defaults that SrtUrl may not specify
            sock.setSockFlag(SockOpt.TRANSTYPE, Transtype.LIVE)
            sock.setSockFlag(SockOpt.RCVSYN, true)

            // connect(SrtUrl) extension applies all URL-specified options
            // (latency, passphrase, streamid, etc.) before connecting
            sock.connect(srtUrl)

            socket = sock
            Log.i(TAG, "SRT connected successfully")
            transferStarted(dataSpec)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to open SRT connection: ${e.message}", e)
            throw IOException("SRT connection failed: ${e.message}", e)
        }

        return C.LENGTH_UNSET.toLong()
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0

        val sock = socket ?: throw IOException("SRT socket is not connected")

        try {
            // If queue is empty, receive a new SRT payload
            if (packetQueue.isEmpty()) {
                val received = sock.recv(SRT_PAYLOAD_SIZE)
                if (received.isEmpty()) {
                    return C.RESULT_END_OF_INPUT
                }

                // Split received data into TS packets and queue them
                val numPackets = received.size / TS_PACKET_SIZE
                for (i in 0 until numPackets) {
                    val tsPacket = received.copyOfRange(
                        i * TS_PACKET_SIZE,
                        (i + 1) * TS_PACKET_SIZE
                    )
                    packetQueue.offer(tsPacket)
                }
            }

            // Drain queued TS packets into the output buffer
            var bytesRead = 0
            while (bytesRead + TS_PACKET_SIZE <= length) {
                val packet = packetQueue.poll() ?: break
                System.arraycopy(packet, 0, buffer, offset + bytesRead, TS_PACKET_SIZE)
                bytesRead += TS_PACKET_SIZE
            }

            if (bytesRead > 0) {
                bytesTransferred(bytesRead)
            }
            return bytesRead

        } catch (e: Exception) {
            Log.e(TAG, "SRT read error: ${e.message}", e)
            throw IOException("SRT read failed: ${e.message}", e)
        }
    }

    override fun getUri(): Uri? = currentUri

    override fun close() {
        Log.i(TAG, "Closing SRT connection")
        packetQueue.clear()
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
     * Factory for creating SrtDataSource instances.
     */
    class Factory : DataSource.Factory {
        override fun createDataSource(): SrtDataSource = SrtDataSource()
    }
}
