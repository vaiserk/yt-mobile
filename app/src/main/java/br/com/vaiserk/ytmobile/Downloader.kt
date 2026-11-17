package br.com.vaiserk.ytmobile

import android.app.Application
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import androidx.core.content.ContextCompat
import com.yausername.ffmpeg.FFmpeg
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.util.Locale
import java.util.UUID

enum class Formato(val altura: Int?) { P1080(1080), P720(720), P480(480), MP3(null) }

data class Estado(
    val iniciado: Boolean = false,
    val ocupado: Boolean = false,
    val baixando: Boolean = false, // true só em download (não em atualização)
    val mostrarCard: Boolean = false,
    val titulo: String = "",
    val etapa: String = "",
    val progresso: Int? = null,
    val detalhe: String = "",
    val erro: Boolean = false,
    val arquivo: Uri? = null,
    val mime: String? = null,
    val versao: String = "",
)

/**
 * Faz o trabalho pesado (yt-dlp + FFmpeg). Fica fora da tela de propósito:
 * o download continua mesmo se a Activity for fechada, e o DownloadService
 * mantém o processo vivo com a tela desligada / app em segundo plano.
 */
object Downloader {

    private val _estado = MutableStateFlow(Estado())
    val estado: StateFlow<Estado> = _estado.asStateFlow()

    private val escopo = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val trava = Mutex()
    private lateinit var app: Application
    private var iniciou = false
    private var processoAtual: String? = null

    @Volatile
    private var cancelado = false

    @Volatile
    private var atualizandoSozinho = false

    @Synchronized
    fun iniciar(contexto: Context) {
        if (iniciou) return
        iniciou = true
        app = contexto.applicationContext as Application
        escopo.launch {
            try {
                // Restos de downloads interrompidos (app fechado no meio)
                File(app.cacheDir, "dl").deleteRecursively()
                YoutubeDL.getInstance().init(app)
                FFmpeg.getInstance().init(app)
                _estado.update { it.copy(iniciado = true, versao = versaoYtdlp()) }
                atualizarYtdlp(automatico = true)
            } catch (e: Exception) {
                _estado.update { it.copy(mostrarCard = true, erro = true, etapa = "Falha ao iniciar: ${e.message}") }
            }
        }
    }

