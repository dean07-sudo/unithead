package com.kemal.headunitmirror

import android.media.*
import android.os.Bundle
import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import java.io.DataInputStream
import java.net.*
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
        super.onCreate(b); setContentView(R.layout.activity_main)
        surfaceView=findViewById(R.id.videoSurface); status=findViewById(R.id.statusText)
        ipText=findViewById(R.id.ipText); overlay=findViewById(R.id.overlay)
        surfaceView.holder.addCallback(this)
        ipText.text="IP Headunit: ${localIp() ?: "-"}  | Port: $port"
        findViewById<Button>(R.id.restartButton).setOnClickListener { restart() }
        surfaceView.setOnClickListener {
            overlay.visibility=if(overlay.visibility==View.VISIBLE) View.GONE else View.VISIBLE
        }
        window.decorView.systemUiVisibility=5894
    }
    override fun surfaceCreated(h: SurfaceHolder) { surface=h.surface; restart() }
    override fun surfaceDestroyed(h: SurfaceHolder) { receiver?.stop(); receiver=null; surface=null }
    override fun surfaceChanged(h: SurfaceHolder,f:Int,w:Int,hh:Int) {}
    private fun restart() {
        val s=surface ?: return
        receiver?.stop()
        receiver=Receiver(port,s){ runOnUiThread { status.text=it } }.also { it.start() }
    }
    private fun localIp(): String?=try {
        Collections.list(NetworkInterface.getNetworkInterfaces()).flatMap{Collections.list(it.inetAddresses)}
            .firstOrNull{!it.isLoopbackAddress && it is Inet4Address}?.hostAddress
    } catch(_:Exception){null}
}

private class Receiver(
    private val port:Int, private val surface:Surface, private val status:(String)->Unit
) {
    private val running=AtomicBoolean(false); private var thread:Thread?=null
    private var server:ServerSocket?=null
    private var v:MediaCodec?=null; private var a:MediaCodec?=null; private var track:AudioTrack?=null
    private var sps:ByteArray?=null; private var pps:ByteArray?=null; private var asc:ByteArray?=null
    private var width=1280; private var height=720
    private var vpts=0L; private var apts=0L

    fun start() {
        if(running.getAndSet(true)) return
        thread=Thread {
            try {
                server=ServerSocket(port); status("Siap • menunggu iPhone...")
                while(running.get()) {
                    val socket=server!!.accept(); socket.tcpNoDelay=true
                    status("Terhubung • video + audio")
                    try {
                        DataInputStream(socket.getInputStream()).use { input ->
                            while(running.get()) {
                                val type=input.readUnsignedByte(); val len=input.readInt()
                                if(len<=0 || len>8*1024*1024) throw Exception("bad packet")
                                val p=ByteArray(len); input.readFully(p)
                                when(type) {
                                    1->{sps=p; configureVideo()}
                                    2->{pps=p; configureVideo()}
                                    3->decodeVideo(p)
                                    4->{asc=p; configureAudio()}
                                    5->if(p.size>=8){width=ByteBuffer.wrap(p).int;height=ByteBuffer.wrap(p,4,4).int;configureVideo()}
                                    6->decodeAudio(p)
                                }
                            }
                        }
                    } catch(_:Exception) { status("Koneksi putus • menunggu lagi...") }
                    finally { try{socket.close()}catch(_:Exception){}; releaseVideo();releaseAudio()
                        sps=null;pps=null;asc=null }
                }
            } catch(e:Exception) { if(running.get()) status("Error: ${e.message}") }
        }.apply { name="MirrorReceiver";start() }
    }
    fun stop(){running.set(false);try{server?.close()}catch(_:Exception){};thread?.interrupt();releaseVideo();releaseAudio()}
    @Synchronized private fun configureVideo(){
        if(v!=null || sps==null || pps==null)return
        try{
            val f=MediaFormat.createVideoFormat("video/avc",width,height)
            f.setByteBuffer("csd-0",ByteBuffer.wrap(byteArrayOf(0,0,0,1)+sps!!))
            f.setByteBuffer("csd-1",ByteBuffer.wrap(byteArrayOf(0,0,0,1)+pps!!))
            f.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE,2*1024*1024)
            v=MediaCodec.createDecoderByType("video/avc").apply{configure(f,surface,null,0);start()}
        }catch(e:Exception){status("Video decoder: ${e.message}");releaseVideo()}
    }
    @Synchronized private fun configureAudio(){
        if(a!=null || asc==null)return
        try{
            val f=MediaFormat.createAudioFormat("audio/mp4a-latm",44100,2)
            f.setByteBuffer("csd-0",ByteBuffer.wrap(asc!!));f.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE,256*1024)
            a=MediaCodec.createDecoderByType("audio/mp4a-latm").apply{configure(f,null,null,0);start()}
            val min=AudioTrack.getMinBufferSize(44100,AudioFormat.CHANNEL_OUT_STEREO,AudioFormat.ENCODING_PCM_16BIT)
            track=AudioTrack.Builder().setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
                .setAudioFormat(AudioFormat.Builder().setSampleRate(44100).setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO).build()).setBufferSizeInBytes(maxOf(min,32768)).build()
            track?.play()
        }catch(e:Exception){status("Audio decoder: ${e.message}");releaseAudio()}
    }
    private fun decodeVideo(data:ByteArray){
        val c=v?:return;try{
            val i=c.dequeueInputBuffer(5000);if(i>=0){c.getInputBuffer(i)?.apply{clear();put(data)};c.queueInputBuffer(i,0,data.size,vpts,0);vpts+=33333}
            val info=MediaCodec.BufferInfo();var o=c.dequeueOutputBuffer(info,0)
            while(o>=0){c.releaseOutputBuffer(o,true);o=c.dequeueOutputBuffer(info,0)}
        }catch(_:Exception){}
    }
    private fun decodeAudio(data:ByteArray){
        val c=a?:return;try{
            val i=c.dequeueInputBuffer(5000);if(i>=0){c.getInputBuffer(i)?.apply{clear();put(data)};c.queueInputBuffer(i,0,data.size,apts,0);apts+=23220}
            val info=MediaCodec.BufferInfo();var o=c.dequeueOutputBuffer(info,0)
            while(o>=0){
                val b=c.getOutputBuffer(o);if(b!=null && info.size>0){b.position(info.offset);val pcm=ByteArray(info.size);b.get(pcm);track?.write(pcm,0,pcm.size)}
                c.releaseOutputBuffer(o,false);o=c.dequeueOutputBuffer(info,0)
            }
        }catch(_:Exception){}
    }
    @Synchronized private fun releaseVideo(){try{v?.stop()}catch(_:Exception){};try{v?.release()}catch(_:Exception){};v=null}
    @Synchronized private fun releaseAudio(){try{a?.stop()}catch(_:Exception){};try{a?.release()}catch(_:Exception){};a=null
        try{track?.stop()}catch(_:Exception){};try{track?.release()}catch(_:Exception){};track=null}
}
