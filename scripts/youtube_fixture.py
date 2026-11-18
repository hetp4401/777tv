"""Generated YouTube TV protocol fixtures. No real Google accounts, tokens or videos."""
from pathlib import Path
from urllib.parse import parse_qs, urlparse
import json
import subprocess
import threading
import time
import performance_fixture

_lock=threading.Lock()
_state={}


def reset():
    with _lock:
        _state.clear()
        _state.update(requests=[],resolved=[],approve=False,deny=False,slow=False,fail=False,fail_playback=False,split_media=False,
            no_heatmap=False,no_comments=False,fail_details=False,guest_fail=False,empty_guest=False)


reset()


def snapshot():
    with _lock:
        return json.loads(json.dumps(_state))


def video(index,short=False,prefix=None):
    identifier=(prefix or ('S' if short else 'V'))+str(index).zfill(10)
    name=('Guest video ' if prefix=='G' else 'Short ' if short else 'Video ')+str(index)
    metadata={'title':{'content':name},'metadata':{'contentMetadataViewModel':{'metadataRows':[
        {'metadataParts':[{'text':{'content':'Generated channel'}}]},
        {'metadataParts':[{'text':{'content':'Fixture video'}}]}]}}}
    return {'lockupViewModel':{'contentId':identifier,'contentType':'LOCKUP_CONTENT_TYPE_SHORTS' if short else 'LOCKUP_CONTENT_TYPE_VIDEO',
        'metadata':{'lockupMetadataViewModel':metadata},'contentImage':{'thumbnailViewModel':{'image':{'sources':[
            {'url':'http://10.0.2.2:8878/logo.png','width':320,'height':180}]}}}}}


def endpoint(index):
    return {'videoId':'S'+str(index).zfill(10),'params':str(index),'thumbnail':{'thumbnails':[
        {'url':'http://10.0.2.2:8878/logo.png','width':180,'height':320}]}}


def reel(index):
    return {'overlay':{'reelPlayerOverlayRenderer':{'reelPlayerHeaderSupportedRenderers':{'reelPlayerHeaderRenderer':{
        'reelTitleText':{'simpleText':'Short '+str(index)},'channelTitleText':{'simpleText':'Generated channel'},'timestampText':{'simpleText':'Fixture Short'}}}}},
        'replacementEndpoint':{'reelWatchEndpoint':endpoint(index)},'sequenceContinuation':'shorts-1'}


