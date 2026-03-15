# BartokPlayerWin

## Project Overview
Windows console application that streams and plays Bartók Rádió (Hungarian classical music radio) with automatic reconnection on connection loss.

## Tech Stack
- C# / .NET 9 console app
- NAudio 2.3.0 for MP3 stream decoding and audio playback
- Published as single-file exe (`publish/BartokPlayer.exe`)

## Stream URLs
- Stored in `BartokPlayer/streams.txt` (one URL per line, `#` for comments)
- Player tries each URL in order; falls back to the next on failure

## Project Structure
- `BartokPlayer/` — .NET project source (Program.cs, streams.txt)
- `publish/` — published single-file exe + streams.txt
- `create_shortcut.ps1` — creates a "Bartok Radio" desktop shortcut

## Build & Publish
```bash
dotnet build BartokPlayer/
dotnet publish BartokPlayer/ -c Release -r win-x64 --self-contained false -p:PublishSingleFile=true -o publish
```

## Notes
- .NET 9 SDK required (installed via `winget install Microsoft.DotNet.SDK.9`)
- Not self-contained — requires .NET 9 runtime on the machine
- The `dotnet` command is at `C:\Program Files\dotnet\dotnet.exe` (may need to add to PATH in bash: `export PATH="$PATH:/c/Program Files/dotnet"`)
