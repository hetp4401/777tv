#!/usr/bin/env python3
"""Loopback-only TV smoke-test server. Media is generated from FFmpeg test inputs."""
from http.server import ThreadingHTTPServer, BaseHTTPRequestHandler
from pathlib import Path
from urllib.parse import urlparse, parse_qs, unquote
import json
import mimetypes
import subprocess
import threading
import time
import struct
import zlib
import re
import youtube_fixture
import startup_fixture


def logo_png():
    def chunk(name,data):
        return struct.pack('!I',len(data))+name+data+struct.pack('!I',zlib.crc32(name+data))
    pixels=(b'\x00'+b'\xff\x00\x00'*24)*12
    return b'\x89PNG\r\n\x1a\n'+chunk(b'IHDR',struct.pack('!2I5B',24,12,8,2,0,0,0))+chunk(b'IDAT',zlib.compress(pixels))+chunk(b'IEND',b'')

ROOT = Path(__file__).resolve().parents[1] / ".tools/fixture-media-v2"
ROOT.mkdir(parents=True, exist_ok=True)
if not (ROOT / "index.m3u8").exists():
    subprocess.run(["ffmpeg", "-v", "error", "-f", "lavfi", "-i", "testsrc2=size=640x360:rate=24", "-f", "lavfi", "-i", "sine=frequency=440:sample_rate=48000", "-t", "60", "-c:v", "libx264", "-preset", "ultrafast", "-pix_fmt", "yuv420p", "-g", "48", "-c:a", "aac", "-f", "hls", "-hls_time", "2", "-hls_playlist_type", "vod", str(ROOT / "index.m3u8")], check=True)
state = {"sessions": {}, "violations": [], "requests": 0, "search_requests": 0, "meta_requests": {}, "stream_requests": {}, "catalog_requests": [], "stream_epoch": 0, "fail_keep": False, "broken_live": False, "partial_catalog": False, "large_channels": False, "emptyStreams": False, "broken_vod": False, "fail_streams": False, "slow_streams": False, "fail_meta": False, "updated_episodes": False, "portal_loads": 0, "portal_links": 0}
lock = threading.Lock()
LIVE_STARTED = time.monotonic() - 60


def live_playlist():
    """A moving live window; loop generated segments with explicit discontinuities."""
    segments = [line for line in (ROOT / "index.m3u8").read_text().splitlines() if line and not line.startswith('#')]
    newest = int((time.monotonic() - LIVE_STARTED) / 2)
    first = max(0, newest - len(segments))
    rows = ['#EXTM3U', '#EXT-X-VERSION:3', '#EXT-X-TARGETDURATION:2',
            f'#EXT-X-MEDIA-SEQUENCE:{first}', f'#EXT-X-DISCONTINUITY-SEQUENCE:{first // len(segments)}']
    for sequence in range(first, newest):
        if sequence != first and sequence % len(segments) == 0:
            rows.append('#EXT-X-DISCONTINUITY')
        rows.extend(['#EXTINF:2.000000,', f'{segments[sequence % len(segments)]}?cycle={sequence // len(segments)}'])
    return ('\n'.join(rows) + '\n').encode()


def title(kind):
    return {"id": "tt0000001" if kind == "movie" else "tt0000002", "name": "Fleet Test Movie" if kind == "movie" else "Fleet Test Series", "type": kind, "year": "2026", "description": "An original test pattern for checking Fleet TV playback, remote navigation, and resume.", "genres": ["Testing"], "imdbRating": "8.0", "videos": [{"id": "tt0000002:1:1", "season": 1, "episode": 1, "name": "Pilot"}, {"id": "tt0000002:1:2", "season": 1, "episode": 2, "name": "Second episode"}] if kind == "series" else []}