def handle_get(handler,path,query,root):
    if path.startswith('/youtube/performance/'):return performance_fixture.handle(handler,path,root)
    if path=='/youtube/control':
        q=parse_qs(query)
        if q.get('reset')==['1']:reset()
        with _lock:
            for name in ['approve','deny','slow','fail','fail_playback','split_media','no_heatmap','no_comments','fail_details','guest_fail','empty_guest']:
                if name in q:_state[name]=q[name]==['1']
        return handler.send({'ok':True})
    if path=='/youtube/state':return handler.send(snapshot())
    if path=='/youtube/tv':
        return handler.send(b'{"INNERTUBE_CLIENT_VERSION":"7.fixture","visitorData":"fixture"}<script id="base-js" src="/youtube/base.js"></script>','text/html')
    if path=='/youtube/':
        return handler.send(b'{"INNERTUBE_CLIENT_VERSION":"2.fixture","VISITOR_DATA":"fixture-web"}','text/html')
    if path=='/youtube/base.js':
        return handler.send(b'clientId:"fixture.apps.googleusercontent.com",secret:"fixtureSecret"','application/javascript')
    if path.startswith('/youtube/resolve/'):
        identifier=path.rsplit('/',1)[-1]
        with _lock:_state['resolved'].append(identifier);fail=_state['fail_playback'];slow=_state['slow'];split=_state['split_media']
        if slow:time.sleep(1)
        if fail:return handler.send({'error':'fixture unavailable'},status=503)
        if split:return handler.send({'url':'http://10.0.2.2:8878/youtube/media-split/video.mp4',
            'audioUrl':'http://10.0.2.2:8878/youtube/media-split/audio.m4a','videoMimeType':'video/mp4','audioMimeType':'audio/mp4'})
        address='http://10.0.2.2:8878/youtube/media-short/index.m3u8' if identifier.startswith('S') else 'http://10.0.2.2:8878/hls/fixture/orig.m3u8'
        return handler.send({'url':address+'?video='+identifier})
    if path.startswith('/youtube/media-split/'):
        directory=root/'youtube-split';directory.mkdir(exist_ok=True)
        with _lock:
            if not (directory/'video.mp4').exists():
                subprocess.run(['ffmpeg','-v','error','-f','lavfi','-i','testsrc2=size=320x180:rate=24','-t','20','-an','-c:v','libx264',
                    '-preset','ultrafast','-pix_fmt','yuv420p','-movflags','+faststart',str(directory/'video.mp4')],check=True,timeout=30)
            if not (directory/'audio.m4a').exists():
                subprocess.run(['ffmpeg','-v','error','-f','lavfi','-i','sine=frequency=440:sample_rate=48000','-t','20','-vn','-c:a','aac',
                    '-movflags','+faststart',str(directory/'audio.m4a')],check=True,timeout=30)
        file=directory/Path(path).name
        if not file.is_file():return handler.send(b'',status=404)
        size=file.stat().st_size;start=0;end=size-1
        requested=handler.headers.get('Range')
        if requested:
            import re
            match=re.fullmatch(r'bytes=(\d+)-(\d*)',requested)
            if not match:return handler.send(b'',status=416)
            start=int(match[1]);end=min(int(match[2]),end) if match[2] else end
            if start>=size or end<start:return handler.send(b'',status=416)
        handler.send_response(206 if requested else 200)
        handler.send_header('Content-Type','audio/mp4' if file.suffix=='.m4a' else 'video/mp4')
        handler.send_header('Content-Length',str(end-start+1));handler.send_header('Accept-Ranges','bytes')
        if requested:handler.send_header('Content-Range',f'bytes {start}-{end}/{size}')
        handler.end_headers()
        try:
            with file.open('rb') as stream:
                stream.seek(start);remaining=end-start+1
                while remaining:
                    data=stream.read(min(65536,remaining));handler.wfile.write(data);remaining-=len(data)
        except (BrokenPipeError,ConnectionResetError):pass
        return
    if path.startswith('/youtube/media-short/'):
        directory=root/'youtube-short';directory.mkdir(exist_ok=True)
        with _lock:
            if not (directory/'index.m3u8').exists():
                subprocess.run(['ffmpeg','-v','error','-f','lavfi','-i','testsrc2=size=180x320:rate=24','-f','lavfi','-i','sine=frequency=600:sample_rate=48000',
                    '-t','15','-c:v','libx264','-preset','ultrafast','-pix_fmt','yuv420p','-g','48','-c:a','aac','-f','hls','-hls_time','2',
                    '-hls_playlist_type','vod',str(directory/'index.m3u8')],check=True,timeout=30)
        media=directory/Path(path).name
        if media.is_file():return handler.send(media.read_bytes(),'application/vnd.apple.mpegurl' if media.suffix=='.m3u8' else 'video/mp2t')
    return handler.send({'error':'unknown fixture'},status=404)


