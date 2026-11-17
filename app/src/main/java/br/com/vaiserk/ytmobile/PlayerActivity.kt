package br.com.vaiserk.ytmobile

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.net.Uri
import android.os.Bundle
import androidx.activity.OnBackPressedCallback
import androidx.annotation.OptIn
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import androidx.media3.ui.PlayerView
import br.com.vaiserk.ytmobile.databinding.ActivityPlayerBinding
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.common.util.concurrent.ListenableFuture
import java.util.Locale

@OptIn(UnstableApi::class)
class PlayerActivity : AppCompatActivity() {

    companion object {
        private const val EXTRA_URIS = "uris"
        private const val EXTRA_TITULOS = "titulos"
        private const val EXTRA_INDICE = "indice"
        val VELOCIDADES = floatArrayOf(0.5f, 0.75f, 1f, 1.25f, 1.5f, 1.75f, 2f, 2.5f, 3f)

        fun abrir(ctx: Context, itens: List<ItemMidia>, indice: Int) {
            ctx.startActivity(
                Intent(ctx, PlayerActivity::class.java)
                    .putExtra(EXTRA_URIS, itens.map { it.uri.toString() }.toTypedArray())
                    .putExtra(EXTRA_TITULOS, itens.map { it.nome }.toTypedArray())
                    .putExtra(EXTRA_INDICE, indice),
            )
        }
    }

    private lateinit var b: ActivityPlayerBinding
    private var futuro: ListenableFuture<MediaController>? = null
    private var controller: MediaController? = null
    private var itensPendentes = true
    private val prefs by lazy { getSharedPreferences("config", MODE_PRIVATE) }

    private val ouvinte = object : Player.Listener {
        override fun onMediaMetadataChanged(mediaMetadata: MediaMetadata) {
            b.titulo.text = mediaMetadata.title ?: ""
        }

        override fun onPlaybackParametersChanged(playbackParameters: PlaybackParameters) {
            mostrarVelocidade(playbackParameters.speed)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityPlayerBinding.inflate(layoutInflater)
        setContentView(b.root)

        itensPendentes = savedInstanceState == null
        b.voltar.setOnClickListener { sair() }
        b.velocidade.setOnClickListener { escolherVelocidade() }
        mostrarVelocidade(prefs.getFloat("velocidade", 1f))

        // A barra de cima some/aparece junto com os controles do player
        b.player.setControllerVisibilityListener(
            PlayerView.ControllerVisibilityListener { visibilidade -> b.barraTopo.visibility = visibilidade },
        )

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() = sair()
        })
        ajustarTelaCheia(resources.configuration)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (intent.hasExtra(EXTRA_URIS)) {
            itensPendentes = true
            carregarItens()
        }
    }

    override fun onStart() {
        super.onStart()
        val token = SessionToken(this, ComponentName(this, PlaybackService::class.java))
        val f = MediaController.Builder(this, token).buildAsync()
        futuro = f
        f.addListener({
            val c = runCatching { f.get() }.getOrNull() ?: return@addListener
            controller = c
            b.player.player = c
            c.addListener(ouvinte)
            b.titulo.text = c.mediaMetadata.title ?: ""
            carregarItens()
        }, ContextCompat.getMainExecutor(this))
    }

    // Tela desligou ou foi pra outro app: solta a tela, mas o serviço continua tocando
    override fun onStop() {
        controller?.removeListener(ouvinte)
        b.player.player = null
        futuro?.let { MediaController.releaseFuture(it) }
        futuro = null
        controller = null
        super.onStop()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        ajustarTelaCheia(newConfig)
    }

    private fun carregarItens() {
        val c = controller ?: return
        if (!itensPendentes) return
        val uris = intent.getStringArrayExtra(EXTRA_URIS) ?: return
        val titulos = intent.getStringArrayExtra(EXTRA_TITULOS) ?: arrayOf()
        val indice = intent.getIntExtra(EXTRA_INDICE, 0)
        val itens = uris.mapIndexed { i, u ->
            MediaItem.Builder()
                .setMediaId(u)
                .setUri(Uri.parse(u))
                .setRequestMetadata(MediaItem.RequestMetadata.Builder().setMediaUri(Uri.parse(u)).build())
                .setMediaMetadata(MediaMetadata.Builder().setTitle(titulos.getOrNull(i) ?: "").build())
                .build()
        }
        c.setMediaItems(itens, indice.coerceIn(0, (itens.size - 1).coerceAtLeast(0)), 0L)
        c.setPlaybackSpeed(prefs.getFloat("velocidade", 1f))
        c.prepare()
        c.play()
        itensPendentes = false
    }

    private fun escolherVelocidade() {
        val atual = controller?.playbackParameters?.speed ?: prefs.getFloat("velocidade", 1f)
        val rotulos = VELOCIDADES.map { formatar(it) + if (it == 1f) "  (normal)" else "" }.toTypedArray()
        val marcado = VELOCIDADES.indexOfFirst { it == atual }
        MaterialAlertDialogBuilder(this)
            .setTitle("Velocidade")
            .setSingleChoiceItems(rotulos, marcado) { dialogo, i ->
                val v = VELOCIDADES[i]
                controller?.setPlaybackSpeed(v)
                prefs.edit().putFloat("velocidade", v).apply()
                mostrarVelocidade(v)
                dialogo.dismiss()
            }
            .show()
    }

    private fun mostrarVelocidade(v: Float) {
        b.velocidade.text = formatar(v)
    }

    private fun formatar(v: Float): String =
        (if (v % 1f == 0f) String.format(Locale.ROOT, "%.0f", v) else String.format(Locale.ROOT, "%s", v)) + "x"

    /** Sair pelo botão voltar para a reprodução (com a tela desligada ela continua). */
    private fun sair() {
        controller?.run {
            stop()
            clearMediaItems()
        }
        finish()
    }

    private fun ajustarTelaCheia(config: Configuration) {
        val janela = WindowCompat.getInsetsController(window, window.decorView)
        if (config.orientation == Configuration.ORIENTATION_LANDSCAPE) {
            janela.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            janela.hide(WindowInsetsCompat.Type.systemBars())
        } else {
            janela.show(WindowInsetsCompat.Type.systemBars())
        }
    }
}
