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
            val outputPath = videoPath + "_merged.mp4"
            val videoExtractor = MediaExtractor()
            videoExtractor.setDataSource(videoPath)

            val audioExtractor = MediaExtractor()
            audioExtractor.setDataSource(audioPath)

            val muxer = MediaMuxer(outputPath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)

            var videoTrackIndex = -1
            for (i in 0 until videoExtractor.trackCount) {
                val format = videoExtractor.getTrackFormat(i)
                if (format.getString(MediaFormat.KEY_MIME)?.startsWith("video/") == true) {
                    videoTrackIndex = muxer.addTrack(format)
                    videoExtractor.selectTrack(i)
                    break
                }
            }

            var audioTrackIndex = -1
            for (i in 0 until audioExtractor.trackCount) {
                val format = audioExtractor.getTrackFormat(i)
                if (format.getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true) {
                    audioTrackIndex = muxer.addTrack(format)
                    audioExtractor.selectTrack(i)
                    break
                }
            }

            if (videoTrackIndex == -1 || audioTrackIndex == -1) {
                promise.reject("MERGE_ERROR", "Video or Audio track not found")
                return
            }

            muxer.start()

            val buffer = ByteArray(1024 * 1024)
            val bufferInfo = MediaCodec.BufferInfo()

            // Write Video
            while (true) {
                bufferInfo.offset = 0
                bufferInfo.size = videoExtractor.readSampleData(ByteBuffer.wrap(buffer), 0)
                if (bufferInfo.size < 0) break
                bufferInfo.presentationTimeUs = videoExtractor.sampleTime
                bufferInfo.flags = videoExtractor.sampleFlags
                muxer.writeSampleData(videoTrackIndex, ByteBuffer.wrap(buffer, 0, bufferInfo.size), bufferInfo)
                videoExtractor.advance()
            }

            // Write Audio
            while (true) {
                bufferInfo.offset = 0
                bufferInfo.size = audioExtractor.readSampleData(ByteBuffer.wrap(buffer), 0)
                if (bufferInfo.size < 0) break
                bufferInfo.presentationTimeUs = audioExtractor.sampleTime
                bufferInfo.flags = audioExtractor.sampleFlags
                muxer.writeSampleData(audioTrackIndex, ByteBuffer.wrap(buffer, 0, bufferInfo.size), bufferInfo)
                audioExtractor.advance()
            }

            muxer.stop()
            muxer.release()
            videoExtractor.release()
            audioExtractor.release()

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
            var muxer: MediaMuxer? = null
            try {
                val inPath = mp3Path.removePrefix("file://")
                val outPath = inPath.replace(".mp3", "_aac.m4a")

                /* --- Extractor & decoder (MP3 → PCM) --- */
                extractor = MediaExtractor()
                extractor.setDataSource(inPath)

                var audioTrack = -1
                for (i in 0 until extractor.trackCount) {
                    val f = extractor.getTrackFormat(i)
                    if (f.getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true) {
                        audioTrack = i
                        break
                    }
                }
                if (audioTrack == -1) throw Exception("No audio track in MP3")

                extractor.selectTrack(audioTrack)
                val inputFormat = extractor.getTrackFormat(audioTrack)
                val mimeIn = inputFormat.getString(MediaFormat.KEY_MIME) ?: "audio/mpeg"

                decoder = MediaCodec.createDecoderByType(mimeIn)
                decoder!!.configure(inputFormat, null, null, 0)
                decoder!!.start()

                /* --- Encoder (PCM → AAC) --- */
                val AAC_MIME = MediaFormat.MIMETYPE_AUDIO_AAC
                val aacFormat = MediaFormat.createAudioFormat(AAC_MIME, 44100, 2)
                aacFormat.setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
                aacFormat.setInteger(MediaFormat.KEY_BIT_RATE, 128_000)
                encoder = MediaCodec.createEncoderByType(AAC_MIME)
                encoder!!.configure(aacFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                encoder!!.start()

                /* --- Muxer --- */
                muxer = MediaMuxer(outPath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
                var muxerStarted = false
                var aacTrackIndex = -1

                val bufferInfo = MediaCodec.BufferInfo()
                val TIMEOUT_US = 10_000L
                var sawInputEOS = false
                var sawDecoderEOS = false
                var sawEncoderEOS = false

                while (!sawEncoderEOS) {
                    /* ---------- Feed decoder with MP3 data ---------- */
                    if (!sawInputEOS) {
                        val inIndex = decoder!!.dequeueInputBuffer(TIMEOUT_US)
                        if (inIndex >= 0) {
                            val buf = decoder!!.getInputBuffer(inIndex)!!
                            val sampleSize = extractor.readSampleData(buf, 0)
                            if (sampleSize < 0) {
                                decoder!!.queueInputBuffer(
                                    inIndex,
                                    0,
                                    0,
                                    0,
                                    MediaCodec.BUFFER_FLAG_END_OF_STREAM
                                )
                                sawInputEOS = true
                            } else {
                                val presentationTimeUs = extractor.sampleTime
                                decoder!!.queueInputBuffer(
                                    inIndex,
                                    0,
                                    sampleSize,
                                    presentationTimeUs,
                                    0
                                )
                                extractor.advance()
                            }
                        }
                    }

                    /* ---------- Drain decoder (PCM) ---------- */
                    var decoderOutAvailable = true
                    while (decoderOutAvailable && !sawDecoderEOS) {
                        val outIndex = decoder!!.dequeueOutputBuffer(bufferInfo, TIMEOUT_US)
                        when {
                            outIndex == MediaCodec.INFO_TRY_AGAIN_LATER -> decoderOutAvailable = false
                            outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> { /* ignore */ }
                            outIndex >= 0 -> {
                                val pcmBuf = decoder!!.getOutputBuffer(outIndex)!!
                                val pcmSize = bufferInfo.size
                                val pcmPts = bufferInfo.presentationTimeUs
                                val flags = bufferInfo.flags

                                /* ---------- Feed encoder with PCM ---------- */
                                val inEnc = encoder!!.dequeueInputBuffer(TIMEOUT_US)
                                if (inEnc >= 0) {
                                    val encBuf = encoder!!.getInputBuffer(inEnc)!!
                                    encBuf.clear()
                                    encBuf.put(pcmBuf)
                                    encoder!!.queueInputBuffer(
                                        inEnc, 0, pcmSize, pcmPts,
                                        if (flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0)
                                            MediaCodec.BUFFER_FLAG_END_OF_STREAM
                                        else 0
                                    )
                                }

                                decoder!!.releaseOutputBuffer(outIndex, false)
                                if (flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                                    sawDecoderEOS = true
                                }
                            }
                        }
                    }

                    /* ---------- Drain encoder (AAC) ---------- */
                    var encoderOutAvailable = true
                    while (encoderOutAvailable && !sawEncoderEOS) {
                        val encIndex = encoder!!.dequeueOutputBuffer(bufferInfo, TIMEOUT_US)
                        when {
                            encIndex == MediaCodec.INFO_TRY_AGAIN_LATER -> encoderOutAvailable = false
                            encIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                                if (muxerStarted) throw RuntimeException("Format changed twice")
                                val newFormat = encoder!!.outputFormat
                                aacTrackIndex = muxer.addTrack(newFormat)
                                muxer.start()
                                muxerStarted = true
                            }
                            encIndex >= 0 -> {
                                val encodedBuf = encoder!!.getOutputBuffer(encIndex)!!
                                if (bufferInfo.size > 0 && muxerStarted) {
                                    encodedBuf.position(bufferInfo.offset)
                                    encodedBuf.limit(bufferInfo.offset + bufferInfo.size)
                                    muxer.writeSampleData(aacTrackIndex, encodedBuf, bufferInfo)
                                }
                                val flags = bufferInfo.flags
                                encoder!!.releaseOutputBuffer(encIndex, false)
                                if (flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                                    sawEncoderEOS = true
                                }
                            }
                        }
                    }
                }

                /* --- Release --- */
                extractor.release()
                decoder.stop(); decoder.release()
                encoder.stop(); encoder.release()
                if (muxerStarted) {
                    muxer.stop()
                }
                muxer.release()

                promise.resolve("file://$outPath")
            } catch (e: Exception) {
                promise.reject("CONVERT_ERROR", e.message, e)
                try { extractor?.release() } catch (_: Exception) {}
                try { decoder?.stop(); decoder?.release() } catch (_: Exception) {}
                try { encoder?.stop(); encoder?.release() } catch (_: Exception) {}
                try { muxer?.release() } catch (_: Exception) {}
            }
        }.start()
    }
}
