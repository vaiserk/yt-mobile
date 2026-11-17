package br.com.vaiserk.ytmobile

import android.app.PendingIntent
import android.content.Intent
import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture

/**
 * Mantém o player vivo fora da tela: continua tocando com a tela desligada
 * ou com o app em segundo plano, com controles na notificação/tela de bloqueio.
 */
@OptIn(UnstableApi::class)
class PlaybackService : MediaSessionService() {

    private var sessao: MediaSession? = null

    override fun onCreate() {
        super.onCreate()
        val player = ExoPlayer.Builder(this)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
                    .build(),
                true, // pausa se outro app tocar algo / ligação
            )
            .setHandleAudioBecomingNoisy(true) // pausa ao tirar o fone
            .setWakeMode(C.WAKE_MODE_LOCAL) // não dorme com a tela desligada
            .build()

        val abrirPlayer = PendingIntent.getActivity(
            this, 0,
            Intent(this, PlayerActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        sessao = MediaSession.Builder(this, player)
            .setSessionActivity(abrirPlayer)
            .setCallback(object : MediaSession.Callback {
                // O controller manda só o id; aqui a gente transforma de volta no arquivo
                override fun onAddMediaItems(
                    mediaSession: MediaSession,
                    controller: MediaSession.ControllerInfo,
                    mediaItems: MutableList<MediaItem>,
                ): ListenableFuture<MutableList<MediaItem>> {
                    val itens = mediaItems.map { item ->
                        val uri = item.requestMetadata.mediaUri ?: Uri.parse(item.mediaId)
                        item.buildUpon().setUri(uri).build()
                    }.toMutableList()
                    return Futures.immediateFuture(itens)
                }
            })
            .build()
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = sessao

    // Fechou o app pela lista de recentes: para tudo
    override fun onTaskRemoved(rootIntent: Intent?) {
        sessao?.player?.let {
            it.pause()
            it.stop()
        }
        stopSelf()
    }

    override fun onDestroy() {
        sessao?.run {
            player.release()
            release()
        }
        sessao = null
        super.onDestroy()
    }
}
