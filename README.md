# YT Mobile

App Android pra baixar vídeo ou áudio do YouTube: cola o link, escolhe o formato e baixa.
Por baixo é yt-dlp + FFmpeg, tudo rodando dentro do próprio celular.

## 📲 [Baixar o APK](https://github.com/vaiserk/yt-mobile/releases/latest/download/yt-mobile.apk)

Abre o link no celular, baixa e instala. Se o Android pedir, libera "instalar apps desconhecidos" pro navegador.

## Como usar

- **Jeito rápido:** no YouTube, toca em **Compartilhar → YT Mobile**, escolhe o formato e toca em **Baixar**.
- Ou copia o link, abre o app e toca em **Colar**.
- Formatos: **1080p / 720p / 480p** (MP4) ou **MP3**.
- Vídeos vão pra **Filmes/YT Mobile** (aparecem na galeria) e músicas pra **Música/YT Mobile**.
- Se o YouTube começar a dar erro, toca em **Atualizar yt-dlp** no rodapé. O app também se atualiza sozinho uma vez por dia.

## Como funciona

- Feito em Kotlin com a biblioteca [youtubedl-android](https://github.com/yausername/youtubedl-android), que empacota Python, yt-dlp e FFmpeg no APK.
- Cada push na `main` faz o GitHub Actions compilar e publicar um APK novo em Releases.
- O APK é assinado com uma chave fixa (`app/yt-mobile.keystore`), então versões novas instalam por cima da antiga. Como ela está no repositório público, serve só pra uso pessoal.
- Android 10 ou mais novo.
