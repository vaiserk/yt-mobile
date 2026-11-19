# -*- coding: utf-8 -*-
"""
YT Mobile para Windows — baixa vídeo/áudio do YouTube (yt-dlp + FFmpeg),
com galeria e player com controle de velocidade.
"""
import hashlib
import os
import re
import shutil
import subprocess
import sys
import threading
import time
import uuid
from dataclasses import dataclass, replace
from pathlib import Path

from PySide6.QtCore import QEvent, QObject, QSettings, QSize, QStandardPaths, Qt, QTimer, QUrl, Signal
from PySide6.QtGui import (
    QAction, QColor, QDesktopServices, QFont, QGuiApplication, QIcon, QKeySequence, QPainter,
    QPainterPath, QPen, QPixmap, QShortcut,
)
from PySide6.QtMultimedia import QAudioOutput, QMediaPlayer
from PySide6.QtMultimediaWidgets import QVideoWidget
from PySide6.QtWidgets import (
    QApplication, QButtonGroup, QComboBox, QFrame, QHBoxLayout, QLabel, QLineEdit, QListWidget,
    QListWidgetItem, QMainWindow, QMenu, QMessageBox, QProgressBar, QPushButton, QSizePolicy,
    QSlider, QStackedWidget, QStyle, QTabWidget, QVBoxLayout, QWidget,
)

try:
    from versao import VERSAO
except ImportError:
    VERSAO = "dev"

APP = "YT Mobile"
WINDOWS = sys.platform == "win32"
SEM_JANELA = 0x08000000 if WINDOWS else 0  # CREATE_NO_WINDOW: não abre console preto
VELOCIDADES = [0.5, 0.75, 1.0, 1.25, 1.5, 1.75, 2.0, 2.5, 3.0]
EXT_AUDIO = {".mp3", ".m4a", ".opus", ".ogg", ".wav", ".flac"}
EXT_MIDIA = EXT_AUDIO | {".mp4", ".webm", ".mkv", ".mov"}


# ============================================================== caminhos
def pasta_app() -> Path:
    if getattr(sys, "frozen", False):
        return Path(sys.executable).resolve().parent
    return Path(__file__).resolve().parent


BIN = pasta_app() / "bin"


def binario(nome: str) -> str:
    local = BIN / (nome + (".exe" if WINDOWS else ""))
    return str(local) if local.exists() else (shutil.which(nome) or str(local))


def pasta_dados() -> Path:
    base = os.environ.get("LOCALAPPDATA") or str(Path.home() / ".local" / "share")
    p = Path(base) / APP
    p.mkdir(parents=True, exist_ok=True)
    return p


def pasta_videos() -> Path:
    return Path(QStandardPaths.writableLocation(QStandardPaths.StandardLocation.MoviesLocation)) / APP


def pasta_musicas() -> Path:
    return Path(QStandardPaths.writableLocation(QStandardPaths.StandardLocation.MusicLocation)) / APP


def tamanho(n: float) -> str:
    for u in ("B", "KB", "MB", "GB"):
        if n < 1024 or u == "GB":
            return f"{n:.1f} {u}" if u != "B" else f"{int(n)} B"
        n /= 1024
    return ""


def tempo(seg: float) -> str:
    seg = int(max(0, seg))
    h, m, s = seg // 3600, seg // 60 % 60, seg % 60
    return f"{h}:{m:02d}:{s:02d}" if h else f"{m}:{s:02d}"


def nome_livre(destino: Path) -> Path:
    """Evita sobrescrever: video.mp4 -> video (2).mp4"""
    if not destino.exists():
        return destino
    i = 2
    while True:
        p = destino.with_name(f"{destino.stem} ({i}){destino.suffix}")
        if not p.exists():
            return p
        i += 1


def abrir_no_explorer(arquivo: Path):
    if WINDOWS:
        subprocess.Popen(["explorer", "/select,", str(arquivo)], creationflags=SEM_JANELA)
    else:
        QDesktopServices.openUrl(QUrl.fromLocalFile(str(arquivo.parent)))


# ============================================================== motor (yt-dlp)
@dataclass
class Estado:
    iniciado: bool = False
    ocupado: bool = False
    baixando: bool = False
    mostrar: bool = False
    titulo: str = ""
    etapa: str = ""
    progresso: float | None = None
    detalhe: str = ""
    erro: bool = False
    arquivo: str = ""
    versao: str = ""


