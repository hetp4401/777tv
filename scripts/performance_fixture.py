"""Generated artwork and high-bitrate media for matched off-device memory/frame workloads."""
from pathlib import Path
import json
import subprocess
import threading

_lock=threading.Lock()
_stats=dict(thumbnailRequests=0,thumbnailBytes=0)


def handle(handler,path,root):
    directory=root/'performance';directory.mkdir(exist_ok=True)
    if path=='/youtube/performance/prepare':
        with _lock:
            for n in range(8):
                image=directory/f'artwork-{n}.jpg'
                if not image.is_file():
                    subprocess.run(['ffmpeg','-v','error','-f','lavfi','-i','testsrc2=size=480x270:rate=1',
                        '-vf',f'hue=h={n*35}','-frames:v','1','-q:v','4','-threads','1',str(image)],check=True,timeout=30)
            media=directory/'buffer';media.mkdir(exist_ok=True)
            if not (media/'index.m3u8').is_file():
                subprocess.run(['ffmpeg','-v','error','-f','lavfi','-i','testsrc2=size=640x360:rate=24',
                    '-f','lavfi','-i','sine=frequency=440:sample_rate=48000','-t','90','-c:v','libx264','-preset','ultrafast','-threads','2',
                    '-b:v','8M','-minrate','8M','-maxrate','8M','-bufsize','2M','-x264-params','nal-hrd=cbr:force-cfr=1',
                    '-g','48','-keyint_min','48','-sc_threshold','0','-c:a','aac','-b:a','96k',
                    '-f','hls','-hls_time','2','-hls_list_size','0',str(media/'index.m3u8')],check=True,timeout=90)
            _stats.update(thumbnailRequests=0,thumbnailBytes=0)
        return handler.send({'ready':True})
    if path=='/youtube/performance/stats':
        with _lock:result=dict(_stats)
        return handler.send(result)
    if path.startswith('/youtube/performance/thumbnail/'):
        try:n=int(Path(path).stem)
        except ValueError:return handler.send(b'',status=404)
        file=directory/f'artwork-{n%8}.jpg'
        if not file.is_file():return handler.send(b'',status=404)
        data=file.read_bytes()
        with _lock:_stats['thumbnailRequests']+=1;_stats['thumbnailBytes']+=len(data)
        handler.send_response(200);handler.send_header('Content-Type','image/jpeg');handler.send_header('Content-Length',str(len(data)))
        handler.send_header('Cache-Control','public, max-age=31536000, immutable');handler.end_headers()
        try:handler.wfile.write(data)
        except (BrokenPipeError,ConnectionResetError):pass
        return
    if path.startswith('/youtube/performance/buffer/'):
        file=directory/'buffer'/Path(path).name
        if not file.is_file() or file.suffix not in ['.m3u8','.ts']:return handler.send(b'',status=404)
        return handler.send(file.read_bytes(),'application/vnd.apple.mpegurl' if file.suffix=='.m3u8' else 'video/mp2t')
    return handler.send({'error':'Unknown generated performance fixture'},status=404)