class Handler(BaseHTTPRequestHandler):
    def log_message(self, *args):
        pass

    def send(self, data, content="application/json", status=200):
        raw = data if isinstance(data, bytes) else json.dumps(data).encode()
        self.send_response(status)
        self.send_header("Content-Type", content)
        self.send_header("Content-Length", str(len(raw)))
        self.send_header("Cache-Control", "no-store")
        self.end_headers()
        try:
            self.wfile.write(raw)
        except (BrokenPipeError, ConnectionResetError):
            pass

    def media(self, head=False):
        from multitrack_fixture import ensure_multitrack
        path=ensure_multitrack(ROOT)
        size=path.stat().st_size;start=0;end=size-1
        requested=self.headers.get('Range')
        if requested:
            match=re.fullmatch(r'bytes=(\d*)-(\d*)',requested)
            if not match or not any(match.groups()):return self.send(b'',status=416)
            left,right=match.groups()
            if left:
                start=int(left);end=min(int(right),end) if right else end
            else:start=max(0,size-int(right))
            if start>=size or start>end:
                self.send_response(416);self.send_header('Content-Range',f'bytes */{size}');self.send_header('Content-Length','0');self.end_headers();return
        self.send_response(206 if requested else 200)
        self.send_header('Content-Type','video/x-matroska');self.send_header('Accept-Ranges','bytes')
        self.send_header('Content-Length',str(end-start+1))
        if requested:self.send_header('Content-Range',f'bytes {start}-{end}/{size}')
        self.end_headers()
        if head:return
        try:
            with path.open('rb') as media:
                media.seek(start);remaining=end-start+1
                while remaining:
                    data=media.read(min(65536,remaining))
                    if not data:break
                    self.wfile.write(data);remaining-=len(data)
        except (BrokenPipeError,ConnectionResetError):pass

    def do_HEAD(self):
        if urlparse(self.path).path=='/media/multitrack.mkv':return self.media(head=True)
        return self.send(b'',status=404)

    def do_GET(self):
        u = urlparse(self.path); path = unquote(u.path)
        if path.startswith('/startup/'):return startup_fixture.handle(self,path,u.query,ROOT)
        if path.startswith('/youtube/'):return youtube_fixture.handle_get(self,path,u.query,ROOT)
        if path=='/media/multitrack.mkv':return self.media()
        if path == '/logo.png':
            if parse_qs(u.query).get('slow') == ['1']:time.sleep(1)
            return self.send(logo_png(),'image/png')
        with lock:
            state["requests"] += 1
        if path == "/__control":
            q = parse_qs(u.query)
            with lock:
                if q.get('reset') == ['1']:
                    for name in ['fail_keep','broken_live','partial_catalog','large_channels','emptyStreams','broken_vod','fail_streams','slow_streams','fail_meta','updated_episodes']:state[name] = False
                    state['meta_requests']={};state['stream_requests']={};state['catalog_requests']=[];state['stream_epoch']=0
                if q.get('renew_streams') == ['1']:state['stream_epoch']+=1
                for name in ['fail_keep','broken_live','partial_catalog','large_channels','emptyStreams','broken_vod','fail_streams','slow_streams','fail_meta','updated_episodes']:
                    if name in q:state[name] = q[name] == ['1']
                if q.get('reset_sessions') == ['1']:state['sessions'] = {};state['violations'] = []
            return self.send({'ok':True})
        if path == "/__state":
            with lock:
                return self.send(state)
        if path=='/media/headers.m3u8':
            values={name:self.headers.get(name,'') for name in ['X-Fixture-Comma','X-Fixture-Slash','X-Fixture-Final']}
            with lock:state.setdefault('header_checks',[]).append({'engine':parse_qs(u.query).get('engine',[''])[0],'values':values})
            expected={'X-Fixture-Comma':'alpha,beta','X-Fixture-Slash':'ends'+chr(92),'X-Fixture-Final':'last'}
            if values!=expected:return self.send({'error':'stream headers changed'},status=403)
            return self.send((ROOT/'index.m3u8').read_bytes(),'application/vnd.apple.mpegurl')
        if path.startswith('/addons/'):
            parts=path.split('/');kind=parts[2]
            if path.endswith('/manifest.json'):
                if kind=='catalogs':
                    return self.send({'id':'fixture.catalogs','version':'1.0.0','name':'Fixture Catalogs','types':['movie','series'],'resources':['catalog'],'catalogs':[
                        {'type':'movie','id':'top','name':'Popular','extra':[{'name':'genre','options':['Action','Comedy']},{'name':'search'}]},
                        {'type':'series','id':'top','name':'Popular','extra':[{'name':'genre','options':['Drama']},{'name':'search'}]},
                        {'type':'movie','id':'year','name':'New','extra':[{'name':'genre','isRequired':True,'options':['2026','2025']},{'name':'skip'}]},
                        {'type':'movie','id':'featured','name':'Featured','extra':[{'name':'genre','options':['Action','Comedy']}]}]})
                if kind=='cinemeta':
                    return self.send({'id':'com.linvo.cinemeta','version':'1.0.0','name':'Fixture Cinemeta','types':['movie','series'],'resources':['catalog','meta'],'catalogs':[{'type':t,'id':'top','name':'Popular','extra':[{'name':'search'},{'name':'skip'}]} for t in ['movie','series']]})
                return self.send({'id':'billybishop.gdrive' if kind=='gdrive' else 'fixture.extra','version':'1.0.0','name':'Fixture GDrive' if kind=='gdrive' else 'Fixture Extra','types':['movie','series'],'resources':['stream'],'catalogs':[]})
            if len(parts)<6:return self.send({'error':'not found'},status=404)
            resource,media_type=parts[3:5];identifier=parts[5].removesuffix('.json')
            if resource=='catalog':
                extra=parse_qs(parts[6].removesuffix('.json')) if len(parts)>6 else {}
                safe_extra={key:values[0] for key,values in extra.items() if key in ['genre','search','skip'] and values}
                with lock:state['catalog_requests'].append({'kind':kind,'type':media_type,'id':identifier,'extras':safe_extra})
                match=re.search(r'/search=(.*)\.json$',path)
                if match:
                    with lock:state['search_requests']+=1
                    if match[1]=='No results fixture':return self.send({'metas':[]})
                if state['partial_catalog'] and media_type=='series':return self.send({'error':'test failure'},status=503)
                if kind=='catalogs':
                    genre=safe_extra.get('genre','')
                    if genre=='Action':time.sleep(1)
                    meta=title(media_type);token=genre or safe_extra.get('search','') or 'default'
                    meta['id']=f'fixture-{media_type}-{identifier}-{token}'
                    meta['name']=f'{genre} Fixture '+('Movie' if media_type=='movie' else 'Series')
                    return self.send({'metas':[meta]})
                return self.send({'metas':[title(media_type)]})
            if resource=='meta':
                with lock:state['meta_requests'][identifier]=state['meta_requests'].get(identifier,0)+1
                if state['fail_meta']:return self.send({'error':'fixture metadata unavailable'},status=503)
                meta=title(media_type)
                if identifier=='browse-resume-series':
                    meta['id']=identifier;meta['name']='Resume fixture series'
                    prefix='Updated fixture' if state['updated_episodes'] else 'Fixture'
                    meta['videos']=[{'id':f'{identifier}:{season}:{number}','season':season,'episode':number,'name':f'Episode {number}','overview':f'{prefix} overview for season {season} episode {number}.'} for season,number in [(1,1),(1,2),(2,1),(2,2),(2,5)]]
                if identifier=='browse-season-rollover':
                    meta['id']=identifier;meta['name']='Season rollover fixture'
                    order=[(0,1)]+[(1,n) for n in range(1,9)]+[(3,n) for n in [1,4,9]]+[(12,1)]
                    meta['videos']=[{'id':f'{identifier}:{season}:{number}','season':season,'episode':number,'name':f'Episode {number}','overview':f'Rollover fixture season {season} episode {number}.'} for season,number in reversed(order)]
                if identifier=='browse-navigation-fluidity':
                    meta['id']=identifier;meta['name']='Navigation fluidity fixture'
                    meta['videos']=[{'id':f'{identifier}:{season}:{number}','season':season,'episode':number,'name':f'Episode {number}','overview':f'Generated season {season} episode {number}.'} for season in range(1,5) for number in range(1,11)]
                return self.send({'meta':meta})
            if resource=='stream':
                with lock:state['stream_requests'][identifier]=state['stream_requests'].get(identifier,0)+1
                if state['slow_streams']:time.sleep(1)
                if state['fail_streams']:return self.send({'error':'fixture streams unavailable'},status=503)
                if identifier.startswith('browse-navigation-fluidity:'):
                    return self.send({'streams':[{'name':f'GDrive 1080p source {number:02d}','title':f'Generated navigation source {number:02d}\n{33-number} GB','url':f'http://10.0.2.2:8878/hls/fixture/orig.m3u8?episode={identifier}&source={number}&epoch={state["stream_epoch"]}'} for number in range(1,33)]})
                if identifier=='browse-header-variants':
                    return self.send({'streams':[{'name':'Header '+label,'title':'Synthetic source '+label,'url':'http://10.0.2.2:8878/hls/fixture/orig.m3u8?source=shared','behaviorHints':{'proxyHeaders':{'request':{'X-Fixture-Source':label}}}} for label in ['first','second']]})
                if identifier=='browse-no-streams' and state['emptyStreams']:return self.send({'streams':[]})
                if identifier=='browse-stream-metadata':
                    return self.send({'streams':[
                        {'name':'GDrive 2160p','title':'A.Very.Long.Fixture.Movie.2026.2160p.UHD.BluRay.Remux.DV.P7.HDR.MULTi.mkv\n55.5 GB\nHEVC · TrueHD Atmos7.1 · English +8 languages\nOriginal video without re-encoding; repackaged with lossless audio','url':'http://10.0.2.2:8878/hls/fixture/orig.m3u8?source=large'},
                        {'name':'GDrive 1080p','title':'Another.Fixture.Movie.2026.1080p.BluRay.x264.mkv\n8.2 GB\nH264 · AAC · English','url':'http://10.0.2.2:8878/hls/fixture/orig.m3u8?source=small','behaviorHints':{'proxyHeaders':{'request':{'X-Fixture-Source':'small'}}}}]})
                suffix=f'?episode={identifier}&epoch={state["stream_epoch"]}' if media_type=='series' else ''
                return self.send({'streams':[{'name':'GDrive 1080p','title':'Fleet test pattern\n12.5 GB','url':'http://10.0.2.2:8878/hls/fixture/orig.m3u8'+suffix},{'name':'GDrive 720p','title':'Fleet compact pattern\n900 MB','url':'http://10.0.2.2:8878/hls/fixture/index.m3u8'+suffix}]})
            return self.send({'error':'not found'},status=404)
        if path in ('/stalker_portal/server/load.php', '/stalker_portal/portal.php'):
            q = parse_qs(u.query)
            action = q.get('action', [''])[0]
            if action == 'handshake':return self.send({'js':{'token':'fixture-portal-token','not_valid':0}})
            if self.headers.get('Authorization') != 'Bearer fixture-portal-token':return self.send({'js':False},status=401)
            if action == 'get_profile':return self.send({'js':{'id':42,'status':0}})
            if action == 'get_genres':return self.send({'js':[{'id':'1','title':'News'},{'id':'2','title':'Sports'}]})
            if action == 'get_all_channels':
                state['portal_loads'] += 1
                if state['large_channels']:
                    return self.send({'js':{'total_items':6000,'data':[{'id':str(i),'name':'Channel '+str(i),'tv_genre_id':'1','cmd':'ffmpeg http://localhost/ch/'+str(i)} for i in range(1,6001)]}})
                return self.send({'js':{'total_items':2,'data':[
                    {'id':'1','name':'Fixture News','tv_genre_id':'1','cmd':'ffmpeg http://localhost/ch/1_'},
                    {'id':'2','name':'Fixture Sports','tv_genre_id':'2','cmd':'ffmpeg http://localhost/ch/2_'}]}})
            if action == 'get_short_epg':
                now = int(time.time())
                rows = [{'name':'Fixture Morning News','start_timestamp':now-900,'stop_timestamp':now+900},{'name':'Fixture World Report','start_timestamp':now+900,'stop_timestamp':now+2700}] if q.get('ch_id') == ['1'] else []
                return self.send({'js':rows})
            if action == 'create_link':
                state['portal_links'] += 1
                prefix = 'broken' if state['broken_live'] else 'live/demo'
                return self.send({'js':{'cmd':'ffmpeg http://10.0.2.2:8878/'+prefix+'/index.m3u8'}})
            return self.send({'error':'unknown portal action'},status=404)
        if path.startswith('/broken/'):
            return self.send({'error':'test channel unavailable'},status=404)
        if state['broken_vod'] and path.startswith('/hls/fixture/'):
            return self.send({'error':'test source unavailable'},status=404)
        if path.endswith(".m3u8"):
            playlist = (ROOT / "index.m3u8").read_bytes()
            if path.startswith('/live/'):
                playlist = live_playlist()
            return self.send(playlist, "application/vnd.apple.mpegurl")
        if path.endswith(".ts"):
            p = ROOT / Path(path).name
            if p.is_file():
                return self.send(p.read_bytes(), "video/mp2t")
        return self.send({"error": "not found"}, status=404)

    def do_POST(self):
        u = urlparse(self.path); q = parse_qs(u.query)
        if u.path.startswith('/youtube/'):return youtube_fixture.handle_post(self,u.path)
        if u.path != "/hls/fixture/session":
            return self.send({"error": "unknown stream"}, status=404)
        sid = q.get("sid", [""])[0]; action = q.get("action", ["keep"])[0]
        with lock:
            s = state["sessions"].setdefault(sid, {"closed": False, "actions": []})
            if s["closed"]:
                state["violations"].append({"sid": sid, "action": action})
                return self.send({"error": "playback ended"}, status=410)
            s["actions"].append({"action": action, "at": q.get("at"), "seq": q.get("seq")})
            if action == "stop":
                s["closed"] = True
        return self.send({"mode": "original", "error": "fixture unavailable" if state['fail_keep'] and action != 'stop' else None, "fallback": None})


print("Fleet TV fixture server: http://127.0.0.1:8878", flush=True)
ThreadingHTTPServer(("127.0.0.1", 8878), Handler).serve_forever()
