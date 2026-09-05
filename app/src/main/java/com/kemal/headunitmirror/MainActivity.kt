package com.kemal.headunitmirror

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.media.MediaCodec
import android.media.MediaFormat
import android.os.Bundle
import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import java.io.DataInputStream
import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.ServerSocket
import java.nio.ByteBuffer
import java.util.Collections
import java.util.concurrent.atomic.AtomicBoolean

class MainActivity : AppCompatActivity(), SurfaceHolder.Callback {

    private lateinit var surfaceView: SurfaceView
    private lateinit var status: TextView
    private lateinit var ipText: TextView
    private lateinit var overlay: View

    private var receiver: Receiver? = null
    private var surface: Surface? = null

    private val port = 5555

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)

        setContentView(R.layout.activity_main)

        // Menjaga layar tetap menyala selama Headunit Mirror digunakan.
        window.addFlags(
            WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
        )

        surfaceView = findViewById(R.id.videoSurface)
        status = findViewById(R.id.statusText)
        ipText = findViewById(R.id.ipText)
        overlay = findViewById(R.id.overlay)

        surfaceView.holder.addCallback(this)

        ipText.text = "IP Headunit: ${localIp() ?: "-"}  | Port: $port"

        findViewById<Button>(R.id.restartButton).setOnClickListener {
            restart()
        }

        surfaceView.setOnClickListener {
            overlay.visibility =
                if (overlay.visibility == View.VISIBLE) {
                    View.GONE
                } else {
                    View.VISIBLE
                }
        }

        window.decorView.systemUiVisibility = 5894
    }

    override fun surfaceCreated(holder: SurfaceHolder) {
        surface = holder.surface
        restart()
    }

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        receiver?.stop()
        receiver = null
        surface = null
    }

    override fun surfaceChanged(
        holder: SurfaceHolder,
        format: Int,
        width: Int,
        height: Int
    ) {
        // Tidak diperlukan.
    }

    private fun restart() {
        val currentSurface = surface ?: return

        receiver?.stop()

        receiver = Receiver(
            port = port,
            surface = currentSurface
        ) { message ->
            runOnUiThread {
                status.text = message
            }
        }.also {
            it.start()
        }
    }

    private fun localIp(): String? {
        return try {
            Collections
                .list(NetworkInterface.getNetworkInterfaces())
                .flatMap {
                    Collections.list(it.inetAddresses)
                }
                .firstOrNull {
                    !it.isLoopbackAddress && it is Inet4Address
                }
                ?.hostAddress
        } catch (_: Exception) {
            null
        }
    }

    override fun onDestroy() {
        receiver?.stop()
        receiver = null
        super.onDestroy()
    }
}

