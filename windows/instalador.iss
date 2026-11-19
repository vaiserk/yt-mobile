; Instalador do YT Mobile para Windows (Inno Setup 6)
; Compilado pelo GitHub Actions: ISCC /DAppVer=1.0.N instalador.iss

#ifndef AppVer
  #define AppVer "1.0.0"
#endif

[Setup]
AppId={{A9FF22B4-66BA-44A8-814F-4D0C526C0C4F}
AppName=YT Mobile
AppVersion={#AppVer}
AppPublisher=vaiserk
AppPublisherURL=https://github.com/vaiserk/yt-mobile
; Instala na pasta do usuário: não pede senha de administrador
; e deixa o yt-dlp se atualizar sozinho
PrivilegesRequired=lowest
DefaultDirName={localappdata}\Programs\YT Mobile
DisableProgramGroupPage=yes
DisableDirPage=yes
OutputDir=saida
OutputBaseFilename=yt-mobile-windows-setup
SetupIconFile=icone.ico
UninstallDisplayIcon={app}\YT Mobile.exe
Compression=lzma2/ultra64
SolidCompression=yes
WizardStyle=modern
ArchitecturesAllowed=x64compatible
ArchitecturesInstallIn64BitMode=x64compatible
CloseApplications=yes

[Languages]
Name: "pt"; MessagesFile: "compiler:Languages\BrazilianPortuguese.isl"

[Tasks]
Name: "desktopicon"; Description: "Criar atalho na Área de Trabalho"; GroupDescription: "Atalhos:"

[Files]
Source: "dist\YT Mobile\*"; DestDir: "{app}"; Flags: ignoreversion recursesubdirs createallsubdirs
Source: "bin\*"; DestDir: "{app}\bin"; Flags: ignoreversion recursesubdirs createallsubdirs

[Icons]
Name: "{autoprograms}\YT Mobile"; Filename: "{app}\YT Mobile.exe"
Name: "{autodesktop}\YT Mobile"; Filename: "{app}\YT Mobile.exe"; Tasks: desktopicon

[Run]
Filename: "{app}\YT Mobile.exe"; Description: "Abrir o YT Mobile"; Flags: nowait postinstall skipifsilent

[UninstallDelete]
Type: filesandordirs; Name: "{app}\bin"
