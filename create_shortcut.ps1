$ws = New-Object -ComObject WScript.Shell
$desktop = [Environment]::GetFolderPath('Desktop')
$sc = $ws.CreateShortcut("$desktop\Bartok Radio.lnk")
$sc.TargetPath = "C:\Users\arche\Dropbox\repos\BartokPlayerWin\publish\BartokPlayer.exe"
$sc.WorkingDirectory = "C:\Users\arche\Dropbox\repos\BartokPlayerWin\publish"
$sc.Description = "Bartok Radio Player"
$sc.Save()
Write-Host "Shortcut created on Desktop."
