package com.lettemin

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Decodes any Android-supported audio Uri (mp3/aac/ogg/m4a/wav/etc.) into a
 * 44.1 kHz, 16-bit signed, mono PCM WAV byte array — the format AudioPlaySdWav
 * on the Teensy plays back natively without resampling.
 */
object WavConverter {

    private const val TARGET_RATE = 44100
    private const val DECODE_TIMEOUT_US = 10_000L

    fun convert(ctx: Context, input: Uri): ByteArray {
        val extractor = MediaExtractor()
        ctx.contentResolver.openFileDescriptor(input, "r").use { pfd ->
            requireNotNull(pfd) { "cannot open audio uri" }
            extractor.setDataSource(pfd.fileDescriptor)
        }

        val (track, srcFormat) = findAudioTrack(extractor)
            ?: throw IllegalArgumentException("no audio track in input")
        extractor.selectTrack(track)

        val srcRate = srcFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
        val srcChannels = srcFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
        val mime = srcFormat.getString(MediaFormat.KEY_MIME)!!

        val pcm = decodePcm(extractor, mime, srcFormat)
        extractor.release()

        // Output of MediaCodec on Android is 16-bit signed PCM little-endian.
        val samples = ShortArray(pcm.size / 2)
        ByteBuffer.wrap(pcm).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(samples)

        val mono = if (srcChannels == 1) samples else downmixToMono(samples, srcChannels)
        val resampled = if (srcRate == TARGET_RATE) mono else linearResample(mono, srcRate, TARGET_RATE)

        return encodeWav(resampled, TARGET_RATE)
    }

    private fun findAudioTrack(extractor: MediaExtractor): Pair<Int, MediaFormat>? {
        for (i in 0 until extractor.trackCount) {
            val f = extractor.getTrackFormat(i)
            val mime = f.getString(MediaFormat.KEY_MIME) ?: continue
            if (mime.startsWith("audio/")) return i to f
        }
        return null
    }

    private fun decodePcm(
        extractor: MediaExtractor,
        mime: String,
        srcFormat: MediaFormat
    ): ByteArray {
        val codec = MediaCodec.createDecoderByType(mime)
        codec.configure(srcFormat, null, null, 0)
        codec.start()

        val out = ByteArrayOutputStream()
        val info = MediaCodec.BufferInfo()
        var inputDone = false
        var eos = false

        while (!eos) {
            if (!inputDone) {
                val inIdx = codec.dequeueInputBuffer(DECODE_TIMEOUT_US)
                if (inIdx >= 0) {
                    val buf = codec.getInputBuffer(inIdx)!!
                    buf.clear()
                    val sz = extractor.readSampleData(buf, 0)
                    if (sz < 0) {
                        codec.queueInputBuffer(inIdx, 0, 0, 0,
                            MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                        inputDone = true
                    } else {
                        codec.queueInputBuffer(inIdx, 0, sz, extractor.sampleTime, 0)
                        extractor.advance()
                    }
                }
            }
            val outIdx = codec.dequeueOutputBuffer(info, DECODE_TIMEOUT_US)
            if (outIdx >= 0) {
                if (info.size > 0) {
                    val outBuf = codec.getOutputBuffer(outIdx)!!
                    val chunk = ByteArray(info.size)
                    outBuf.position(info.offset)
                    outBuf.get(chunk, 0, info.size)
                    out.write(chunk)
                }
                codec.releaseOutputBuffer(outIdx, false)
                if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) eos = true
            }
        }
        codec.stop()
        codec.release()
        return out.toByteArray()
    }

    private fun downmixToMono(src: ShortArray, channels: Int): ShortArray {
        val frames = src.size / channels
        return ShortArray(frames) { i ->
            var sum = 0
            for (c in 0 until channels) sum += src[i * channels + c]
            (sum / channels).toShort()
        }
    }

    private fun linearResample(src: ShortArray, srcRate: Int, dstRate: Int): ShortArray {
        val ratio = srcRate.toDouble() / dstRate
        val outLen = (src.size / ratio).toInt()
        return ShortArray(outLen) { i ->
            val pos = i * ratio
            val i0 = pos.toInt().coerceIn(0, src.size - 1)
            val i1 = (i0 + 1).coerceIn(0, src.size - 1)
            val frac = pos - i0
            (src[i0] * (1 - frac) + src[i1] * frac).toInt().toShort()
        }
    }

    private fun encodeWav(samples: ShortArray, sampleRate: Int): ByteArray {
        val dataSize = samples.size * 2  // 16-bit mono
        val out = ByteArrayOutputStream(44 + dataSize)
        val bb = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)

        // RIFF header
        bb.put("RIFF".toByteArray())
        bb.putInt(36 + dataSize)
        bb.put("WAVE".toByteArray())
        // fmt sub-chunk
        bb.put("fmt ".toByteArray())
        bb.putInt(16)                            // sub-chunk size
        bb.putShort(1)                           // PCM
        bb.putShort(1)                           // mono
        bb.putInt(sampleRate)
        bb.putInt(sampleRate * 2)                // byte rate (mono * 2 bytes)
        bb.putShort(2)                           // block align
        bb.putShort(16)                          // bits per sample
        // data sub-chunk
        bb.put("data".toByteArray())
        bb.putInt(dataSize)
        out.write(bb.array())

        val pcmBuf = ByteBuffer.allocate(dataSize).order(ByteOrder.LITTLE_ENDIAN)
        for (s in samples) pcmBuf.putShort(s)
        out.write(pcmBuf.array())
        return out.toByteArray()
    }
}
