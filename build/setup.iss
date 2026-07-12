#define MyAppName "YShell"
#ifndef MyAppVersion
#define MyAppVersion "0.0.0"
#endif
#define MyAppPublisher "YShell"
#define MyAppExeName "YShell.exe"
#define MyAppIcon "..\src\main\resources\logo\yshell.ico"
#define MyAppImageDir "..\target\installer\YShell"

[Setup]
AppId={{A1B2C3D4-E5F6-7890-1234-567890ABCDEF}
AppName={#MyAppName}
AppVersion={#MyAppVersion}
AppPublisher={#MyAppPublisher}
DefaultDirName=D:\YShell
DefaultGroupName={#MyAppName}
DisableProgramGroupPage=yes
OutputDir=..\target\installer
OutputBaseFilename=YShell-{#MyAppVersion}-setup
Compression=lzma2
SolidCompression=yes
WizardStyle=modern
SetupIconFile={#MyAppIcon}

; 生成卸载程序,但不写入系统卸载列表
Uninstallable=yes
CreateUninstallRegKey=no
UninstallDisplayName={#MyAppName}
UninstallDisplayIcon={app}\{#MyAppExeName}

[Languages]
Name: "chinesesimplified"; MessagesFile: "compiler:Languages\ChineseSimplified.isl"

[Files]
Source: "{#MyAppImageDir}\YShell.exe"; DestDir: "{app}"; Flags: ignoreversion
Source: "{#MyAppImageDir}\app\*"; DestDir: "{app}\app"; Flags: ignoreversion recursesubdirs createallsubdirs
Source: "{#MyAppImageDir}\updater\*"; DestDir: "{app}\updater"; Flags: ignoreversion recursesubdirs createallsubdirs
Source: "{#MyAppImageDir}\runtime\*"; DestDir: "{app}\runtime"; Flags: ignoreversion recursesubdirs createallsubdirs

[Icons]
Name: "{group}\{#MyAppName}"; Filename: "{app}\{#MyAppExeName}"; IconFilename: "{app}\{#MyAppExeName}"
Name: "{group}\卸载 {#MyAppName}"; Filename: "{uninstallexe}"
Name: "{autodesktop}\{#MyAppName}"; Filename: "{app}\{#MyAppExeName}"; IconFilename: "{app}\{#MyAppExeName}"; Tasks: desktopicon

[Tasks]
Name: "desktopicon"; Description: "{cm:CreateDesktopIcon}"; GroupDescription: "{cm:AdditionalIcons}";

[Run]
Filename: "{app}\{#MyAppExeName}"; Description: "{cm:LaunchProgram,{#StringChange(MyAppName, '&', '&&')}}"; Flags: nowait postinstall skipifsilent

[UninstallDelete]
Type: filesandordirs; Name: "{app}"