private class Receiver(
    private val port: Int,
    private val surface: Surface,
    private val status: (String) -> Unit
) {

    private val running = AtomicBoolean(false)

    private var thread: Thread? = null
    private var server: ServerSocket? = null

    private var videoDecoder: MediaCodec? = null
    private var audioDecoder: MediaCodec? = null
    private var audioTrack: AudioTrack? = null

    private var sps: ByteArray? = null
    private var pps: ByteArray? = null
    private var asc: ByteArray? = null

    private var width = 1280
    private var height = 720

    private var videoPts = 0L
    private var audioPts = 0L

    fun start() {
        if (running.getAndSet(true)) {
            return
        }

        thread = Thread {

            try {
                server = ServerSocket(port)

                status("Siap • menunggu iPhone...")

                while (running.get()) {

                    val socket = server?.accept() ?: break

                    socket.tcpNoDelay = true

                    status("Terhubung • video + audio")

                    try {

                        DataInputStream(
                            socket.getInputStream()
                        ).use { input ->

                            while (running.get()) {

                                val type = input.readUnsignedByte()
                                val len = input.readInt()

                                if (len <= 0 || len > 8 * 1024 * 1024) {
                                    throw Exception("bad packet")
                                }

                                val packet = ByteArray(len)

                                input.readFully(packet)

                                when (type) {

                                    // SPS
                                    1 -> {
                                        sps = packet
                                        configureVideo()
                                    }

                                    // PPS
                                    2 -> {
                                        pps = packet
                                        configureVideo()
                                    }

                                    // Video
                                    3 -> {
                                        decodeVideo(packet)
                                    }

                                    // Audio configuration
                                    4 -> {
                                        asc = packet
                                        configureAudio()
                                    }

                                    // Video size
                                    5 -> {
                                        if (packet.size >= 8) {

                                            width = ByteBuffer
                                                .wrap(packet)
                                                .int

                                            height = ByteBuffer
                                                .wrap(
                                                    packet,
                                                    4,
                                                    4
                                                )
                                                .int

                                            releaseVideo()
                                            configureVideo()
                                        }
                                    }

                                    // Audio
                                    6 -> {
                                        decodeAudio(packet)
                                    }
                                }
                            }
                        }

                    } catch (_: Exception) {

                        if (running.get()) {
                            status(
                                "Koneksi putus • menunggu lagi..."
                            )
                        }

                    } finally {

                        try {
                            socket.close()
                        } catch (_: Exception) {
                        }

                        releaseVideo()
                        releaseAudio()

                        sps = null
                        pps = null
                        asc = null

                        videoPts = 0L
                        audioPts = 0L
                    }
                }

            } catch (e: Exception) {

                if (running.get()) {
                    status("Error: ${e.message}")
                }
            }

        }.apply {
            name = "MirrorReceiver"
            start()
        }
    }

    fun stop() {

        running.set(false)

        try {
            server?.close()
        } catch (_: Exception) {
        }

        server = null

        thread?.interrupt()
        thread = null

        releaseVideo()
        releaseAudio()
    }

    @Synchronized
    private fun configureVideo() {

        if (videoDecoder != null) {
            return
        }

        val currentSps = sps ?: return
        val currentPps = pps ?: return

        try {

            val format = MediaFormat.createVideoFormat(
                "video/avc",
                width,
                height
            )

            format.setByteBuffer(
                "csd-0",
                ByteBuffer.wrap(
                    byteArrayOf(
                        0,
                        0,
                        0,
                        1
                    ) + currentSps
                )
            )

            format.setByteBuffer(
                "csd-1",
                ByteBuffer.wrap(
                    byteArrayOf(
                        0,
                        0,
                        0,
                        1
                    ) + currentPps
                )
            )

            format.setInteger(
                MediaFormat.KEY_MAX_INPUT_SIZE,
                2 * 1024 * 1024
            )

            videoDecoder =
                MediaCodec.createDecoderByType(
                    "video/avc"
                ).apply {

                    configure(
                        format,
                        surface,
                        null,
                        0
                    )

                    start()
                }

        } catch (e: Exception) {

            status(
                "Video decoder: ${e.message}"
            )

            releaseVideo()
        }
    }

    @Synchronized
    private fun configureAudio() {

        if (audioDecoder != null) {
            return
        }

        val currentAsc = asc ?: return

        try {

            val format = MediaFormat.createAudioFormat(
                "audio/mp4a-latm",
                44100,
                2
            )

            format.setByteBuffer(
                "csd-0",
                ByteBuffer.wrap(currentAsc)
            )

            format.setInteger(
                MediaFormat.KEY_MAX_INPUT_SIZE,
                256 * 1024
            )

            audioDecoder =
                MediaCodec.createDecoderByType(
                    "audio/mp4a-latm"
                ).apply {

                    configure(
                        format,
                        null,
                        null,
                        0
                    )

                    start()
                }

            val minBufferSize =
                AudioTrack.getMinBufferSize(
                    44100,
                    AudioFormat.CHANNEL_OUT_STEREO,
                    AudioFormat.ENCODING_PCM_16BIT
                )

            audioTrack =
                AudioTrack.Builder()
                    .setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(
                                AudioAttributes.USAGE_MEDIA
                            )
                            .setContentType(
                                AudioAttributes.CONTENT_TYPE_MUSIC
                            )
                            .build()
                    )
                    .setAudioFormat(
                        AudioFormat.Builder()
                            .setSampleRate(44100)
                            .setEncoding(
                                AudioFormat.ENCODING_PCM_16BIT
                            )
                            .setChannelMask(
                                AudioFormat.CHANNEL_OUT_STEREO
                            )
                            .build()
                    )
                    .setBufferSizeInBytes(
                        maxOf(
                            minBufferSize,
                            32768
                        )
                    )
                    .build()

            audioTrack?.play()

        } catch (e: Exception) {

            status(
                "Audio decoder: ${e.message}"
            )

            releaseAudio()
        }
    }

    private fun decodeVideo(data: ByteArray) {

        val decoder = videoDecoder ?: return

        try {

            val inputIndex =
                decoder.dequeueInputBuffer(5000)

            if (inputIndex >= 0) {

                decoder
                    .getInputBuffer(inputIndex)
                    ?.apply {
                        clear()
                        put(data)
                    }

                decoder.queueInputBuffer(
                    inputIndex,
                    0,
                    data.size,
                    videoPts,
                    0
                )

                videoPts += 33333
            }

            val info = MediaCodec.BufferInfo()

            var outputIndex =
                decoder.dequeueOutputBuffer(
                    info,
                    0
                )

            while (outputIndex >= 0) {

                decoder.releaseOutputBuffer(
                    outputIndex,
                    true
                )

                outputIndex =
                    decoder.dequeueOutputBuffer(
                        info,
                        0
                    )
            }

        } catch (_: Exception) {
        }
    }

    private fun decodeAudio(data: ByteArray) {

        val decoder = audioDecoder ?: return

        try {

            val inputIndex =
                decoder.dequeueInputBuffer(5000)

            if (inputIndex >= 0) {

                decoder
                    .getInputBuffer(inputIndex)
                    ?.apply {
                        clear()
                        put(data)
                    }

                decoder.queueInputBuffer(
                    inputIndex,
                    0,
                    data.size,
                    audioPts,
                    0
                )

                audioPts += 23220
            }

            val info = MediaCodec.BufferInfo()

            var outputIndex =
                decoder.dequeueOutputBuffer(
                    info,
                    0
                )

            while (outputIndex >= 0) {

                val buffer =
                    decoder.getOutputBuffer(
                        outputIndex
                    )

                if (
                    buffer != null &&
                    info.size > 0
                ) {

                    buffer.position(info.offset)

                    val pcm =
                        ByteArray(info.size)

                    buffer.get(pcm)

                    audioTrack?.write(
                        pcm,
                        0,
                        pcm.size
                    )
                }

                decoder.releaseOutputBuffer(
                    outputIndex,
                    false
                )

                outputIndex =
                    decoder.dequeueOutputBuffer(
                        info,
                        0
                    )
            }

        } catch (_: Exception) {
        }
    }

    @Synchronized
    private fun releaseVideo() {

        try {
            videoDecoder?.stop()
        } catch (_: Exception) {
        }

        try {
            videoDecoder?.release()
        } catch (_: Exception) {
        }

        videoDecoder = null
    }

    @Synchronized
    private fun releaseAudio() {

        try {
            audioDecoder?.stop()
        } catch (_: Exception) {
        }

        try {
            audioDecoder?.release()
        } catch (_: Exception) {
        }

        audioDecoder = null

        try {
            audioTrack?.stop()
        } catch (_: Exception) {
        }

        try {
            audioTrack?.release()
        } catch (_: Exception) {
        }

        audioTrack = null
    }
}
