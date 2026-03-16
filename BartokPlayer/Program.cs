using System;
using System.IO;
using System.Net.Http;
using System.Text.RegularExpressions;
using System.Threading;
using System.Xml.Linq;
using NAudio.Wave;

const int ReconnectDelaySec = 3;

var streamsFile = Path.Combine(AppContext.BaseDirectory, "streams.txt");
if (!File.Exists(streamsFile))
{
    Console.WriteLine($"Error: streams.txt not found at {streamsFile}");
    return;
}

var streamUrls = File.ReadAllLines(streamsFile)
    .Where(l => !string.IsNullOrWhiteSpace(l) && !l.TrimStart().StartsWith('#'))
    .ToArray();

if (streamUrls.Length == 0)
{
    Console.WriteLine("Error: no stream URLs found in streams.txt");
    return;
}

Console.WriteLine("Bartok Radio Player");
Console.WriteLine($"Loaded {streamUrls.Length} stream(s) from streams.txt");
Console.WriteLine("Press Ctrl+C to quit");
Console.WriteLine();

using var cts = new CancellationTokenSource();
Console.CancelKeyPress += (_, e) => { e.Cancel = true; cts.Cancel(); };

// Fetch and display schedule immediately, then refresh in background
var schedule = new ProgramSchedule();
await schedule.FetchAndDisplayAsync(cts.Token);
_ = Task.Run(() => schedule.RunAsync(cts.Token));

while (!cts.Token.IsCancellationRequested)
{
    foreach (var url in streamUrls)
    {
        if (cts.Token.IsCancellationRequested) break;

        IMp3FrameDecompressor? decompressor = null;
        BufferedWaveProvider? bufferedProvider = null;
        WaveOutEvent? waveOut = null;

        try
        {
            Console.WriteLine($"[{DateTime.Now:HH:mm:ss}] Connecting to {url}");

            using var client = new HttpClient();
            client.Timeout = Timeout.InfiniteTimeSpan;
            using var connectCts = CancellationTokenSource.CreateLinkedTokenSource(cts.Token);
            connectCts.CancelAfter(TimeSpan.FromSeconds(10));
            using var response = await client.GetAsync(url, HttpCompletionOption.ResponseHeadersRead, connectCts.Token);
            response.EnsureSuccessStatusCode();
            connectCts.CancelAfter(Timeout.InfiniteTimeSpan);

            await using var stream = await response.Content.ReadAsStreamAsync(cts.Token);
            var readFullyStream = new ReadFullyStream(stream);

            while (!cts.Token.IsCancellationRequested)
            {
                Mp3Frame? frame;
                try
                {
                    frame = Mp3Frame.LoadFromStream(readFullyStream);
                }
                catch (Exception ex)
                {
                    Console.WriteLine($"[{DateTime.Now:HH:mm:ss}] Frame error: {ex.Message}");
                    break;
                }

                if (frame == null)
                    break;

                if (decompressor == null)
                {
                    Console.WriteLine($"[{DateTime.Now:HH:mm:ss}] Stream: {frame.BitRate / 1000} kbps, {frame.SampleRate} Hz, {frame.ChannelMode}, MPEG {frame.MpegVersion} Layer {frame.MpegLayer}");
                    var waveFormat = new Mp3WaveFormat(frame.SampleRate,
                        frame.ChannelMode == ChannelMode.Mono ? 1 : 2,
                        frame.FrameLength, frame.BitRate);
                    decompressor = new AcmMp3FrameDecompressor(waveFormat);
                    bufferedProvider = new BufferedWaveProvider(decompressor.OutputFormat)
                    {
                        BufferDuration = TimeSpan.FromSeconds(30),
                        DiscardOnBufferOverflow = true
                    };
                    Console.WriteLine($"[{DateTime.Now:HH:mm:ss}] Playing.");
                    waveOut = new WaveOutEvent();
                    waveOut.Init(bufferedProvider);
                    waveOut.Play();
                }

                var decompressed = new byte[16384];
                int decompressedBytes = decompressor.DecompressFrame(frame, decompressed, 0);
                bufferedProvider!.AddSamples(decompressed, 0, decompressedBytes);
            }

            Console.WriteLine($"[{DateTime.Now:HH:mm:ss}] Stream ended.");
            break;
        }
        catch (OperationCanceledException) when (cts.Token.IsCancellationRequested)
        {
            break;
        }
        catch (Exception ex)
        {
            Console.WriteLine($"[{DateTime.Now:HH:mm:ss}] Failed: {ex.Message}");
        }
        finally
        {
            waveOut?.Stop();
            waveOut?.Dispose();
            decompressor?.Dispose();
        }
    }

    if (!cts.Token.IsCancellationRequested)
    {
        Console.WriteLine($"[{DateTime.Now:HH:mm:ss}] Reconnecting in {ReconnectDelaySec}s...");
        try { await Task.Delay(ReconnectDelaySec * 1000, cts.Token); } catch { break; }
    }
}

