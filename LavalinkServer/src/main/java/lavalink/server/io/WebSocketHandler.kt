package lavalink.server.io

import com.fasterxml.jackson.databind.ObjectMapper
import com.sedmelluq.discord.lavaplayer.track.TrackMarker
import dev.arbjerg.lavalink.api.AudioFilterExtension
import dev.arbjerg.lavalink.api.WebSocketExtension
import dev.arbjerg.lavalink.protocol.Band
import dev.arbjerg.lavalink.protocol.Filters
import dev.arbjerg.lavalink.protocol.decodeTrack
import lavalink.server.player.TrackEndMarkerHandler
import lavalink.server.player.filters.EqualizerConfig
import lavalink.server.player.filters.FilterChain
import moe.kyokobot.koe.VoiceServerInfo
import org.json.JSONObject
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import kotlin.reflect.KFunction1

class WebSocketHandler(
    private val context: SocketContext,
    private val wsExtensions: List<WebSocketExtension>,
    private val filterExtensions: List<AudioFilterExtension>,
    private val objectMapper: ObjectMapper
) {

    companion object {
        private val log: Logger = LoggerFactory.getLogger(WebSocketHandler::class.java)
    }

    private var loggedEqualizerDeprecationWarning = false

    private val handlers: Map<String, (JSONObject) -> Unit> = mutableMapOf(
        "voiceUpdate" to ::voiceUpdate,
        "play" to ::play,
        "stop" to ::stop,
        "pause" to ::pause,
        "seek" to ::seek,
        "volume" to ::volume,
        "equalizer" to ::equalizer,
        "filters" to ::filters,
        "destroy" to ::destroy,
        "configureResuming" to ::configureResuming
    ).apply {
        wsExtensions.forEach {
            val func = fun(json: JSONObject) { it.onInvocation(context, json) }
            this[it.opName] = func as KFunction1<JSONObject, Unit>
        }
    }

    fun handle(json: JSONObject) {
        val op = json.getString("op")
        val handler = handlers[op] ?: return log.warn("Unknown op '$op'")
        handler(json)
    }

    private fun voiceUpdate(json: JSONObject) {
        val sessionId = json.getString("sessionId")
        val guildId = json.getLong("guildId")

        val event = json.getJSONObject("event")
        val endpoint: String? = event.optString("endpoint")
        val token: String = event.getString("token")

        //discord sometimes send a partial server update missing the endpoint, which can be ignored.
        endpoint ?: return
        //clear old connection
        context.koe.destroyConnection(guildId)

        val player = context.getPlayer(guildId)
        val conn = context.getMediaConnection(player)
        conn.connect(VoiceServerInfo(sessionId, endpoint, token)).whenComplete { _, _ ->
            player.provideTo(conn)
        }
    }

    private fun play(json: JSONObject) {
        val player = context.getPlayer(json.getLong("guildId"))
        val noReplace = json.optBoolean("noReplace", false)

        if (noReplace && player.track != null) {
            log.info("Skipping play request because of noReplace")
            return
        }

        val track = decodeTrack(context.audioPlayerManager, json.getString("track"))

        if (json.has("startTime")) {
            track.position = json.getLong("startTime")
        }

        player.setPause(json.optBoolean("pause", false))
        if (json.has("volume")) {
            player.setVolume(json.getInt("volume"))
        }

        if (json.has("endTime")) {
            val stopTime = json.getLong("endTime")
            if (stopTime > 0) {
                val handler = TrackEndMarkerHandler(player)
                val marker = TrackMarker(stopTime, handler)
                track.setMarker(marker)
            }
        }

        player.play(track)

        val conn = context.getMediaConnection(player)
        context.getPlayer(json.getLong("guildId")).provideTo(conn)
    }

    private fun stop(json: JSONObject) {
        val player = context.getPlayer(json.getLong("guildId"))
        player.stop()
    }

    private fun pause(json: JSONObject) {
        val player = context.getPlayer(json.getLong("guildId"))
        player.setPause(json.getBoolean("pause"))
        SocketServer.sendPlayerUpdate(context, player)
    }

    private fun seek(json: JSONObject) {
        val player = context.getPlayer(json.getLong("guildId"))
        player.seekTo(json.getLong("position"))
        SocketServer.sendPlayerUpdate(context, player)
    }

    private fun volume(json: JSONObject) {
        val player = context.getPlayer(json.getLong("guildId"))
        player.setVolume(json.getInt("volume"))
    }

    private fun equalizer(json: JSONObject) {
        if (!loggedEqualizerDeprecationWarning) {
            log.warn(
                "The 'equalizer' op has been deprecated in favour of the " +
                        "'filters' op. Please switch to use that one, as this op will get removed in v4."
            )

            loggedEqualizerDeprecationWarning = true
        }

        val player = context.getPlayer(json.getLong("guildId"))

        val bands = json.getJSONArray("bands")
            .filterIsInstance<JSONObject>()
            .map { b -> Band(b.getInt("band"), b.getFloat("gain")) }

        val filters = player.filters
            ?: FilterChain()

        filters.equalizer = EqualizerConfig(bands)
        player.filters = filters
    }

    private fun filters(json: JSONObject) {
        val player = context.getPlayer(json.getLong("guildId"))
        val filters = objectMapper.readValue(json.toString(), Filters::class.java)
        player.filters = FilterChain.parse(filters, filterExtensions)
    }

    private fun destroy(json: JSONObject) {
        context.destroyPlayer(json.getLong("guildId"))
    }

    private fun configureResuming(json: JSONObject) {
        context.resumeKey = json.optString("key", null)
        if (json.has("timeout")) context.resumeTimeout = json.getLong("timeout")
    }
}
