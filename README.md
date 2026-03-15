# BartokPlayerWin

Windows console application that streams and plays [Bartók Rádió](https://www.mr3-bartok.hu/) (Hungarian classical music radio) with automatic reconnection on connection loss.

## Requirements

- .NET 9 runtime

## Build & Run

```bash
dotnet build BartokPlayer/
dotnet run --project BartokPlayer/
```

## Publish

```bash
dotnet publish BartokPlayer/ -c Release -r win-x64 --self-contained false -p:PublishSingleFile=true -o publish
```

Produces a single-file exe at `publish/BartokPlayer.exe`.