class Motor(QObject):
    mudou = Signal(object)

    def __init__(self, cfg: QSettings):
        super().__init__()
        self.cfg = cfg
        self.estado = Estado()
        self.trava = threading.Lock()
        self.proc: subprocess.Popen | None = None
        self.cancelado = False
        self.atualizando_sozinho = False
        self._ultimo_emit = 0.0

    # ---------------------------------------------------- estado
    def _set(self, forcar=True, **kw):
        self.estado = replace(self.estado, **kw)
        agora = time.monotonic()
        if forcar or agora - self._ultimo_emit > 0.15:
            self._ultimo_emit = agora
            self.mudou.emit(self.estado)

    # ---------------------------------------------------- processo
    def _rodar(self, args: list[str], ao_ler=None) -> tuple[int, list[str]]:
        env = dict(os.environ, PYTHONIOENCODING="utf-8", PYTHONUTF8="1")
        self.proc = subprocess.Popen(
            [binario("yt-dlp")] + args,
            stdout=subprocess.PIPE, stderr=subprocess.STDOUT, stdin=subprocess.DEVNULL,
            creationflags=SEM_JANELA, env=env, cwd=str(BIN) if BIN.exists() else None,
            text=True, encoding="utf-8", errors="replace", bufsize=1,
        )
        ultimas: list[str] = []
        assert self.proc.stdout is not None
        for linha in self.proc.stdout:
            linha = linha.rstrip("\r\n")
            ultimas = (ultimas + [linha])[-40:]
            if ao_ler:
                try:
                    ao_ler(linha)
                except Exception:
                    pass
        codigo = self.proc.wait()
        self.proc = None
        return codigo, ultimas

    def _matar(self):
        p = self.proc
        if not p:
            return
        try:
            if WINDOWS:
                # yt-dlp.exe abre processos filhos (python, ffmpeg): mata a árvore toda
                subprocess.run(["taskkill", "/PID", str(p.pid), "/T", "/F"],
                               creationflags=SEM_JANELA, capture_output=True)
            else:
                p.kill()
        except Exception:
            pass

    # ---------------------------------------------------- início
    def iniciar(self):
        def trabalho():
            shutil.rmtree(pasta_dados() / "tmp", ignore_errors=True)
            if not Path(binario("yt-dlp")).exists():
                self._set(mostrar=True, erro=True, etapa="yt-dlp não encontrado. Reinstala o YT Mobile.")
                return
            self._set(iniciado=True, versao=self._versao())
            self._atualizar(automatico=True)
        threading.Thread(target=trabalho, daemon=True).start()

    def _versao(self) -> str:
        try:
            r = subprocess.run([binario("yt-dlp"), "--version"], capture_output=True, text=True,
                               creationflags=SEM_JANELA, timeout=60)
            return r.stdout.strip()
        except Exception:
            return ""

    # ---------------------------------------------------- atualização
    def atualizar(self):
        threading.Thread(target=self._atualizar, args=(False,), daemon=True).start()

    def _atualizar(self, automatico: bool):
        chave = f"ultimaAtualizacao_{VERSAO}"
        agora = time.time()
        if automatico and agora - float(self.cfg.value(chave, 0) or 0) < 24 * 3600:
            return
        if not self.trava.acquire(blocking=False):
            return
        self.atualizando_sozinho = automatico
        try:
            if not automatico:
                self._set(ocupado=True, mostrar=True, erro=False, titulo="", arquivo="",
                          etapa="Atualizando yt-dlp...", progresso=None, detalhe="")
            codigo, saida = self._rodar(["-U"])
            texto = "\n".join(saida)
            v = self._versao()
            if codigo == 0:
                self.cfg.setValue(chave, agora)
            if not automatico:
                if codigo != 0:
                    self._set(ocupado=False, erro=True, etapa="Não deu pra atualizar o yt-dlp.", versao=v)
                elif "up to date" in texto:
                    self._set(ocupado=False, etapa=f"yt-dlp já está na versão mais nova ({v}).", versao=v)
                else:
                    self._set(ocupado=False, etapa=f"yt-dlp atualizado pra {v}.", versao=v)
            else:
                self._set(versao=v)
        except Exception as e:
            if not automatico:
                self._set(ocupado=False, erro=True, etapa=f"Não deu pra atualizar: {e}")
        finally:
            self.atualizando_sozinho = False
            self.trava.release()

    # ---------------------------------------------------- download
    def baixar(self, url: str, formato: str):
        if self.estado.ocupado or not self.estado.iniciado:
            return
        self.cancelado = False
        self._set(ocupado=True, baixando=True, mostrar=True, erro=False, titulo="", arquivo="",
                  etapa="Terminando de atualizar o yt-dlp..." if self.atualizando_sozinho
                  else "Lendo informações do vídeo...",
                  progresso=None, detalhe="")
        threading.Thread(target=self._baixar, args=(url, formato), daemon=True).start()

    def cancelar(self):
        self.cancelado = True
        self._matar()

    def _baixar(self, url: str, formato: str):
        with self.trava:
            tmp = pasta_dados() / "tmp" / uuid.uuid4().hex[:10]
            tmp.mkdir(parents=True, exist_ok=True)
            try:
                if self.cancelado:
                    raise RuntimeError("cancelado")
                args = [
                    "--newline", "--no-colors", "--no-playlist", "--ignore-config",
                    "-o", str(tmp / "%(title).120B.%(ext)s"),
                    "--progress-template",
                    "download:[prog] %(progress.downloaded_bytes)s %(progress.total_bytes)s "
                    "%(progress.total_bytes_estimate)s %(progress.speed)s %(progress.eta)s",
                ]
                ff = BIN / "ffmpeg"
                if ff.exists():
                    args += ["--ffmpeg-location", str(ff)]
                if formato == "mp3":
                    args += ["-f", "ba/b", "-x", "--audio-format", "mp3", "--audio-quality", "192K"]
                else:
                    # qualidade escolhida; se não tiver, a mais próxima. Prefere H.264 + AAC
                    args += ["-f", "bv*+ba/b", "-S", f"res:{formato},vcodec:h264,acodec:m4a",
                             "--merge-output-format", "mp4"]
                args.append(url)

                parte = [0]

                def ler(linha: str):
                    if "Downloading webpage" in linha or "player API JSON" in linha:
                        self._set(etapa="Conectando ao YouTube...", progresso=None)
                    elif "Downloading player" in linha or "Solving JS challenges" in linha:
                        self._set(etapa="Liberando o vídeo...", progresso=None)
                    elif linha.startswith("[download] Destination:"):
                        parte[0] += 1
                        nome = Path(linha.split("Destination:", 1)[1].strip()).name
                        titulo = re.sub(r"\.f\d+$", "", nome.rsplit(".", 1)[0])
                        etapa = ("Baixando áudio..." if formato == "mp3" or parte[0] > 1 else "Baixando vídeo...")
                        self._set(titulo=titulo, etapa=etapa, progresso=0.0, detalhe="")
                    elif linha.startswith("[prog]"):
                        v = linha.split()[1:]
                        num = [float(x) if re.fullmatch(r"[\d.]+", x) else None for x in v] + [None] * 5
                        feito, total, estimado, vel, eta = num[:5]
                        total = total or estimado
                        pct = (feito / total * 100) if (feito is not None and total) else None
                        partes = []
                        if total:
                            partes.append(f"{tamanho(feito or 0)} de {tamanho(total)}")
                        if vel:
                            partes.append(f"{tamanho(vel)}/s")
                        if eta:
                            partes.append(f"faltam {tempo(eta)}")
                        self._set(forcar=False, progresso=pct, detalhe=" · ".join(partes))
                    elif linha.startswith("[Merger]"):
                        self._set(etapa="Juntando vídeo e áudio...", progresso=None, detalhe="")
                    elif linha.startswith("[ExtractAudio]"):
                        self._set(etapa="Convertendo pra MP3...", progresso=None, detalhe="")

                codigo, saida = self._rodar(args, ler)
                if self.cancelado:
                    raise RuntimeError("cancelado")
                if codigo != 0:
                    raise RuntimeError(self._erro(saida))

                arquivos = [p for p in tmp.iterdir()
                            if p.is_file() and p.suffix.lower() in EXT_MIDIA]
                if not arquivos:
                    raise RuntimeError("O download terminou mas nenhum arquivo foi gerado.")
                origem = max(arquivos, key=lambda p: p.stat().st_size)
                destino_dir = pasta_musicas() if origem.suffix.lower() in EXT_AUDIO else pasta_videos()
                destino_dir.mkdir(parents=True, exist_ok=True)
                self._set(etapa="Salvando...", progresso=None, detalhe="")
                destino = nome_livre(destino_dir / origem.name)
                shutil.move(str(origem), str(destino))
                self._set(ocupado=False, baixando=False, titulo=destino.stem,
                          etapa=f"Pronto! Salvo em {destino_dir}", progresso=100.0,
                          detalhe=tamanho(destino.stat().st_size), arquivo=str(destino))
            except Exception as e:
                cancel = self.cancelado
                self._set(ocupado=False, baixando=False, erro=not cancel, progresso=None, detalhe="",
                          etapa="Download cancelado." if cancel else str(e)[:400])
            finally:
                shutil.rmtree(tmp, ignore_errors=True)

    @staticmethod
    def _erro(saida: list[str]) -> str:
        linha = next((l for l in reversed(saida) if "ERROR:" in l), None)
        linha = linha.split("ERROR:", 1)[1].strip() if linha else (saida[-1] if saida else "Erro desconhecido.")
        if "Unsupported URL" in linha:
            return "Link não suportado. Confere se é um link de vídeo."
        if "Private video" in linha:
            return "Esse vídeo é privado."
        if "Sign in to confirm" in linha:
            return "O YouTube pediu verificação. Clica em \"Atualizar yt-dlp\" e tenta de novo."
        if "Video unavailable" in linha:
            return "Vídeo indisponível."
        if "No space left" in linha or "Errno 28" in linha:
            return "Sem espaço no disco."
        if "getaddrinfo" in linha or "Failed to resolve" in linha or "Network is unreachable" in linha:
            return "Sem internet."
        return linha


