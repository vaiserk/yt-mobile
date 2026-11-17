package br.com.vaiserk.ytmobile

import android.app.RecoverableSecurityException
import android.content.Intent
import android.graphics.Bitmap
import android.os.Build
import android.os.Bundle
import android.util.LruCache
import android.util.Size
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import br.com.vaiserk.ytmobile.databinding.ActivityGaleriaBinding
import br.com.vaiserk.ytmobile.databinding.ItemMidiaBinding
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale
import android.provider.MediaStore

class GaleriaActivity : AppCompatActivity() {

    private lateinit var b: ActivityGaleriaBinding
    private var itens: List<ItemMidia> = emptyList()
    private val miniaturas = LruCache<String, Bitmap>(60)
    private val adaptador = Adaptador()

    // Confirmação do sistema pra apagar (Android 11+)
    private val pedidoApagar = registerForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) {
        if (it.resultCode == RESULT_OK) recarregar()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityGaleriaBinding.inflate(layoutInflater)
        setContentView(b.root)
        b.voltar.setOnClickListener { finish() }
        b.lista.layoutManager = LinearLayoutManager(this)
        b.lista.adapter = adaptador
    }

    override fun onResume() {
        super.onResume()
        recarregar()
    }

    private fun recarregar() {
        lifecycleScope.launch {
            val novos = withContext(Dispatchers.IO) { Biblioteca.listar(applicationContext) }
            itens = novos
            adaptador.notifyDataSetChanged()
            b.vazio.visibility = if (novos.isEmpty()) View.VISIBLE else View.GONE
            b.contagem.text = when (novos.size) {
                0 -> ""
                1 -> "1 arquivo"
                else -> "${novos.size} arquivos"
            }
        }
    }

    private fun opcoes(item: ItemMidia) {
        MaterialAlertDialogBuilder(this)
            .setTitle(item.nome)
            .setItems(arrayOf("Compartilhar", "Apagar")) { _, i ->
                if (i == 0) compartilhar(item) else apagar(item)
            }
            .show()
    }

    private fun compartilhar(item: ItemMidia) {
        val envio = Intent(Intent.ACTION_SEND)
            .setType(if (item.audio) "audio/*" else "video/*")
            .putExtra(Intent.EXTRA_STREAM, item.uri)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        runCatching { startActivity(Intent.createChooser(envio, "Compartilhar")) }
    }

    private fun apagar(item: ItemMidia) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val pedido = MediaStore.createDeleteRequest(contentResolver, listOf(item.uri))
            pedidoApagar.launch(IntentSenderRequest.Builder(pedido.intentSender).build())
            return
        }
        MaterialAlertDialogBuilder(this)
            .setTitle("Apagar?")
            .setMessage(item.nome)
            .setNegativeButton("Cancelar", null)
            .setPositiveButton("Apagar") { _, _ ->
                try {
                    contentResolver.delete(item.uri, null, null)
                    recarregar()
                } catch (e: RecoverableSecurityException) {
                    pedidoApagar.launch(IntentSenderRequest.Builder(e.userAction.actionIntent.intentSender).build())
                } catch (_: Exception) {
                }
            }
            .show()
    }

    private fun duracao(ms: Long): String {
        if (ms <= 0) return ""
        val s = ms / 1000
        return if (s >= 3600) String.format(Locale.ROOT, "%d:%02d:%02d", s / 3600, s / 60 % 60, s % 60)
        else String.format(Locale.ROOT, "%d:%02d", s / 60, s % 60)
    }

    private fun tamanho(bytes: Long): String = String.format(Locale.ROOT, "%.1f MB", bytes / 1024.0 / 1024.0)

    inner class Vh(val v: ItemMidiaBinding) : RecyclerView.ViewHolder(v.root)

    inner class Adaptador : RecyclerView.Adapter<Vh>() {
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
            Vh(ItemMidiaBinding.inflate(LayoutInflater.from(parent.context), parent, false))

        override fun getItemCount() = itens.size

        override fun onBindViewHolder(h: Vh, posicao: Int) {
            val item = itens[posicao]
            h.v.nome.text = item.nome
            h.v.info.text = (if (item.audio) "MP3" else "Vídeo") + " · " + tamanho(item.tamanho)
            h.v.duracao.text = duracao(item.duracaoMs)
            h.v.duracao.visibility = if (item.duracaoMs > 0) View.VISIBLE else View.GONE
            h.v.root.setOnClickListener { PlayerActivity.abrir(this@GaleriaActivity, itens, h.bindingAdapterPosition) }
            h.v.root.setOnLongClickListener { opcoes(item); true }
            h.v.mais.setOnClickListener { opcoes(item) }

            // Miniatura
            val chave = item.uri.toString()
            h.v.miniatura.tag = chave
            h.v.icone.visibility = View.GONE
            val pronta = miniaturas.get(chave)
            if (pronta != null) {
                h.v.miniatura.setImageBitmap(pronta)
                return
            }
            h.v.miniatura.setImageDrawable(null)
            lifecycleScope.launch {
                val bmp = withContext(Dispatchers.IO) {
                    runCatching { contentResolver.loadThumbnail(item.uri, Size(320, 180), null) }.getOrNull()
                }
                if (h.v.miniatura.tag != chave) return@launch
                if (bmp != null) {
                    miniaturas.put(chave, bmp)
                    h.v.miniatura.setImageBitmap(bmp)
                } else {
                    h.v.icone.text = if (item.audio) "♪" else "▶"
                    h.v.icone.visibility = View.VISIBLE
                }
            }
        }
    }
}
