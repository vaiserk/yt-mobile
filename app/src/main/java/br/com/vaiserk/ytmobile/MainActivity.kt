package br.com.vaiserk.ytmobile

import android.content.ClipboardManager
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import br.com.vaiserk.ytmobile.databinding.ActivityMainBinding
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private lateinit var b: ActivityMainBinding
    private val vm: MainViewModel by viewModels()
    private val prefs by lazy { getSharedPreferences("config", MODE_PRIVATE) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityMainBinding.inflate(layoutInflater)
        setContentView(b.root)

        // Formato: lembra o último escolhido
        val salvo = runCatching { Formato.valueOf(prefs.getString("formato", null) ?: "P720") }.getOrDefault(Formato.P720)
        b.formatos.check(idDe(salvo))
        b.formatos.addOnButtonCheckedListener { _, id, marcado ->
            if (marcado) prefs.edit().putString("formato", formatoDe(id).name).apply()
        }

        b.colar.setOnClickListener {
            val cm = getSystemService(ClipboardManager::class.java)
            val texto = cm?.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(this)?.toString()
            if (!texto.isNullOrBlank()) b.url.setText(extrairLink(texto) ?: texto.trim())
        }

        b.baixar.setOnClickListener { iniciarDownload() }
        b.url.setOnEditorActionListener { _, acao, _ ->
            if (acao == EditorInfo.IME_ACTION_DONE) { iniciarDownload(); true } else false
        }
        b.cancelar.setOnClickListener { vm.cancelar() }
        b.abrir.setOnClickListener { abrirArquivo() }
        b.atualizar.setOnClickListener { vm.atualizar() }

        tratarIntent(intent)

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                vm.estado.collect { render(it) }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        tratarIntent(intent)
    }

    /** Link compartilhado pelo YouTube (botão Compartilhar) */
    private fun tratarIntent(intent: Intent?) {
        if (intent?.action == Intent.ACTION_SEND) {
            val texto = intent.getStringExtra(Intent.EXTRA_TEXT) ?: return
            b.url.setText(extrairLink(texto) ?: texto.trim())
        }
    }

    private fun iniciarDownload() {
        val url = b.url.text?.toString()?.trim().orEmpty()
        if (url.isEmpty()) {
            b.campoUrl.error = "Cola um link primeiro"
            return
        }
        b.campoUrl.error = null
        getSystemService(InputMethodManager::class.java)?.hideSoftInputFromWindow(b.url.windowToken, 0)
        b.url.clearFocus()
        vm.baixar(extrairLink(url) ?: url, formatoDe(b.formatos.checkedButtonId))
    }

    private fun render(e: Estado) {
        b.baixar.isEnabled = e.iniciado && !e.ocupado
        b.baixar.text = when {
            !e.iniciado -> "Preparando..."
            e.ocupado -> "Baixando..."
            else -> "Baixar"
        }
        b.atualizar.isEnabled = e.iniciado && !e.ocupado
        b.versao.text = if (e.versao.isNotBlank()) "yt-dlp ${e.versao}" else ""

        b.card.visibility = if (e.mostrarCard) View.VISIBLE else View.GONE
        b.titulo.text = e.titulo
        b.titulo.visibility = if (e.titulo.isBlank()) View.GONE else View.VISIBLE
        b.etapa.text = e.etapa
        b.etapa.setTextColor(ContextCompat.getColor(this, if (e.erro) R.color.erro else R.color.texto2))
        b.detalhe.text = e.detalhe
        b.detalhe.visibility = if (e.detalhe.isBlank()) View.GONE else View.VISIBLE

        // Barra: indeterminada quando não dá pra saber o progresso (ex: convertendo)
        val indeterminada = e.progresso == null
        if (b.barra.isIndeterminate != indeterminada) {
            b.barra.visibility = View.INVISIBLE
            b.barra.isIndeterminate = indeterminada
        }
        if (!indeterminada) b.barra.setProgressCompat(e.progresso ?: 0, true)
        b.barra.visibility = if (e.ocupado) View.VISIBLE else View.GONE

        b.cancelar.visibility = if (e.ocupado) View.VISIBLE else View.GONE
        b.abrir.visibility = if (!e.ocupado && e.arquivo != null) View.VISIBLE else View.GONE

        if (e.ocupado) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    private fun abrirArquivo() {
        val e = vm.estado.value
        val uri = e.arquivo ?: return
        val abrir = Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, e.mime)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        runCatching { startActivity(Intent.createChooser(abrir, "Abrir com")) }
    }

    private fun extrairLink(texto: String): String? = Regex("https?://\\S+").find(texto)?.value

    private fun formatoDe(id: Int): Formato = when (id) {
        R.id.f1080 -> Formato.P1080
        R.id.f480 -> Formato.P480
        R.id.fmp3 -> Formato.MP3
        else -> Formato.P720
    }

    private fun idDe(f: Formato): Int = when (f) {
        Formato.P1080 -> R.id.f1080
        Formato.P720 -> R.id.f720
        Formato.P480 -> R.id.f480
        Formato.MP3 -> R.id.fmp3
    }
}
