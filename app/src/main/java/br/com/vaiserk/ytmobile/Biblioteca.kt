package br.com.vaiserk.ytmobile

import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.provider.MediaStore

data class ItemMidia(
    val uri: Uri,
    val nome: String,
    val duracaoMs: Long,
    val tamanho: Long,
    val data: Long,
    val audio: Boolean,
)

/** Lista o que o app salvou em Filmes/YT Mobile e Música/YT Mobile. */
object Biblioteca {

    fun listar(ctx: Context): List<ItemMidia> {
        val videos = consultar(ctx, MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), false)
        val audios = consultar(ctx, MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), true)
        return (videos + audios).sortedByDescending { it.data }
    }

    private fun consultar(ctx: Context, colecao: Uri, audio: Boolean): List<ItemMidia> {
        val projecao = arrayOf(
            MediaStore.MediaColumns._ID,
            MediaStore.MediaColumns.DISPLAY_NAME,
            MediaStore.MediaColumns.DURATION,
            MediaStore.MediaColumns.SIZE,
            MediaStore.MediaColumns.DATE_ADDED,
        )
        val lista = mutableListOf<ItemMidia>()
        runCatching {
            ctx.contentResolver.query(
                colecao, projecao,
                "${MediaStore.MediaColumns.RELATIVE_PATH} LIKE ?", arrayOf("%YT Mobile%"),
                "${MediaStore.MediaColumns.DATE_ADDED} DESC",
            )?.use { c ->
                val iId = c.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
                val iNome = c.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME)
                val iDur = c.getColumnIndexOrThrow(MediaStore.MediaColumns.DURATION)
                val iTam = c.getColumnIndexOrThrow(MediaStore.MediaColumns.SIZE)
                val iData = c.getColumnIndexOrThrow(MediaStore.MediaColumns.DATE_ADDED)
                while (c.moveToNext()) {
                    lista += ItemMidia(
                        uri = ContentUris.withAppendedId(colecao, c.getLong(iId)),
                        nome = (c.getString(iNome) ?: "").substringBeforeLast('.'),
                        duracaoMs = c.getLong(iDur),
                        tamanho = c.getLong(iTam),
                        data = c.getLong(iData),
                        audio = audio,
                    )
                }
            }
        }
        return lista
    }
}
