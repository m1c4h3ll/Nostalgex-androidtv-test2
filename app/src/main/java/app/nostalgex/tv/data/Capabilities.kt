package app.nostalgex.tv.data

import android.content.Context
import android.hardware.display.DisplayManager
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.view.Display
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.audio.AudioCapabilities
import app.nostalgex.engine.server.DeviceCaps

/** What this TV decodes, from its codec list and its HDMI output. */
object Capabilities {

    @OptIn(UnstableApi::class)
    fun detect(context: Context): DeviceCaps {
        val decoders: List<MediaCodecInfo> = try {
            MediaCodecList(MediaCodecList.ALL_CODECS).codecInfos.filter { !it.isEncoder }
        } catch (_: Exception) {
            emptyList()
        }
        fun has(mime: String) = decoders.any { info -> info.supportedTypes.any { it.equals(mime, ignoreCase = true) } }

        fun hevcMain10(): Boolean = decoders.any { info ->
            info.supportedTypes.any { it.equals("video/hevc", true) } && try {
                info.getCapabilitiesForType("video/hevc").profileLevels.any {
                    it.profile == MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10 ||
                        it.profile == MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10HDR10
                }
            } catch (_: Exception) {
                false
            }
        }

        val passthrough = try {
            @Suppress("DEPRECATION")
            AudioCapabilities.getCapabilities(context)
        } catch (_: Exception) {
            null
        }
        fun pass(encoding: Int) = passthrough?.supportsEncoding(encoding) == true

        return DeviceCaps(
            hevc = has("video/hevc"),
            hevc10 = hevcMain10(),
            av1 = has("video/av01"),
            vp9 = has("video/x-vnd.on2.vp9"),
            mpeg2 = has("video/mpeg2"),
            ac3 = has("audio/ac3") || pass(C.ENCODING_AC3),
            eac3 = has("audio/eac3") || pass(C.ENCODING_E_AC3),
            dts = has("audio/vnd.dts") || pass(C.ENCODING_DTS),
            truehd = has("audio/true-hd") || pass(C.ENCODING_DOLBY_TRUEHD),
            maxWidth = outputWidth(context),
        )
    }

    /** 3840 on a 4K output, 1920 on 1080p, 1280 below: the width a server stream asks for. */
    private fun outputWidth(context: Context): Int {
        val dm = context.getSystemService(Context.DISPLAY_SERVICE) as? DisplayManager ?: return 1920
        val display = dm.getDisplay(Display.DEFAULT_DISPLAY) ?: return 1920
        // The current output mode, not the UI's size (often 1080p on a 4K TV).
        val mode = display.mode
        val widest = maxOf(mode.physicalWidth, mode.physicalHeight)
        return when {
            widest >= 3840 -> 3840
            widest >= 1920 -> 1920
            else -> 1280
        }
    }
}