    // ------------------------------------------------------------ download
    fun baixar(url: String, formato: Formato) {
        val atual = _estado.value
        if (atual.ocupado || !atual.iniciado) return
        cancelado = false
        val id = UUID.randomUUID().toString()
        processoAtual = id
        _estado.update {
            it.copy(
                ocupado = true, baixando = true, mostrarCard = true, erro = false, titulo = "",
                etapa = if (atualizandoSozinho) "Terminando de atualizar o yt-dlp..." else "Lendo informações do vídeo...",
                progresso = null, detalhe = "", arquivo = null,
            )
        }
        // Serviço em primeiro plano: segura o processo vivo até acabar
        runCatching { ContextCompat.startForegroundService(app, Intent(app, DownloadService::class.java)) }

        escopo.launch {
            trava.withLock {
                val pasta = File(app.cacheDir, "dl/$id").apply { mkdirs() }
                try {
                    if (cancelado) throw Exception("cancelado")
                    val req = YoutubeDLRequest(url)
                    req.addOption("--no-playlist")
                    req.addOption("-o", "${pasta.absolutePath}/%(title).80B.%(ext)s")
                    // Cache persistente: não precisa resolver a proteção do YouTube a cada download
                    req.addOption("--cache-dir", File(app.filesDir, "yt-dlp-cache").absolutePath)
                    if (formato == Formato.MP3) {
                        req.addOption("-f", "ba/b")
                        req.addOption("-x")
                        req.addOption("--audio-format", "mp3")
                        req.addOption("--audio-quality", "192K")
                    } else {
                        // Qualidade escolhida; se não existir, a mais próxima abaixo (ou acima).
                        // Prefere H.264 + AAC, que tocam em qualquer celular.
                        req.addOption("-f", "bv*+ba/b")
                        req.addOption("-S", "res:${formato.altura},vcodec:h264,acodec:m4a")
                        req.addOption("--merge-output-format", "mp4")
                    }

                    var parte = 0
                    YoutubeDL.getInstance().execute(req, id) { progresso, eta, linha ->
                        when {
                            linha.contains("Downloading webpage") || linha.contains("player API JSON") ->
                                _estado.update { it.copy(etapa = "Conectando ao YouTube...", progresso = null) }
                            linha.contains("Downloading player") || linha.contains("Solving JS challenges") ->
                                _estado.update {
                                    it.copy(etapa = "Liberando o vídeo (só demora na primeira vez)...", progresso = null)
                                }
                            linha.startsWith("[download]") && linha.contains("Destination:") -> {
                                parte++
                                val nome = File(linha.substringAfter("Destination:").trim()).name
                                val titulo = nome.substringBeforeLast('.').replace(Regex("\\.f\\d+$"), "")
                                val etapa = when {
                                    formato == Formato.MP3 -> "Baixando áudio..."
                                    parte == 1 -> "Baixando vídeo..."
                                    else -> "Baixando áudio..."
                                }
                                _estado.update { it.copy(titulo = titulo, etapa = etapa, progresso = 0, detalhe = "") }
                            }
                            linha.startsWith("[Merger]") ->
                                _estado.update { it.copy(etapa = "Juntando vídeo e áudio...", progresso = null, detalhe = "") }
                            linha.startsWith("[ExtractAudio]") ->
                                _estado.update { it.copy(etapa = "Convertendo pra MP3...", progresso = null, detalhe = "") }
                            linha.startsWith("[download]") && linha.contains("%") && progresso >= 0f ->
                                _estado.update { it.copy(progresso = progresso.toInt(), detalhe = detalheDe(linha, eta)) }
                        }
                    }

                    val arquivo = pasta.listFiles()
                        ?.filter { it.isFile && !it.name.endsWith(".part") && !it.name.endsWith(".ytdl") }
                        ?.maxByOrNull { it.length() }
                        ?: throw Exception("O download terminou mas nenhum arquivo foi gerado.")

                    _estado.update { it.copy(etapa = "Salvando no celular...", progresso = null, detalhe = "") }
                    val mime = mimeDe(arquivo)
                    val audio = mime.startsWith("audio")
                    val uri = salvarNaGaleria(arquivo, mime, audio)

                    _estado.update {
                        it.copy(
                            ocupado = false, baixando = false,
                            titulo = arquivo.nameWithoutExtension,
                            etapa = "Pronto! Tá na Galeria do app.",
                            progresso = 100,
                            detalhe = tamanho(arquivo.length()),
                            arquivo = uri,
                            mime = mime,
                        )
                    }
                } catch (e: Exception) {
                    val msg = if (cancelado) "Download cancelado." else limparErro(e.message)
                    _estado.update {
                        it.copy(ocupado = false, baixando = false, erro = !cancelado, etapa = msg, progresso = null, detalhe = "")
                    }
                } finally {
                    pasta.deleteRecursively()
                    processoAtual = null
                }
            }
        }
    }

    fun cancelar() {
        cancelado = true
        processoAtual?.let { runCatching { YoutubeDL.getInstance().destroyProcessById(it) } }
    }

    // ------------------------------------------------------------ atualização do yt-dlp
    fun atualizar() {
        escopo.launch { atualizarYtdlp(automatico = false) }
    }

    private fun atualizarYtdlp(automatico: Boolean) {
        val prefs = app.getSharedPreferences("config", Context.MODE_PRIVATE)
        val agora = System.currentTimeMillis()
        // Chave por versão do app: atualizar o APK traz um yt-dlp antigo junto, então atualiza de novo
        val chave = "ultimaAtualizacao_" + versaoApp()
        if (automatico && agora - prefs.getLong(chave, 0L) < 24 * 60 * 60 * 1000L) return
        if (!trava.tryLock()) return
        atualizandoSozinho = automatico
        try {
            if (!automatico) {
                _estado.update {
                    it.copy(
                        ocupado = true, mostrarCard = true, erro = false, titulo = "", arquivo = null,
                        etapa = "Atualizando yt-dlp...", progresso = null, detalhe = "",
                    )
                }
            }
            val status = YoutubeDL.getInstance().updateYoutubeDL(app, YoutubeDL.UpdateChannel.STABLE)
            prefs.edit().putLong(chave, agora).apply()
            val v = versaoYtdlp()
            _estado.update { it.copy(versao = v) }
            if (!automatico) {
                val msg = if (status?.name == "ALREADY_UP_TO_DATE") "yt-dlp já está na versão mais nova ($v)."
                else "yt-dlp atualizado pra $v."
                _estado.update { it.copy(ocupado = false, etapa = msg) }
            }
        } catch (e: Exception) {
            if (!automatico) {
                _estado.update { it.copy(ocupado = false, erro = true, etapa = "Não deu pra atualizar: ${e.message}") }
            }
        } finally {
            atualizandoSozinho = false
            trava.unlock()
        }
    }

