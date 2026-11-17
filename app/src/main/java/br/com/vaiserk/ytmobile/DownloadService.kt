package br.com.vaiserk.ytmobile

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Mostra o progresso na notificação e mantém o app vivo enquanto baixa
 * (sem isso o Android pode matar o download com a tela desligada).
 */
class DownloadService : Service() {

    companion object {
        private const val CANAL = "downloads"
        private const val ID_PROGRESSO = 10
        private const val ID_FIM = 11
        private const val ACAO_CANCELAR = "cancelar"
    }

    private val escopo = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var observador: Job? = null
    private var ultimaNotificacao = 0L
    private var ultimaEtapa = ""

    private val nm by lazy { getSystemService(NotificationManager::class.java) }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        nm.createNotificationChannel(
            NotificationChannel(CANAL, "Downloads", NotificationManager.IMPORTANCE_LOW),
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Precisa virar primeiro plano logo de cara (regra do Android)
        val notif = montar(Downloader.estado.value)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(ID_PROGRESSO, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(ID_PROGRESSO, notif)
        }

        if (intent?.action == ACAO_CANCELAR) Downloader.cancelar()

        if (observador == null) {
            observador = escopo.launch {
                Downloader.estado.collect { e ->
                    if (!e.ocupado) {
                        finalizar(e)
                    } else {
                        // Não spammar a notificação: no máximo 2x por segundo, exceto mudança de etapa
                        val agora = System.currentTimeMillis()
                        if (e.etapa != ultimaEtapa || agora - ultimaNotificacao > 500) {
                            ultimaEtapa = e.etapa
                            ultimaNotificacao = agora
                            nm.notify(ID_PROGRESSO, montar(e))
                        }
                    }
                }
            }
        }
        return START_NOT_STICKY
    }

    private fun finalizar(e: Estado) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) stopForeground(STOP_FOREGROUND_REMOVE)
        // Aviso final só se foi um download de verdade (não cancelado)
        if (e.etapa != "Download cancelado." && (e.arquivo != null || e.erro)) {
            val fim = NotificationCompat.Builder(this, CANAL)
                .setSmallIcon(R.drawable.ic_notificacao)
                .setContentTitle(if (e.erro) "Falha no download" else "Download concluído")
                .setContentText(if (e.erro) e.etapa else e.titulo)
                .setContentIntent(abrirApp())
                .setAutoCancel(true)
                .build()
            nm.notify(ID_FIM, fim)
        }
        observador?.cancel()
        observador = null
        stopSelf()
    }

    private fun montar(e: Estado) = NotificationCompat.Builder(this, CANAL)
        .setSmallIcon(R.drawable.ic_notificacao)
        .setContentTitle(e.titulo.ifBlank { "Baixando..." })
        .setContentText(listOf(e.etapa, e.detalhe).filter { it.isNotBlank() }.joinToString(" · "))
        .setProgress(100, e.progresso ?: 0, e.progresso == null)
        .setOngoing(true)
        .setOnlyAlertOnce(true)
        .setSilent(true)
        .setContentIntent(abrirApp())
        .addAction(
            0, "Cancelar",
            PendingIntent.getService(
                this, 1,
                Intent(this, DownloadService::class.java).setAction(ACAO_CANCELAR),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            ),
        )
        .build()

    private fun abrirApp(): PendingIntent = PendingIntent.getActivity(
        this, 0,
        Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    override fun onDestroy() {
        escopo.cancel()
        super.onDestroy()
    }
}
