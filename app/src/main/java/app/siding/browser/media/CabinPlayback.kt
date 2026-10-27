package app.siding.browser.media

import android.content.Context
import android.media.MediaMetadata
import android.media.session.MediaSession
import android.media.session.PlaybackState

/**
 * Forwards headset and steering-wheel play/pause into the page only while the
 * activity is resumed and the car is not asking for distraction optimization.
 */
class CabinPlayback(
    context: Context,
    private val allowed: () -> Boolean,
    private val onCommand: (Command) -> Unit,
) {
    enum class Command { PLAY, PAUSE }

    private val session = MediaSession(context, "Siding").apply {
        setCallback(object : MediaSession.Callback() {
            override fun onPlay() {
                if (!allowed()) {
                    showPaused()
                    return
                }
                onCommand(Command.PLAY)
            }

            override fun onPause() {
                onCommand(Command.PAUSE)
            }
        })
    }

    fun setEnabled(enabled: Boolean) {
        session.isActive = enabled && allowed()
        if (!session.isActive) showPaused()
    }

    fun showPlaying() {
        publish(playing = true)
    }

    fun showPaused() {
        publish(playing = false)
    }

    fun setTitle(title: String) {
        session.setMetadata(
            MediaMetadata.Builder()
                .putString(MediaMetadata.METADATA_KEY_TITLE, title)
                .putString(MediaMetadata.METADATA_KEY_ARTIST, "Siding")
                .build(),
        )
    }

    fun release() {
        session.isActive = false
        session.release()
    }

    private fun publish(playing: Boolean) {
        val state = if (playing && allowed()) PlaybackState.STATE_PLAYING else PlaybackState.STATE_PAUSED
        session.setPlaybackState(
            PlaybackState.Builder()
                .setActions(
                    PlaybackState.ACTION_PLAY or
                        PlaybackState.ACTION_PAUSE or
                        PlaybackState.ACTION_PLAY_PAUSE,
                )
                .setState(state, PlaybackState.PLAYBACK_POSITION_UNKNOWN, if (state == PlaybackState.STATE_PLAYING) 1f else 0f)
                .build(),
        )
    }
}
