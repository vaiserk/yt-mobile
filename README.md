# YT Mobile

App pra baixar vídeo ou áudio do YouTube: cola o link, escolhe o formato e baixa.
Por baixo é yt-dlp + FFmpeg. Tem galeria e player com controle de velocidade.

## 📲 [Baixar pra Android (APK)](https://github.com/vaiserk/yt-mobile/releases/latest/download/yt-mobile.apk)

Abre o link no celular, baixa e instala. Se o Android pedir, libera "instalar apps desconhecidos" pro navegador.

## 💻 [Baixar pra Windows (instalador)](https://github.com/vaiserk/yt-mobile/releases/latest/download/yt-mobile-windows-setup.exe)

Abre o instalador e segue. Não pede senha de administrador.
Se aparecer **"O Windows protegeu o computador"**, clica em **Mais informações → Executar assim mesmo** (aparece porque o app não é assinado com certificado pago).

- Vídeos vão pra **Vídeos\YT Mobile** e músicas pra **Músicas\YT Mobile**.
- Copiou um link do YouTube? Ao voltar pra janela do app ele já cola sozinho.
- Atalhos no player: **Espaço** pausa, **← →** volta/avança 5s, **J/L** 10s, **< >** velocidade, **F** tela cheia, **N/P** próximo/anterior.
- Windows 10 ou 11 (64 bits).

## Android

## Como usar

- **Jeito rápido:** no YouTube, toca em **Compartilhar → YT Mobile**, escolhe o formato e toca em **Baixar**.
- Ou copia o link, abre o app e toca em **Colar**.
- Formatos: **1080p / 720p / 480p** (MP4) ou **MP3**. Se a qualidade escolhida não existir no vídeo, ele baixa a mais próxima.
- Vídeos vão pra **Filmes/YT Mobile** (aparecem na galeria) e músicas pra **Música/YT Mobile**.
- **Galeria** (botão no topo): lista tudo que foi baixado. Toca pra assistir/ouvir, segura pra compartilhar ou apagar.
- **Player:** controle de velocidade (0.5x até 3x) e continua tocando **com a tela desligada** ou em segundo plano, com controles na notificação e na tela de bloqueio.
- Se o YouTube começar a dar erro, toca em **Atualizar yt-dlp** no rodapé. O app também se atualiza sozinho uma vez por dia.

## Como funciona (Android)

- Feito em Kotlin com a biblioteca [youtubedl-android](https://github.com/yausername/youtubedl-android), que empacota Python, yt-dlp e FFmpeg no APK.
- Cada push na `main` faz o GitHub Actions compilar e publicar um APK novo em Releases.
- O APK é assinado com uma chave fixa (`app/yt-mobile.keystore`), então versões novas instalam por cima da antiga. Como ela está no repositório público, serve só pra uso pessoal.
- Android 10 ou mais novo.

## Como funciona (Windows)

- Interface em Python + PySide6 (Qt), empacotada com PyInstaller e instalador Inno Setup (pasta `windows/`).
- O instalador já traz `yt-dlp.exe`, FFmpeg e Deno (o yt-dlp usa o Deno pra liberar os vídeos do YouTube).
- O yt-dlp se atualiza sozinho uma vez por dia.
