"""Gera icone.ico (quadrado vermelho com seta de download) para o .exe e o instalador."""
from PIL import Image, ImageDraw

T = 1024
img = Image.new("RGBA", (T, T), (0, 0, 0, 0))
d = ImageDraw.Draw(img)
d.rounded_rectangle((32, 32, T - 32, T - 32), radius=224, fill=(255, 59, 71, 255))
branco = (255, 255, 255, 255)
L = 88
d.line((512, 240, 512, 600), fill=branco, width=L)
d.line((352, 448, 512, 608), fill=branco, width=L, joint="curve")
d.line((672, 448, 512, 608), fill=branco, width=L, joint="curve")
d.line((304, 784, 720, 784), fill=branco, width=L)
for x, y in ((512, 240), (352, 448), (672, 448), (512, 608), (304, 784), (720, 784)):
    d.ellipse((x - L // 2, y - L // 2, x + L // 2, y + L // 2), fill=branco)
img.save("icone.ico", sizes=[(16, 16), (24, 24), (32, 32), (48, 48), (64, 64), (128, 128), (256, 256)])
print("icone.ico gerado")
