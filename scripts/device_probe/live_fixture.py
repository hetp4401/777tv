"""Generated looping HLS on loopback; never reads or provisions IPTV profiles."""
import argparse
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
import subprocess
import time

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('--media-dir', type=Path, default=Path('.tools/device-probe-live'))
args = parser.parse_args()
root = args.media_dir.resolve()
root.mkdir(parents=True, exist_ok=True)
if not (root / 'index.m3u8').is_file():
    subprocess.run([
        'ffmpeg', '-v', 'error', '-f', 'lavfi', '-i', 'testsrc2=size=640x360:rate=24',
        '-f', 'lavfi', '-i', 'sine=frequency=440:sample_rate=48000', '-t', '60',
        '-c:v', 'libx264', '-preset', 'ultrafast', '-profile:v', 'baseline', '-pix_fmt', 'yuv420p',
        '-threads', '1', '-g', '48', '-sc_threshold', '0', '-c:a', 'aac', '-b:a', '64k',
        '-f', 'hls', '-hls_time', '2', '-hls_playlist_type', 'vod', str(root / 'index.m3u8'),
    ], check=True, timeout=90)
segments = list(root.glob('index*.ts'))
count = len(segments)
if count < 6:
    raise SystemExit('Expected at least six generated two-second segments.')
start = time.monotonic()


class Handler(BaseHTTPRequestHandler):
    def log_message(self, *_args):
        pass

    def do_GET(self):
        path = self.path.split('?')[0]
        if path == '/live.m3u8':
            end = int((time.monotonic() - start) // 2) + 6
            first = end - 5
            lines = ['#EXTM3U', '#EXT-X-VERSION:3', '#EXT-X-TARGETDURATION:2',
                     '#EXT-X-MEDIA-SEQUENCE:' + str(first),
                     '#EXT-X-DISCONTINUITY-SEQUENCE:' + str(first // count)]
            for sequence in range(first, end + 1):
                if sequence % count == 0:
                    lines.append('#EXT-X-DISCONTINUITY')
                lines += ['#EXTINF:2.000000,', 'index' + str(sequence % count) + '.ts']
            data = ('\n'.join(lines) + '\n').encode()
            mime = 'application/vnd.apple.mpegurl'
        else:
            name = path.lstrip('/')
            if '/' in name or not name.startswith('index') or not name.endswith('.ts'):
                self.send_error(404)
                return
            try:
                data = (root / name).read_bytes()
            except FileNotFoundError:
                self.send_error(404)
                return
            mime = 'video/mp2t'
        self.send_response(200)
        self.send_header('Content-Type', mime)
        self.send_header('Content-Length', str(len(data)))
        self.send_header('Cache-Control', 'no-store')
        self.end_headers()
        self.wfile.write(data)


print('Serving generated live media at 127.0.0.1:8893; Ctrl-C stops it.', flush=True)
ThreadingHTTPServer(('127.0.0.1', 8893), Handler).serve_forever()
