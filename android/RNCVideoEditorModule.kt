package com.soundsnap

import android.media.*
import android.util.Log
import com.facebook.react.bridge.*
import java.io.File
import java.nio.ByteBuffer
import kotlin.math.min

class RNCVideoEditorModule(reactContext: ReactApplicationContext) : ReactContextBaseJavaModule(reactContext) {
    override fun getName() = "RNCVideoEditor"

    /* -------------------- EXISTING METHODS (UNCHANGED) -------------------- */

    @ReactMethod
    fun separateAudioVideo(videoPath: String, promise: Promise) {
        try {
            val audioOutputPath = videoPath + "_extracted.m4a"
            val extractor = MediaExtractor()
            extractor.setDataSource(videoPath)

            var audioTrackIndex = -1
            for (i in 0 until extractor.trackCount) {
                val format = extractor.getTrackFormat(i)
                val mime = format.getString(MediaFormat.KEY_MIME)
                if (mime?.startsWith("audio/") == true) {
                    audioTrackIndex = i
                    break
                }
            }

            if (audioTrackIndex == -1) {
                promise.reject("NO_AUDIO_TRACK", "No audio track found")
                return
            }

            extractor.selectTrack(audioTrackIndex)
            val muxer = MediaMuxer(audioOutputPath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            val newTrackIndex = muxer.addTrack(extractor.getTrackFormat(audioTrackIndex))
            muxer.start()

            val buffer = ByteArray(1024 * 1024)
            val bufferInfo = MediaCodec.BufferInfo()
            extractor.seekTo(0, MediaExtractor.SEEK_TO_CLOSEST_SYNC)

            while (true) {
                bufferInfo.offset = 0
                bufferInfo.size = extractor.readSampleData(ByteBuffer.wrap(buffer), 0)
                if (bufferInfo.size < 0) break
                bufferInfo.presentationTimeUs = extractor.sampleTime
                bufferInfo.flags = extractor.sampleFlags
                muxer.writeSampleData(newTrackIndex, ByteBuffer.wrap(buffer, 0, bufferInfo.size), bufferInfo)
                extractor.advance()
            }

            muxer.stop()
            muxer.release()
            extractor.release()

            promise.resolve(audioOutputPath)

        } catch (e: Exception) {
            promise.reject("SEPARATE_ERROR", e.message, e)
        }
    }

    @ReactMethod
    fun mergeAudioWithVideo(videoPath: String, audioPath: String, promise: Promise) {
        try {
            val outputPath = "${videoPath}_merged.mp4"

            /* ---------- Extractors ---------- */
            val vEx = MediaExtractor().apply { setDataSource(videoPath) }
            val aEx = MediaExtractor().apply { setDataSource(audioPath) }

            /* ---------- Muxer ---------- */
            val muxer = MediaMuxer(outputPath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)

            /* ---- 1. Video track + rotation ---- */
            var vTrackIx = -1
            var rotation = 0
            for (i in 0 until vEx.trackCount) {
                val fmt = vEx.getTrackFormat(i)
                if (fmt.getString(MediaFormat.KEY_MIME)!!.startsWith("video/")) {
                    vTrackIx = muxer.addTrack(fmt); vEx.selectTrack(i)
                    if (fmt.containsKey(MediaFormat.KEY_ROTATION)) {
                        rotation = fmt.getInteger(MediaFormat.KEY_ROTATION)
                    } else {        // bazı cihazlar rotation’ı burada tutmaz
                        // yedek yol: MetadataRetriever
                        val retr = MediaMetadataRetriever()
                        retr.setDataSource(videoPath)
                        rotation = retr.extractMetadata(
                            MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION
                        )?.toInt() ?: 0
                        retr.release()
                    }
                    break
                }
            }

            /* ---- 2. Audio track ---- */
            var aTrackIx = -1
            for (i in 0 until aEx.trackCount) {
                val fmt = aEx.getTrackFormat(i)
                if (fmt.getString(MediaFormat.KEY_MIME)!!.startsWith("audio/")) {
                    aTrackIx = muxer.addTrack(fmt); aEx.selectTrack(i); break
                }
            }
            if (vTrackIx == -1 || aTrackIx == -1) {
                promise.reject("MERGE_ERROR", "Tracks not found"); return
            }

            /* ---- Rotation metasını ekle ---- */
            muxer.setOrientationHint(rotation)   // 🔑 DÖNÜŞ BİLGİSİ EKLENDİ
            muxer.start()

            val buf = ByteArray(1 shl 20)
            val info = MediaCodec.BufferInfo()

            fun copy(ex: MediaExtractor, trackIx: Int) {
                while (true) {
                    info.size = ex.readSampleData(ByteBuffer.wrap(buf), 0)
                    if (info.size < 0) break
                    info.offset = 0
                    info.presentationTimeUs = ex.sampleTime
                    info.flags = ex.sampleFlags
                    muxer.writeSampleData(trackIx, ByteBuffer.wrap(buf, 0, info.size), info)
                    ex.advance()
                }
            }

            copy(vEx, vTrackIx); copy(aEx, aTrackIx)

            muxer.stop(); muxer.release(); vEx.release(); aEx.release()
            promise.resolve(outputPath)

        } catch (e: Exception) {
            promise.reject("MERGE_ERROR", e.message, e)
        }
    }


    /* -------------------- NEW METHOD: MP3 ➜ AAC/M4A -------------------- */

    /**
     * convertMp3ToM4a
     *  - mp3Path  : "file:///..." veya "/storage/..." olabilir
     *  - return   : Promise.resolve("file:///..._aac.m4a")
     *
     *  Yalnızca Android’de kullanılır. AAC (LC) 44.1 kHz / 2 ch / 128 kbps
     *  çıktılı bir M4A dosyası üretir.
     */
     @ReactMethod
fun convertMp3ToM4a(mp3Path: String, promise: Promise) {
    Thread {
        var extractor: MediaExtractor? = null
        var decoder: MediaCodec? = null
        var encoder: MediaCodec? = null
        var muxer:   MediaMuxer? = null
        try {
            val inPath  = mp3Path.removePrefix("file://")
            val outPath = inPath.replace(".mp3", "_aac.m4a")

            /* 1. MP3 track alınır -------------------------------------------------- */
            extractor = MediaExtractor().apply { setDataSource(inPath) }

            val trackIx = (0 until extractor!!.trackCount).first {
                extractor!!.getTrackFormat(it)
                    .getString(MediaFormat.KEY_MIME)!!.startsWith("audio/")
            }
            extractor!!.selectTrack(trackIx)

            val inFmt = extractor!!.getTrackFormat(trackIx)
            val sampleRate   = inFmt.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            val channelCount = inFmt.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            val mimeIn       = inFmt.getString(MediaFormat.KEY_MIME)!!

            /* 2. Decoder ----------------------------------------------------------- */
            decoder = MediaCodec.createDecoderByType(mimeIn).apply {
                configure(inFmt, null, null, 0)
                start()
            }

            /* 3. Encoder (AAC) ------------------------------------------------------ */
            val aacFmt = MediaFormat.createAudioFormat(
                MediaFormat.MIMETYPE_AUDIO_AAC, sampleRate, channelCount
            ).apply {
                setInteger(
                    MediaFormat.KEY_AAC_PROFILE,
                    MediaCodecInfo.CodecProfileLevel.AACObjectLC
                )
                setInteger(MediaFormat.KEY_BIT_RATE, 128000)
            }
            encoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC).apply {
                configure(aacFmt, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                start()
            }

            /* 4. Muxer ------------------------------------------------------------- */
            muxer = MediaMuxer(outPath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            var muxStarted = false
            var aacTrack   = -1

            val info    = MediaCodec.BufferInfo()
            val TIMEOUT = 10_000L
            var inEOS = false
            var decEOS = false
            var encEOS = false

            while (!encEOS) {

                /* feed decoder --------------------------------------------------- */
                if (!inEOS) {
                    val inIdx = decoder!!.dequeueInputBuffer(TIMEOUT)
                    if (inIdx >= 0) {
                        val inBuf = decoder!!.getInputBuffer(inIdx)!!
                        val sz = extractor!!.readSampleData(inBuf, 0)
                        if (sz < 0) {
                            decoder!!.queueInputBuffer(
                                inIdx, 0, 0, 0,
                                MediaCodec.BUFFER_FLAG_END_OF_STREAM
                            )
                            inEOS = true
                        } else {
                            decoder!!.queueInputBuffer(
                                inIdx, 0, sz, extractor!!.sampleTime, 0
                            )
                            extractor!!.advance()
                        }
                    }
                }

                /* decoder → encoder (chunk-safe) --------------------------------- */
                var decOut = decoder!!.dequeueOutputBuffer(info, TIMEOUT)
                while (decOut >= 0) {
                    val pcm = decoder!!.getOutputBuffer(decOut)!!
                    val endFlag = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM

                    while (pcm.hasRemaining()) {
                        val inEnc = encoder!!.dequeueInputBuffer(TIMEOUT)
                        if (inEnc < 0) break
                        val encBuf = encoder!!.getInputBuffer(inEnc)!!
                        encBuf.clear()

                        val chunk = ByteArray(min(encBuf.remaining(), pcm.remaining()))
                        pcm.get(chunk)
                        encBuf.put(chunk)

                        encoder!!.queueInputBuffer(
                            inEnc, 0, chunk.size, info.presentationTimeUs,
                            if (!pcm.hasRemaining() && endFlag != 0)
                                MediaCodec.BUFFER_FLAG_END_OF_STREAM else 0
                        )
                    }
                    decoder!!.releaseOutputBuffer(decOut, false)
                    if (endFlag != 0) decEOS = true
                    decOut = decoder!!.dequeueOutputBuffer(info, 0)
                }

                /* encoder → muxer -------------------------------------------------- */
                var encOut = encoder!!.dequeueOutputBuffer(info, TIMEOUT)
                while (encOut >= 0) {
                    if (!muxStarted) {
                        aacTrack = muxer!!.addTrack(encoder!!.outputFormat)
                        muxer!!.start(); muxStarted = true
                    }
                    if (info.size > 0) {
                        val aBuf = encoder!!.getOutputBuffer(encOut)!!
                        muxer!!.writeSampleData(aacTrack, aBuf, info)
                    }
                    val eEnd = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM
                    encoder!!.releaseOutputBuffer(encOut, false)
                    if (eEnd != 0) encEOS = true
                    encOut = encoder!!.dequeueOutputBuffer(info, 0)
                }
            }

            decoder!!.stop(); decoder!!.release()
            encoder!!.stop(); encoder!!.release()
            if (muxStarted) muxer!!.stop()
            muxer!!.release(); extractor!!.release()

            promise.resolve("file://$outPath")

        } catch (e: Exception) {
            promise.reject("CONVERT_ERROR", e.message, e)
            try { extractor?.release() }      catch(_:Exception){}
            try { decoder?.stop(); decoder?.release() } catch(_:Exception){}
            try { encoder?.stop(); encoder?.release() } catch(_:Exception){}
            try { muxer?.release() }          catch(_:Exception){}
        }
    }.start()
}

}