"""Generated, bandwidth-limited media and a delayed portal for paired startup measurements."""
from pathlib import Path
from urllib.parse import parse_qs
import json
import subprocess
import threading
import time

_lock = threading.Lock()


def handle(handler, path, query, root):
    directory = root / 'startup'
    if path == '/startup/prepare':
        with _lock:
            directory.mkdir(exist_ok=True)
            if not (directory / 'index.m3u8').exists():
                subprocess.run(['ffmpeg', '-v', 'error', '-f', 'lavfi', '-i', 'testsrc2=size=320x180:rate=24',
                    '-f', 'lavfi', '-i', 'sine=frequency=440:sample_rate=48000', '-t', '32',
                    '-c:v', 'libx264', '-preset', 'ultrafast', '-profile:v', 'baseline', '-level', '3.0',
                    '-pix_fmt', 'yuv420p', '-threads', '1', '-b:v', '1M', '-minrate', '1M', '-maxrate', '1M',
                    '-bufsize', '1M', '-x264-params', 'nal-hrd=cbr:force-cfr=1', '-g', '48', '-sc_threshold', '0',
                    '-c:a', 'aac', '-b:a', '64k', '-f', 'hls', '-hls_time', '2', '-hls_playlist_type', 'vod',
                    str(directory / 'index.m3u8')], check=True, timeout=60)
        return handler.send({'ready': True, 'bytesPerSecond': 204800, 'portalDelayMs': 160})
    if path == '/startup/portal.php':
        time.sleep(.16)
        action = parse_qs(query).get('action', [''])[0]
        result = {'handshake': {'token': 'generated-startup-token'}, 'get_profile': {'status': 0},
            'get_genres': [{'id': '1', 'title': 'Generated'}],
            'get_all_channels': [{'id': '1', 'name': 'Generated channel', 'tv_genre_id': '1',
                'cmd': 'ffmpeg http://localhost/ch/1'}], 'get_short_epg': [],
            'create_link': {'cmd': 'ffmpeg http://10.0.2.2:8878/startup/live/master.m3u8'}}.get(action)
        return handler.send({'js': result}, status=200 if result is not None else 404)
    if path.endswith('/master.m3u8'):
        time.sleep(.04)
        return handler.send(b'#EXTM3U\n#EXT-X-STREAM-INF:BANDWIDTH=1200000,RESOLUTION=320x180,CODECS="avc1.42c01e,mp4a.40.2"\nindex.m3u8\n',
            'application/vnd.apple.mpegurl')
    if path.endswith('/index.m3u8') and (directory / 'index.m3u8').is_file():
        time.sleep(.04)
        data = (directory / 'index.m3u8').read_text()
        if '/live/' in path:
            data = data.replace('#EXT-X-PLAYLIST-TYPE:VOD\n', '').replace('#EXT-X-ENDLIST\n', '')
        return handler.send(data.encode(), 'application/vnd.apple.mpegurl')
    file = directory / Path(path).name
    if file.is_file() and file.suffix == '.ts':
        data = file.read_bytes()
        started=time.monotonic();written=0
        print(json.dumps({'startupSegment':file.name,'event':'start','bytes':len(data),'at':started}),flush=True)
        handler.send_response(200)
        handler.send_header('Content-Type', 'video/mp2t')
        handler.send_header('Content-Length', str(len(data)))
        handler.end_headers()
        try:
            for offset in range(0, len(data), 4096):
                time.sleep(.02)
                handler.wfile.write(data[offset:offset + 4096])
                handler.wfile.flush()
                written+=len(data[offset:offset + 4096])
        except (BrokenPipeError, ConnectionResetError):
            pass
        finally:
            print(json.dumps({'startupSegment':file.name,'event':'end','bytesWritten':written,
                'durationMs':round((time.monotonic()-started)*1000)}),flush=True)
        return
    return handler.send({'error': 'Unknown startup fixture'}, status=404)