# ============================================================== miniaturas
class Miniaturas(QObject):
    pronta = Signal(str, str)  # caminho do vídeo, caminho da imagem

    def __init__(self):
        super().__init__()
        self.pasta = pasta_dados() / "miniaturas"
        self.pasta.mkdir(parents=True, exist_ok=True)
        self.fila: list[Path] = []
        self.trava = threading.Lock()
        self.rodando = False

    def caminho(self, video: Path) -> Path:
        st = video.stat()
        h = hashlib.md5(f"{video}|{st.st_mtime}|{st.st_size}".encode()).hexdigest()
        return self.pasta / f"{h}.jpg"

    def pedir(self, videos: list[Path]):
        with self.trava:
            self.fila = list(videos)
            if self.rodando:
                return
            self.rodando = True
        threading.Thread(target=self._loop, daemon=True).start()

    def _loop(self):
        ffmpeg = binario("ffmpeg") if not (BIN / "ffmpeg").exists() else str(
            BIN / "ffmpeg" / ("ffmpeg.exe" if WINDOWS else "ffmpeg"))
        while True:
            with self.trava:
                if not self.fila:
                    self.rodando = False
                    return
                video = self.fila.pop(0)
            try:
                img = self.caminho(video)
                if not img.exists():
                    for inicio in ("5", "0"):
                        subprocess.run(
                            [ffmpeg, "-y", "-loglevel", "error", "-ss", inicio, "-i", str(video),
                             "-frames:v", "1", "-vf", "scale=480:-2", str(img)],
                            creationflags=SEM_JANELA, capture_output=True, timeout=60,
                        )
                        if img.exists() and img.stat().st_size > 0:
                            break
                if img.exists():
                    self.pronta.emit(str(video), str(img))
            except Exception:
                pass


# ============================================================== ícones desenhados
VERMELHO = "#FF3B47"