def handle_post(handler,path):
    length=int(handler.headers.get('Content-Length','0'))
    if length>128*1024:return handler.send({'error':'fixture body limit'},status=413)
    body=json.loads(handler.rfile.read(length) or b'{}')
    with _lock:
        _state['requests'].append({'path':path,'query':body.get('query',''),'authenticated':bool(handler.headers.get('Authorization')),
            'client':body.get('context',{}).get('client',{}).get('clientName',''),'continuation':body.get('continuation',''),
            'browseId':body.get('browseId',''),'videoId':body.get('videoId','')})
        approved=_state['approve'];denied=_state['deny'];slow=_state['slow'];fail=_state['fail']
    if slow and path.endswith('/search') and body.get('query')=='slow':time.sleep(1)
    if fail:return handler.send({'error':'generated failure'},status=503)
    if path=='/youtube/o/oauth2/device/code':
        return handler.send({'device_code':'fixture-device','user_code':'TV-FIXTURE','verification_url':'https://www.google.com/device','expires_in':1800,'interval':5})
    if path=='/youtube/o/oauth2/token':
        if denied:return handler.send({'error':'access_denied'},status=400)
        if not approved and body.get('grant_type')!='refresh_token':return handler.send({'error':'authorization_pending'},status=400)
        return handler.send({'access_token':'fixture-youtube-access','refresh_token':'fixture-youtube-refresh','expires_in':3600})
    if path.endswith('/reel_item_watch'):
        identifier=body.get('playerRequest',{}).get('videoId','S0000000001')
        return handler.send(reel(int(identifier[1:])))
    if path.endswith('/reel_watch_sequence'):
        page=int(body.get('sequenceParams','shorts-1').rsplit('-',1)[-1])
        return handler.send({'entries':[{'command':{'reelWatchEndpoint':endpoint(page*12+n)}} for n in range(1,13)],'continuation':'shorts-'+str(page+1)})
    if path.endswith('/browse'):
        identifier=body.get('browseId','')
        if identifier.startswith('FEtopics_'):
            with _lock:guest_fail=_state['guest_fail'];empty_guest=_state['empty_guest']
            if guest_fail:return handler.send({'error':'generated guest failure'},status=503)
            start={'FEtopics_music':100,'FEtopics_gaming':200,'FEtopics_news':300}.get(identifier,400)
            return handler.send({'contents':[] if empty_guest else [video(start+n,prefix='G') for n in range(1,13)]})
        if not handler.headers.get('Authorization'):
            return handler.send({'contents':{'feedNudgeRenderer':{'subtitle':{'simpleText':'Sign in for recommendations, or search.'}}}})
        personal={'FEsubscriptions':'U','FEhistory':'H','FEmy_youtube':'L','VLLL':'K'}
        continuation=body.get('continuation','')
        if continuation.startswith('fixture-library-'):identifier=continuation.removeprefix('fixture-library-')
        if identifier in personal:
            start=21 if continuation else 1
            return handler.send({'contents':[video(n,prefix=personal[identifier]) for n in range(start,start+20)],
                **({'continuationContents':{'nextContinuationData':{'continuation':'fixture-library-'+identifier}}} if not continuation else {})})
        rows=[]
        for title,start,short in [('Recommended',1,False),('New to you',13,False),('Shorts',25,True)]:
            rows.append({'shelfRenderer':{'title':{'simpleText':title},'content':{'horizontalListRenderer':{
                'items':[video(n,short) for n in range(start,start+12)]}}}})
        return handler.send({'contents':{'sectionListRenderer':{'contents':rows}}})
    if path.endswith('/search'):
        query=body.get('query','')
        if query=='nothing':return handler.send({'contents':[]})
        start=33 if body.get('continuation') else 1
        return handler.send({'contents':[video(n) for n in range(start,start+32)],
            **({'continuationContents':{'nextContinuationData':{'continuation':'fixture-page-2'}}} if start==1 else {})})
    if path.endswith('/next'):
        with _lock:
            no_heatmap=_state['no_heatmap'];no_comments=_state['no_comments'];fail_details=_state['fail_details']
        web=body.get('context',{}).get('client',{}).get('clientName')=='WEB'
        if web:
            return handler.send({} if no_heatmap else {'frameworkUpdates':{'entityBatchUpdate':{'mutations':[{'payload':{
                'macroMarkersListEntity':{'markersList':{'markerType':'MARKER_TYPE_HEATMAP','markers':[
                    {'startMillis':str(n*1000),'durationMillis':'1000','intensityScoreNormalized':(n%8)/7} for n in range(20)]}}}}]}}})
        if fail_details:return handler.send({'error':'fixture details unavailable'},status=503)
        if slow:time.sleep(.8)
        continuation=body.get('continuation')
        if continuation:
            page=2 if continuation=='fixture-comments-2' else 1
            reply=continuation=='fixture-replies'
            start=21 if page==2 else 1
            rows=[]
            for n in range(start,start+(3 if reply else 20)):
                item={'commentId':('reply-' if reply else 'comment-')+str(n),'authorText':{'simpleText':'Fixture viewer '+str(n)},
                    'contentText':{'simpleText':('Generated reply ' if reply else 'Generated comment ')+str(n)},
                    'publishedTimeText':{'simpleText':'1 day ago'},'voteCount':{'simpleText':'12'}}
                if not reply:
                    item.update(repliesCount={'simpleText':'3 replies'},detailViewEndpoint={
                        'showEngagementPanelEndpoint':{'engagementPanel':{'engagementPanelSectionListRenderer':{'content':{
                            'reloadContinuationData':{'continuation':'fixture-replies'}}}}}})
                rows.append({'commentThreadRenderer':{'comment':{'commentRenderer':item}}})
            return handler.send({'continuationContents':{'itemSectionContinuation':{'contents':rows,
                **({'continuations':[{'nextContinuationData':{'continuation':'fixture-comments-2'}}]} if page==1 and not reply else {})}}})
        return handler.send({'contents':{'singleColumnWatchNextResults':{'pivot':{'sectionListRenderer':{'contents':[video(n) for n in range(40,60)]}}}},
            'engagementPanels':[{'engagementPanelSectionListRenderer':{'panelIdentifier':'comment-item-section',
                'header':{'engagementPanelTitleHeaderRenderer':{'title':{'simpleText':'Comments'}}},
                'content':{} if no_comments else {'reloadContinuationData':{'continuation':'fixture-comments-1'}}}}]})
    return handler.send({'error':'unknown fixture'},status=404)
