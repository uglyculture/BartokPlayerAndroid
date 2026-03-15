# BartokPlayerWin

## Project Overview
Windows console application that streams and plays Bartók Rádió (Hungarian classical music radio) with automatic reconnection on connection loss.

## Tech Stack
- C# / .NET 9 console app
- NAudio 2.3.0 for MP3 stream decoding and audio playback
- Published as single-file exe (`publish/BartokPlayer.exe`)

## Stream URL
- `http://icast.connectmedia.hu/4742/mr3hq.mp3`

## Project Structure
- `BartokPlayer/` — .NET project source (Program.cs)
- `publish/` — published single-file exe
- `create_shortcut.ps1` — creates a "Bartok Radio" desktop shortcut
- `bartok_downloader.py` — legacy Python script (downloads + uploads to Dropbox, not used by the player)

## Build & Publish
```bash
dotnet build BartokPlayer/
dotnet publish BartokPlayer/ -c Release -r win-x64 --self-contained false -p:PublishSingleFile=true -o publish
```

## Notes
- .NET 9 SDK required (installed via `winget install Microsoft.DotNet.SDK.9`)
- Not self-contained — requires .NET 9 runtime on the machine
- The `dotnet` command is at `C:\Program Files\dotnet\dotnet.exe` (may need to add to PATH in bash: `export PATH="$PATH:/c/Program Files/dotnet"`)