def icone_app() -> QIcon:
    pm = QPixmap(256, 256)
    pm.fill(Qt.GlobalColor.transparent)
    p = QPainter(pm)
    p.setRenderHint(QPainter.RenderHint.Antialiasing)
    p.setBrush(QColor(VERMELHO))
    p.setPen(Qt.PenStyle.NoPen)
    p.drawRoundedRect(8, 8, 240, 240, 56, 56)
    caneta = QPen(QColor("white"), 22, Qt.PenStyle.SolidLine, Qt.PenCapStyle.RoundCap, Qt.PenJoinStyle.RoundJoin)
    p.setPen(caneta)
    p.drawLine(128, 60, 128, 150)
    p.drawLine(88, 112, 128, 152)
    p.drawLine(168, 112, 128, 152)
    p.drawLine(76, 196, 180, 196)
    p.end()
    return QIcon(pm)


def capa_texto(texto: str, w=480, h=270) -> QPixmap:
    pm = QPixmap(w, h)
    pm.fill(QColor("#1A1A20"))
    p = QPainter(pm)
    p.setPen(QColor("#9A9AA8"))
    f = QFont()
    f.setPixelSize(h // 3)
    p.setFont(f)
    p.drawText(pm.rect(), Qt.AlignmentFlag.AlignCenter, texto)
    p.end()
    return pm


# ============================================================== player
class Player(QWidget):
    def __init__(self, cfg: QSettings, arquivos: list[Path], indice: int):
        super().__init__()
        self.cfg = cfg
        self.lista = arquivos
        self.indice = indice
        self.arrastando = False
        self.setWindowIcon(icone_app())
        self.resize(1000, 620)
        self.setAttribute(Qt.WidgetAttribute.WA_DeleteOnClose)
        self.setStyleSheet("QWidget{background:#000;color:#ECECF1}")

        self.player = QMediaPlayer(self)
        self.saida = QAudioOutput(self)
        self.player.setAudioOutput(self.saida)
        self.saida.setVolume(float(cfg.value("volume", 0.9)))
        if hasattr(self.player, "setPitchCompensation"):
            try:  # mantém a voz natural em velocidades diferentes (Qt 6.10+)
                self.player.setPitchCompensation(True)
            except Exception:
                pass

        self.video = QVideoWidget()
        self.video.setSizePolicy(QSizePolicy.Policy.Expanding, QSizePolicy.Policy.Expanding)
        self.capa_audio = QLabel("♪")
        self.capa_audio.setAlignment(Qt.AlignmentFlag.AlignCenter)
        self.capa_audio.setStyleSheet("font-size:96px;color:#9A9AA8")
        self.palco = QStackedWidget()
        self.palco.addWidget(self.video)
        self.palco.addWidget(self.capa_audio)
        self.player.setVideoOutput(self.video)

        self.titulo = QLabel()
        self.titulo.setStyleSheet("font-size:14px;padding:6px 10px;color:#ECECF1")

        estilo_bt = ("QPushButton{background:#1A1A20;border:none;border-radius:8px;padding:8px 12px;"
                     "font-size:15px;color:#ECECF1}QPushButton:hover{background:#2A2A33}")
        def bt(texto, dica, acao):
            b = QPushButton(texto)
            b.setToolTip(dica)
            b.setStyleSheet(estilo_bt)
            b.setCursor(Qt.CursorShape.PointingHandCursor)
            b.setFocusPolicy(Qt.FocusPolicy.NoFocus)  # espaço controla o play, não o botão
            b.clicked.connect(acao)
            return b

        self.b_ant = bt("⏮", "Anterior", self.anterior)
        self.b_volta = bt("↺ 10", "Voltar 10s (←)", lambda: self.pular(-10))
        self.b_play = bt("⏸", "Play/Pause (Espaço)", self.alternar)
        self.b_frente = bt("10 ↻", "Avançar 10s (→)", lambda: self.pular(10))
        self.b_prox = bt("⏭", "Próximo", self.proximo)
        self.b_tela = bt("⛶", "Tela cheia (F)", self.tela_cheia)

        self.barra = QSlider(Qt.Orientation.Horizontal)
        self.barra.setRange(0, 0)
        self.barra.sliderPressed.connect(lambda: setattr(self, "arrastando", True))
        self.barra.sliderReleased.connect(self._soltou_barra)
        self.barra.setStyleSheet(
            "QSlider::groove:horizontal{height:6px;background:#2A2A33;border-radius:3px}"
            f"QSlider::sub-page:horizontal{{background:{VERMELHO};border-radius:3px}}"
            "QSlider::handle:horizontal{width:14px;margin:-5px 0;border-radius:7px;background:white}")
        self.tempo = QLabel("0:00 / 0:00")
        self.tempo.setStyleSheet("color:#9A9AA8;padding:0 8px")

        self.vel = QComboBox()
        for v in VELOCIDADES:
            self.vel.addItem(f"{v:g}x", v)
        self.vel.setToolTip("Velocidade (< e >)")
        self.vel.setFocusPolicy(Qt.FocusPolicy.NoFocus)
        self.vel.setStyleSheet("QComboBox{background:#1A1A20;border:none;border-radius:8px;padding:7px 10px;"
                               "font-size:14px}QComboBox QAbstractItemView{background:#1A1A20;"
                               f"selection-background-color:{VERMELHO}}}")
        vel_salva = float(cfg.value("velocidade", 1.0))
        self.vel.setCurrentIndex(min(range(len(VELOCIDADES)), key=lambda i: abs(VELOCIDADES[i] - vel_salva)))
        self.vel.currentIndexChanged.connect(self._mudou_velocidade)

        self.volume = QSlider(Qt.Orientation.Horizontal)
        self.volume.setRange(0, 100)
        self.volume.setValue(int(self.saida.volume() * 100))
        self.volume.setFixedWidth(90)
        self.volume.setStyleSheet(self.barra.styleSheet())
        self.volume.setToolTip("Volume (↑ ↓)")
        self.volume.setFocusPolicy(Qt.FocusPolicy.NoFocus)
        self.barra.setFocusPolicy(Qt.FocusPolicy.NoFocus)
        self.volume.valueChanged.connect(self._mudou_volume)

        linha1 = QHBoxLayout()
        linha1.addWidget(self.barra, 1)
        linha1.addWidget(self.tempo)
        linha2 = QHBoxLayout()
        for w in (self.b_ant, self.b_volta, self.b_play, self.b_frente, self.b_prox):
            linha2.addWidget(w)
        linha2.addStretch(1)
        linha2.addWidget(QLabel("🔊"))
        linha2.addWidget(self.volume)
        linha2.addWidget(self.vel)
        linha2.addWidget(self.b_tela)
        self.controles = QWidget()
        lc = QVBoxLayout(self.controles)
        lc.setContentsMargins(10, 6, 10, 10)
        lc.addLayout(linha1)
        lc.addLayout(linha2)

        raiz = QVBoxLayout(self)
        raiz.setContentsMargins(0, 0, 0, 0)
        raiz.setSpacing(0)
        raiz.addWidget(self.titulo)
        raiz.addWidget(self.palco, 1)
        raiz.addWidget(self.controles)

        self.player.positionChanged.connect(self._posicao)
        self.player.durationChanged.connect(lambda d: self.barra.setRange(0, d))
        self.player.playbackStateChanged.connect(self._estado_play)
        self.player.mediaStatusChanged.connect(self._status)
        self.player.errorOccurred.connect(lambda _e, msg: self.titulo.setText(f"Erro: {msg}"))

        atalhos = {
            "Space": self.alternar, "K": self.alternar,
            "Left": lambda: self.pular(-5), "Right": lambda: self.pular(5),
            "J": lambda: self.pular(-10), "L": lambda: self.pular(10),
            "Up": lambda: self.volume.setValue(self.volume.value() + 5),
            "Down": lambda: self.volume.setValue(self.volume.value() - 5),
            "F": self.tela_cheia, "Escape": self.sair_tela_cheia,
            ">": lambda: self.vel.setCurrentIndex(min(self.vel.currentIndex() + 1, self.vel.count() - 1)),
            "<": lambda: self.vel.setCurrentIndex(max(self.vel.currentIndex() - 1, 0)),
            "N": self.proximo, "P": self.anterior,
        }
        for tecla, acao in atalhos.items():
            QShortcut(QKeySequence(tecla), self, activated=acao)
        self.video.mouseDoubleClickEvent = lambda _e: self.tela_cheia()

        self.tocar(self.indice)

    # ---------------------------------------------------- reprodução
    def tocar(self, i: int):
        if not self.lista:
            return
        self.indice = max(0, min(i, len(self.lista) - 1))
        arq = self.lista[self.indice]
        audio = arq.suffix.lower() in EXT_AUDIO
        self.palco.setCurrentWidget(self.capa_audio if audio else self.video)
        self.capa_audio.setText(f"♪\n\n{arq.stem}" if audio else "♪")
        self.capa_audio.setStyleSheet("font-size:28px;color:#9A9AA8" if audio else "")
        self.titulo.setText(arq.stem)
        self.setWindowTitle(f"{arq.stem} — {APP}")
        self.player.setSource(QUrl.fromLocalFile(str(arq)))
        self.player.setPlaybackRate(self.vel.currentData())
        self.player.play()
        self.b_ant.setEnabled(self.indice > 0)
        self.b_prox.setEnabled(self.indice < len(self.lista) - 1)

    def alternar(self):
        if self.player.playbackState() == QMediaPlayer.PlaybackState.PlayingState:
            self.player.pause()
        else:
            self.player.play()

    def pular(self, seg: int):
        pos = max(0, min(self.player.position() + seg * 1000, self.player.duration()))
        self.player.setPosition(pos)

    def proximo(self):
        if self.indice < len(self.lista) - 1:
            self.tocar(self.indice + 1)

    def anterior(self):
        if self.player.position() > 5000:
            self.player.setPosition(0)
        elif self.indice > 0:
            self.tocar(self.indice - 1)

    def tela_cheia(self):
        if self.isFullScreen():
            self.sair_tela_cheia()
        else:
            self.titulo.hide()
            self.showFullScreen()

    def sair_tela_cheia(self):
        if self.isFullScreen():
            self.titulo.show()
            self.showNormal()

    # ---------------------------------------------------- sinais
    def _posicao(self, pos: int):
        if not self.arrastando:
            self.barra.setValue(pos)
        self.tempo.setText(f"{tempo(pos / 1000)} / {tempo(self.player.duration() / 1000)}")

    def _soltou_barra(self):
        self.arrastando = False
        self.player.setPosition(self.barra.value())

    def _estado_play(self, st):
        self.b_play.setText("⏸" if st == QMediaPlayer.PlaybackState.PlayingState else "▶")

    def _status(self, st):
        if st == QMediaPlayer.MediaStatus.EndOfMedia:
            self.proximo()

    def _mudou_velocidade(self, _i):
        v = self.vel.currentData()
        self.player.setPlaybackRate(v)
        self.cfg.setValue("velocidade", v)

    def _mudou_volume(self, v: int):
        self.saida.setVolume(v / 100)
        self.cfg.setValue("volume", v / 100)

    def closeEvent(self, e):
        self.player.stop()
        super().closeEvent(e)


# ============================================================== janela principal
ESTILO = f"""
QMainWindow, QWidget {{ background:#0F0F12; color:#ECECF1; font-size:14px; }}
QTabWidget::pane {{ border:none; }}
QTabBar::tab {{ background:transparent; color:#9A9AA8; padding:10px 18px; font-size:15px; border:none; }}
QTabBar::tab:selected {{ color:#ECECF1; border-bottom:3px solid {VERMELHO}; }}
QLineEdit {{ background:#1A1A20; border:1px solid #2A2A33; border-radius:10px; padding:12px; font-size:15px; }}
QLineEdit:focus {{ border-color:{VERMELHO}; }}
QPushButton {{ background:#1A1A20; border:1px solid #2A2A33; border-radius:10px; padding:10px 16px; }}
QPushButton:hover {{ background:#22222A; }}
QPushButton:checked {{ border-color:{VERMELHO}; background:#3A1A1E; }}
QPushButton:disabled {{ color:#5A5A66; }}
QPushButton#principal {{ background:{VERMELHO}; border:none; color:white; font-size:17px; font-weight:600; padding:14px; }}
QPushButton#principal:hover {{ background:#FF5560; }}
QPushButton#principal:disabled {{ background:#5A2A2E; color:#C8A0A4; }}
QPushButton#link {{ background:transparent; border:none; color:{VERMELHO}; padding:6px; }}
QFrame#card {{ background:#1A1A20; border-radius:14px; }}
QFrame#card QLabel, QFrame#card QWidget {{ background:transparent; }}
QProgressBar {{ background:#0F0F12; border:none; border-radius:5px; height:10px; text-align:center; color:transparent; }}
QProgressBar::chunk {{ background:{VERMELHO}; border-radius:5px; }}
QListWidget {{ background:#0F0F12; border:none; outline:none; }}
QListWidget::item {{ border-radius:10px; padding:6px; color:#ECECF1; }}
QListWidget::item:selected, QListWidget::item:hover {{ background:#1A1A20; }}
QMenu {{ background:#1A1A20; border:1px solid #2A2A33; }}
QMenu::item:selected {{ background:{VERMELHO}; }}
QToolTip {{ background:#1A1A20; color:#ECECF1; border:1px solid #2A2A33; }}
"""


class Janela(QMainWindow):
    def __init__(self):
        super().__init__()
        self.cfg = QSettings("vaiserk", APP)
        self.motor = Motor(self.cfg)
        self.motor.mudou.connect(self.render)
        self.miniaturas = Miniaturas()
        self.miniaturas.pronta.connect(self._miniatura_pronta)
        self.players: list[Player] = []

        self.setWindowTitle(APP)
        self.setWindowIcon(icone_app())
        self.resize(760, 640)
        self.setStyleSheet(ESTILO)

        abas = QTabWidget()
        abas.addTab(self._aba_baixar(), "Baixar")
        abas.addTab(self._aba_galeria(), "Galeria")
        abas.currentChanged.connect(lambda i: self.carregar_galeria() if i == 1 else None)
        self.abas = abas
        self.setCentralWidget(abas)

        self.motor.iniciar()
        self.render(self.motor.estado)

    # ---------------------------------------------------- aba Baixar
    def _aba_baixar(self) -> QWidget:
        w = QWidget()
        v = QVBoxLayout(w)
        v.setContentsMargins(24, 20, 24, 16)
        v.setSpacing(12)

        topo = QLabel("▶  YT Mobile")
        topo.setStyleSheet("font-size:26px;font-weight:700")
        v.addWidget(topo)

        linha = QHBoxLayout()
        self.url = QLineEdit()
        self.url.setPlaceholderText("Cola o link do vídeo aqui")
        self.url.setClearButtonEnabled(True)
        self.url.returnPressed.connect(self.baixar)
        colar = QPushButton("Colar")
        colar.clicked.connect(self.colar)
        linha.addWidget(self.url, 1)
        linha.addWidget(colar)
        v.addLayout(linha)

        rot = QLabel("Formato")
        rot.setStyleSheet("color:#9A9AA8;margin-top:6px")
        v.addWidget(rot)
        fmts = QHBoxLayout()
        self.grupo = QButtonGroup(self)
        salvo = str(self.cfg.value("formato", "720"))
        for chave, texto in (("1080", "1080p"), ("720", "720p"), ("480", "480p"), ("mp3", "MP3")):
            b = QPushButton(texto)
            b.setCheckable(True)
            b.setProperty("chave", chave)
            b.setChecked(chave == salvo)
            self.grupo.addButton(b)
            fmts.addWidget(b, 1)
        if not self.grupo.checkedButton():
            self.grupo.buttons()[1].setChecked(True)
        self.grupo.buttonClicked.connect(lambda b: self.cfg.setValue("formato", b.property("chave")))
        v.addLayout(fmts)

        self.b_baixar = QPushButton("Preparando...")
        self.b_baixar.setObjectName("principal")
        self.b_baixar.setCursor(Qt.CursorShape.PointingHandCursor)
        self.b_baixar.clicked.connect(self.baixar)
        v.addSpacing(8)
        v.addWidget(self.b_baixar)

        self.card = QFrame()
        self.card.setObjectName("card")
        c = QVBoxLayout(self.card)
        c.setContentsMargins(16, 14, 16, 14)
        self.l_titulo = QLabel()
        self.l_titulo.setWordWrap(True)
        self.l_titulo.setStyleSheet("font-size:15px;font-weight:600")
        self.l_etapa = QLabel()
        self.l_etapa.setWordWrap(True)
        self.barra = QProgressBar()
        self.barra.setFixedHeight(10)
        self.l_detalhe = QLabel()
        self.l_detalhe.setStyleSheet("color:#9A9AA8;font-size:12px")
        acoes = QHBoxLayout()
        self.b_abrir = QPushButton("▶  Assistir")
        self.b_abrir.clicked.connect(self.abrir_ultimo)
        self.b_pasta = QPushButton("Mostrar na pasta")
        self.b_pasta.clicked.connect(lambda: abrir_no_explorer(Path(self.motor.estado.arquivo)))
        self.b_cancelar = QPushButton("Cancelar")
        self.b_cancelar.clicked.connect(self.motor.cancelar)
        for b in (self.b_abrir, self.b_pasta, self.b_cancelar):
            acoes.addWidget(b)
        acoes.addStretch(1)
        for x in (self.l_titulo, self.l_etapa, self.barra, self.l_detalhe):
            c.addWidget(x)
        c.addLayout(acoes)
        v.addSpacing(6)
        v.addWidget(self.card)
        v.addStretch(1)

        rodape = QHBoxLayout()
        self.l_versao = QLabel()
        self.l_versao.setStyleSheet("color:#9A9AA8;font-size:12px")
        self.b_atualizar = QPushButton("Atualizar yt-dlp")
        self.b_atualizar.setObjectName("link")
        self.b_atualizar.clicked.connect(self.motor.atualizar)
        rodape.addWidget(self.l_versao, 1)
        rodape.addWidget(self.b_atualizar)
        v.addLayout(rodape)
        return w

    # ---------------------------------------------------- aba Galeria
    def _aba_galeria(self) -> QWidget:
        w = QWidget()
        v = QVBoxLayout(w)
        v.setContentsMargins(16, 12, 16, 12)
        topo = QHBoxLayout()
        self.l_contagem = QLabel()
        self.l_contagem.setStyleSheet("color:#9A9AA8")
        b_pasta = QPushButton("Abrir pasta de vídeos")
        b_pasta.clicked.connect(lambda: QDesktopServices.openUrl(QUrl.fromLocalFile(str(self._garantir(pasta_videos())))))
        b_rec = QPushButton("Atualizar")
        b_rec.clicked.connect(self.carregar_galeria)
        topo.addWidget(self.l_contagem, 1)
        topo.addWidget(b_rec)
        topo.addWidget(b_pasta)
        v.addLayout(topo)

        self.lista = QListWidget()
        self.lista.setViewMode(QListWidget.ViewMode.IconMode)
        self.lista.setIconSize(QSize(224, 126))
        self.lista.setGridSize(QSize(244, 200))
        self.lista.setResizeMode(QListWidget.ResizeMode.Adjust)
        self.lista.setMovement(QListWidget.Movement.Static)
        self.lista.setWordWrap(True)
        self.lista.setUniformItemSizes(True)
        self.lista.setSpacing(4)
        self.lista.itemDoubleClicked.connect(self._tocar_item)
        self.lista.setContextMenuPolicy(Qt.ContextMenuPolicy.CustomContextMenu)
        self.lista.customContextMenuRequested.connect(self._menu_item)
        self.vazio = QLabel("Nada baixado ainda.\nO que você baixar aparece aqui.")
        self.vazio.setAlignment(Qt.AlignmentFlag.AlignCenter)
        self.vazio.setStyleSheet("color:#9A9AA8;font-size:15px")
        v.addWidget(self.lista, 1)
        v.addWidget(self.vazio, 1)
        dica = QLabel("Duplo clique pra assistir · botão direito pra mais opções")
        dica.setStyleSheet("color:#5A5A66;font-size:12px")
        v.addWidget(dica)
        return w

    @staticmethod
    def _garantir(p: Path) -> Path:
        p.mkdir(parents=True, exist_ok=True)
        return p

    def arquivos_galeria(self) -> list[Path]:
        arqs = []
        for pasta in (pasta_videos(), pasta_musicas()):
            if pasta.exists():
                arqs += [p for p in pasta.iterdir() if p.is_file() and p.suffix.lower() in EXT_MIDIA]
        return sorted(arqs, key=lambda p: p.stat().st_mtime, reverse=True)

    def carregar_galeria(self):
        arqs = self.arquivos_galeria()
        self.lista.clear()
        self._itens: dict[str, QListWidgetItem] = {}
        capa_video, capa_audio = QIcon(capa_texto("▶")), QIcon(capa_texto("♪"))
        videos = []
        for arq in arqs:
            audio = arq.suffix.lower() in EXT_AUDIO
            texto = arq.stem if len(arq.stem) <= 70 else arq.stem[:67] + "..."
            item = QListWidgetItem(texto)
            item.setData(Qt.ItemDataRole.UserRole, str(arq))
            item.setToolTip(f"{arq.name}\n{tamanho(arq.stat().st_size)}")
            item.setTextAlignment(Qt.AlignmentFlag.AlignHCenter | Qt.AlignmentFlag.AlignTop)
            img = None if audio else self.miniaturas.caminho(arq)
            if img is not None and img.exists():
                item.setIcon(QIcon(str(img)))
            else:
                item.setIcon(capa_audio if audio else capa_video)
                if not audio:
                    videos.append(arq)
            self.lista.addItem(item)
            self._itens[str(arq)] = item
        self.lista.setVisible(bool(arqs))
        self.vazio.setVisible(not arqs)
        self.l_contagem.setText(f"{len(arqs)} arquivo(s)" if arqs else "")
        if videos:
            self.miniaturas.pedir(videos)

    def _miniatura_pronta(self, video: str, img: str):
        item = getattr(self, "_itens", {}).get(video)
        if item is not None:
            item.setIcon(QIcon(img))

    def _tocar_item(self, item: QListWidgetItem):
        arqs = self.arquivos_galeria()
        alvo = Path(item.data(Qt.ItemDataRole.UserRole))
        idx = next((i for i, a in enumerate(arqs) if a == alvo), 0)
        self.abrir_player(arqs, idx)

    def _menu_item(self, pos):
        item = self.lista.itemAt(pos)
        if not item:
            return
        arq = Path(item.data(Qt.ItemDataRole.UserRole))
        m = QMenu(self)
        m.addAction("Assistir", lambda: self._tocar_item(item))
        m.addAction("Mostrar na pasta", lambda: abrir_no_explorer(arq))
        m.addAction("Copiar caminho", lambda: QGuiApplication.clipboard().setText(str(arq)))
        m.addSeparator()
        m.addAction("Apagar (vai pra Lixeira)", lambda: self._apagar(arq))
        m.exec(self.lista.mapToGlobal(pos))

    def _apagar(self, arq: Path):
        r = QMessageBox.question(self, APP, f"Mandar pra Lixeira?\n\n{arq.name}")
        if r != QMessageBox.StandardButton.Yes:
            return
        from PySide6.QtCore import QFile
        if not QFile.moveToTrash(str(arq)):
            try:
                arq.unlink()
            except Exception as e:
                QMessageBox.warning(self, APP, f"Não deu pra apagar:\n{e}")
        self.carregar_galeria()

    # ---------------------------------------------------- ações
    def colar(self):
        texto = QGuiApplication.clipboard().text().strip()
        if texto:
            m = re.search(r"https?://\S+", texto)
            self.url.setText(m.group(0) if m else texto)

    def baixar(self):
        url = self.url.text().strip()
        m = re.search(r"https?://\S+", url)
        if not m:
            self.url.setFocus()
            self.url.setPlaceholderText("Cola um link primeiro (começando com http)")
            return
        fmt = self.grupo.checkedButton().property("chave")
        self.motor.baixar(m.group(0), fmt)

    def abrir_ultimo(self):
        arq = Path(self.motor.estado.arquivo)
        if arq.exists():
            arqs = self.arquivos_galeria()
            idx = next((i for i, a in enumerate(arqs) if a == arq), 0)
            self.abrir_player(arqs or [arq], idx)

    def abrir_player(self, arqs: list[Path], idx: int):
        p = Player(self.cfg, arqs, idx)
        self.players = [x for x in self.players if x.isVisible()] + [p]
        p.show()

    def changeEvent(self, e):
        # Ao voltar pra janela: se tiver link de vídeo copiado e o campo estiver vazio, já cola
        super().changeEvent(e)
        if e.type() == QEvent.Type.ActivationChange and self.isActiveWindow() and not self.url.text().strip():
            t = QGuiApplication.clipboard().text().strip()
            if re.match(r"https?://(www\.|m\.|music\.)?(youtube\.com|youtu\.be)/", t):
                self.url.setText(t.split()[0])

    # ---------------------------------------------------- render
    def render(self, e: Estado):
        self.b_baixar.setEnabled(e.iniciado and not e.ocupado)
        self.b_baixar.setText("Preparando..." if not e.iniciado else
                              "Baixando..." if e.baixando else "Aguarde..." if e.ocupado else "Baixar")
        self.b_atualizar.setEnabled(e.iniciado and not e.ocupado)
        self.l_versao.setText(f"yt-dlp {e.versao}  ·  app {VERSAO}" if e.versao else f"app {VERSAO}")
        self.card.setVisible(e.mostrar)
        self.l_titulo.setText(e.titulo)
        self.l_titulo.setVisible(bool(e.titulo))
        self.l_etapa.setText(e.etapa)
        self.l_etapa.setStyleSheet("color:#FF7B84" if e.erro else "color:#9A9AA8")
        self.barra.setVisible(e.ocupado)
        if e.progresso is None:
            self.barra.setRange(0, 0)
        else:
            self.barra.setRange(0, 1000)
            self.barra.setValue(int(e.progresso * 10))
        self.l_detalhe.setText(e.detalhe)
        self.l_detalhe.setVisible(bool(e.detalhe))
        self.b_cancelar.setVisible(e.baixando)
        pronto = bool(e.arquivo) and not e.ocupado
        self.b_abrir.setVisible(pronto)
        self.b_pasta.setVisible(pronto)

    def closeEvent(self, ev):
        if self.motor.estado.baixando:
            r = QMessageBox.question(self, APP, "Tem um download em andamento. Fechar mesmo assim?")
            if r != QMessageBox.StandardButton.Yes:
                ev.ignore()
                return
            self.motor.cancelar()
        for p in self.players:
            p.close()
        super().closeEvent(ev)


def main():
    if WINDOWS:
        try:  # ícone certo na barra de tarefas
            import ctypes
            ctypes.windll.shell32.SetCurrentProcessExplicitAppUserModelID("vaiserk.ytmobile")
        except Exception:
            pass
    app = QApplication(sys.argv)
    app.setApplicationName(APP)
    app.setOrganizationName("vaiserk")
    app.setWindowIcon(icone_app())
    j = Janela()
    j.show()

    teste = os.environ.get("YTM_TESTE")
    if teste:  # usado no GitHub Actions: abre, espera o motor iniciar e grava o resultado
        inicio = time.time()

        def checar():
            e = j.motor.estado
            if e.iniciado or e.erro or time.time() - inicio > 60:
                Path(teste).write_text(
                    f"iniciado={e.iniciado} erro={e.erro} versao={e.versao} etapa={e.etapa}\n"
                    f"ytdlp={binario('yt-dlp')}\nvideos={pasta_videos()}\n", encoding="utf-8")
                app.quit()
        t = QTimer(j)
        t.timeout.connect(checar)
        t.start(500)
    sys.exit(app.exec())


if __name__ == "__main__":
    main()
