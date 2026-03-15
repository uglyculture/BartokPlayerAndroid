@echo off
powershell -Command "$ws = New-Object -ComObject WScript.Shell; $sc = $ws.CreateShortcut([System.IO.Path]::Combine([Environment]::GetFolderPath('Desktop'), 'Bartok Radio.lnk')); $sc.TargetPath = '%~dp0publish\BartokPlayer.exe'; $sc.WorkingDirectory = '%~dp0publish'; $sc.Description = 'Bartok Radio Player'; $sc.Save()"
echo Shortcut created on Desktop.
pause