    // ------------------------------------------------------------ utilidades
    private fun versaoApp(): Long =
        runCatching { app.packageManager.getPackageInfo(app.packageName, 0).longVersionCode }.getOrDefault(0L)

    private fun versaoYtdlp(): String =
        try { YoutubeDL.getInstance().version(app) ?: "" } catch (e: Exception) { "" }

    private fun salvarNaGaleria(arquivo: File, mime: String, audio: Boolean): Uri {
        val resolver = app.contentResolver
        val colecao = if (audio) {
            MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        } else {
            MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        }
        val pastaRelativa = (if (audio) Environment.DIRECTORY_MUSIC else Environment.DIRECTORY_MOVIES) + "/YT Mobile"
        val valores = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, arquivo.name)
            put(MediaStore.MediaColumns.MIME_TYPE, mime)
            put(MediaStore.MediaColumns.RELATIVE_PATH, pastaRelativa)
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val uri = resolver.insert(colecao, valores) ?: throw Exception("Não consegui criar o arquivo no armazenamento.")
        try {
            val saida = resolver.openOutputStream(uri) ?: throw Exception("Não consegui gravar o arquivo.")
            saida.use { out -> arquivo.inputStream().use { it.copyTo(out) } }
            valores.clear()
            valores.put(MediaStore.MediaColumns.IS_PENDING, 0)
            resolver.update(uri, valores, null, null)
        } catch (e: Exception) {
            resolver.delete(uri, null, null)
            throw e
        }
        return uri
    }

    private fun mimeDe(arquivo: File): String = when (arquivo.extension.lowercase(Locale.ROOT)) {
        "mp3" -> "audio/mpeg"
        "m4a" -> "audio/mp4"
        "opus", "ogg" -> "audio/ogg"
        "webm" -> "video/webm"
        "mkv" -> "video/x-matroska"
        else -> "video/mp4"
    }

    private fun detalheDe(linha: String, eta: Long): String {
        val m = Regex("of\\s+~?\\s*([\\d.]+\\s*\\w+)(?:\\s+at\\s+([\\d.]+\\s*\\w+/s))?").find(linha)
        val partes = mutableListOf<String>()
        m?.groupValues?.getOrNull(1)?.takeIf { it.isNotBlank() }?.let { partes += it.replace("iB", "B") }
        m?.groupValues?.getOrNull(2)?.takeIf { it.isNotBlank() }?.let { partes += it.replace("iB", "B") }
        if (eta > 0) partes += "faltam " + String.format(Locale.ROOT, "%d:%02d", eta / 60, eta % 60)
        return partes.joinToString(" · ")
    }

    private fun tamanho(bytes: Long): String {
        val mb = bytes / 1024.0 / 1024.0
        return if (mb >= 1) String.format(Locale.ROOT, "%.1f MB", mb) else "${bytes / 1024} KB"
    }

    private fun limparErro(msg: String?): String {
        val texto = msg ?: return "Erro desconhecido."
        val linhas = texto.lines().filter { it.isNotBlank() }
        val linha = linhas.lastOrNull { it.contains("ERROR:") }?.substringAfter("ERROR:")?.trim()
            ?: linhas.lastOrNull()?.trim()
            ?: texto
        return when {
            "Unsupported URL" in linha -> "Link não suportado. Confere se é um link de vídeo."
            "Private video" in linha -> "Esse vídeo é privado."
            "Sign in to confirm" in linha -> "O YouTube pediu verificação. Toca em \"Atualizar yt-dlp\" e tenta de novo."
            "Video unavailable" in linha -> "Vídeo indisponível."
            "No space left" in linha || "ENOSPC" in linha -> "Sem espaço no celular."
            "resolve host" in linha || "Failed to resolve" in linha || "Network is unreachable" in linha -> "Sem internet."
            else -> linha.take(300)
        }
    }
}
