using System;
using System.IO;
using System.Net.Http;
using System.Threading;
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
            break; // stream ended after playing — go to reconnect, don't try next URL
        }
        catch (OperationCanceledException) when (cts.Token.IsCancellationRequested)
        {
            break;
        }
        catch (Exception ex)
        {
            Console.WriteLine($"[{DateTime.Now:HH:mm:ss}] Failed: {ex.Message}");
            // try next URL
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

/// <summary>
/// Stream wrapper that ensures Read() always fills the requested byte count (required by Mp3Frame.LoadFromStream).
/// </summary>
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