Console.WriteLine("Stopped.");

// --- Program schedule ---

class ProgramInfo
{
    public string Title { get; set; } = "";
    public DateTime Start { get; set; }
    public DateTime End { get; set; }
    public string Description { get; set; } = "";
}

class ProgramSchedule
{
    private const string ScheduleUrlBase = "https://mediaklikk.hu/iface/broadcast/{0}/broadcast_12.xml";
    private const int RefreshMinutes = 5;
    private List<ProgramInfo> _programs = new();
    private string? _lastDisplayedTitle;

    public async Task FetchAndDisplayAsync(CancellationToken ct)
    {
        try
        {
            await FetchScheduleAsync(ct);
        }
        catch (Exception ex)
        {
            Console.WriteLine($"[{DateTime.Now:HH:mm:ss}] Schedule fetch error: {ex.Message}");
        }
        DisplayCurrentProgram();
    }

    public async Task RunAsync(CancellationToken ct)
    {
        while (!ct.IsCancellationRequested)
        {
            try { await Task.Delay(TimeSpan.FromMinutes(RefreshMinutes), ct); }
            catch { break; }

            await FetchAndDisplayAsync(ct);
        }
    }

    private async Task FetchScheduleAsync(CancellationToken ct)
    {
        using var client = new HttpClient();
        client.Timeout = TimeSpan.FromSeconds(15);

        var today = DateTime.Now.ToString("yyyy-MM-dd");
        var url = string.Format(ScheduleUrlBase, today);
        var xml = await client.GetStringAsync(url, ct);

        var doc = XDocument.Parse(xml);
        var programs = new List<ProgramInfo>();

        foreach (var item in doc.Descendants("Item"))
        {
            var title = item.Element("Title")?.Value ?? "";
            var beginStr = item.Element("BeginDate")?.Value ?? "";
            var endStr = item.Element("EndDate")?.Value ?? "";
            var desc = item.Element("Description")?.Value ?? "";

            if (DateTime.TryParse(beginStr, out var start) && DateTime.TryParse(endStr, out var end))
            {
                programs.Add(new ProgramInfo
                {
                    Title = title,
                    Start = start,
                    End = end,
                    Description = desc
                });
            }
        }

        programs.Sort((a, b) => a.Start.CompareTo(b.Start));
        _programs = programs;
    }

    private void DisplayCurrentProgram()
    {
        if (_programs.Count == 0) return;

        var now = DateTime.Now;
        var current = _programs.LastOrDefault(p => p.Start <= now && p.End > now);
        var next = current != null
            ? _programs.FirstOrDefault(p => p.Start >= current.End)
            : _programs.FirstOrDefault(p => p.Start > now);

        if (current != null && current.Title != _lastDisplayedTitle)
        {
            _lastDisplayedTitle = current.Title;
            Console.WriteLine();
            Console.WriteLine($"  Now: {current.Title} ({current.Start:HH:mm}-{current.End:HH:mm})");
            if (!string.IsNullOrWhiteSpace(current.Description))
            {
                // Split numbered music items like ", 2. Brahms:" onto new lines
                var desc = Regex.Replace(current.Description, @",\s+(\d+\.\s+[A-ZÁÉÍÓÖŐÚÜŰ])", "\n$1");
                foreach (var line in desc.Split('\n', StringSplitOptions.RemoveEmptyEntries))
                    Console.WriteLine($"       {line.Trim()}");
            }
            if (next != null)
                Console.WriteLine($"  Next: {next.Title} ({next.Start:HH:mm})");
            Console.WriteLine();
        }
    }
}

// --- ReadFullyStream ---

class ReadFullyStream : Stream
{
    private readonly Stream _sourceStream;
    private long _pos;

    public ReadFullyStream(Stream sourceStream) => _sourceStream = sourceStream;

    public override int Read(byte[] buffer, int offset, int count)
    {
        int totalRead = 0;
        while (totalRead < count)
        {
            int bytesRead = _sourceStream.Read(buffer, offset + totalRead, count - totalRead);
            if (bytesRead == 0)
                break;
            totalRead += bytesRead;
        }
        _pos += totalRead;
        return totalRead;
    }

    public override bool CanRead => true;
    public override bool CanSeek => false;
    public override bool CanWrite => false;
    public override long Length => throw new NotSupportedException();
    public override long Position { get => _pos; set => throw new NotSupportedException(); }
    public override void Flush() { }
    public override long Seek(long offset, SeekOrigin origin) => throw new NotSupportedException();
    public override void SetLength(long value) => throw new NotSupportedException();
    public override void Write(byte[] buffer, int offset, int count) => throw new NotSupportedException();
}
