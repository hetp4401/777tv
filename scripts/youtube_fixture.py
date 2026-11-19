"""Generated YouTube TV protocol fixtures. No real Google accounts, tokens or videos."""
from pathlib import Path
from urllib.parse import parse_qs, urlparse
import json
import subprocess
import threading
import time
import struct
import zlib
import performance_fixture

_lock=threading.Lock()
_phone_changed=threading.Condition(_lock)
_state={}
CHANNEL_ID='UC'+'1'*22


def reset():
    with _lock:
        _state.clear()
        _state.update(requests=[],resolved=[],fallbackResolved=[],storyboardRequests=[],no_storyboard=False,slow_storyboard=False,broken_storyboard=False,
            approve=False,deny=False,slow=False,fail=False,fail_playback=False,split_media=False,rejected_media=False,rejected_fallback=False,
            no_heatmap=False,no_comments=False,no_chapters=False,fail_details=False,guest_fail=False,empty_guest=False,
            history_paused=False,fail_actions=False,slow_actions=False,slow_remote=False,slow_next=False,slow_suggestions=False,grouped_search=False,stateful_account=False,ratings={},subscribed=False,
            playlists={'WL':{'name':'Watch later','videos':[]},'PLfixture':{'name':'Fixture playlist','videos':[]}},phone_events=[],phone_aid=4,phone_screen=0,slow_phone=False)


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
        'onSelectCommand':{'watchEndpoint':{'videoId':identifier,**({'startTimeSeconds':12} if prefix=='H' else {})}},
        'ownerEndpoint':{'browseEndpoint':{'browseId':CHANNEL_ID}},
        'metadata':{'lockupMetadataViewModel':metadata},'contentImage':{'thumbnailViewModel':{'image':{'sources':[
            {'url':'http://10.0.2.2:8878/logo.png','width':320,'height':180}]}}}}}


def endpoint(index):
    return {'videoId':'S'+str(index).zfill(10),'params':str(index),'thumbnail':{'thumbnails':[
        {'url':'http://10.0.2.2:8878/logo.png','width':180,'height':320}]}}


def video_id(identifier):
    item=video(1)['lockupViewModel']
    item['contentId']=identifier;item['onSelectCommand']['watchEndpoint']['videoId']=identifier
    item['metadata']['lockupMetadataViewModel']['title']['content']='Saved video '+identifier
    return {'lockupViewModel':item}


def shelf(name,items,continuation=None):
    listing={'items':items}
    if continuation:listing['continuations']=[{'nextContinuationData':{'continuation':continuation}}]
    return {'shelfRenderer':{'title':{'simpleText':name},'content':{'horizontalListRenderer':listing}}}


def reel(index):
    return {'overlay':{'reelPlayerOverlayRenderer':{'reelPlayerHeaderSupportedRenderers':{'reelPlayerHeaderRenderer':{
        'reelTitleText':{'simpleText':'Short '+str(index)},'channelTitleText':{'simpleText':'Generated channel'},'timestampText':{'simpleText':'Fixture Short'}}}}},
        'replacementEndpoint':{'reelWatchEndpoint':endpoint(index)},'sequenceContinuation':'shorts-1'}


def storyboard_png(sheet):
    """Small generated contact sheet; no real video frames or external artwork."""
    width,height=400,135
    def chunk(name,data):return struct.pack('!I',len(data))+name+data+struct.pack('!I',zlib.crc32(name+data))
    pixels=bytearray()
    for y in range(height):
        pixels.append(0)
        for x in range(width):
            frame=sheet*15+(y//45)*5+x//80
            color=(40+frame*37%180,40+frame*59%180,40+frame*83%180)
            pixels.extend((230,230,230) if x%80<2 or y%45<2 else color)
    return b'\x89PNG\r\n\x1a\n'+chunk(b'IHDR',struct.pack('!2I5B',width,height,8,2,0,0,0))+chunk(b'IDAT',zlib.compress(pixels))+chunk(b'IEND',b'')


def handle_get(handler,path,query,root):
    if path=='/youtube/complete/search':
        text=parse_qs(query).get('q',[''])[0].strip()[:200]
        with _lock:
            _state['requests'].append({'path':path,'query':text,'authenticated':bool(handler.headers.get('Authorization'))});slow=_state['slow_suggestions']
        if slow:time.sleep(.8)
        values=[text+' videos',text+' music',text+' live',text+' tutorial',text+' documentary',text+' shorts'] if text else []
        payload=")]}'\n"+json.dumps([text,[[value,0] for value in values]],ensure_ascii=False)
        return handler.send(payload.encode('utf-8'),'application/json; charset=utf-8')
    if path.startswith('/youtube/api/lounge/'):return lounge_get(handler,path,parse_qs(query))
    if path in ['/youtube/api/stats/playback','/youtube/api/stats/watchtime']:
        values=parse_qs(query)
        with _lock:_state['requests'].append({'path':path,'authenticated':bool(handler.headers.get('Authorization')),
            **{name:values.get(name,[''])[0] for name in ['docid','cpn','cmt','len','st','et','final']}})
        return handler.send(b'',status=204)
    if path.startswith('/youtube/performance/'):return performance_fixture.handle(handler,path,root)
    if path=='/youtube/control':
        q=parse_qs(query)
        if q.get('reset')==['1']:reset()
        with _lock:
            for name in ['approve','deny','slow','fail','fail_playback','split_media','rejected_media','rejected_fallback','no_heatmap','no_comments','no_chapters','no_storyboard','slow_storyboard','broken_storyboard','fail_details','guest_fail','empty_guest','history_paused','fail_actions','slow_actions','slow_phone','slow_remote','slow_next','slow_suggestions','grouped_search','stateful_account']:
                if name in q:_state[name]=q[name]==['1']
        return handler.send({'ok':True})
    if path=='/youtube/state':return handler.send(snapshot())
    if path=='/youtube/tv':
        return handler.send(b'{"INNERTUBE_CLIENT_VERSION":"7.fixture","visitorData":"fixture"}<script id="base-js" src="/youtube/base.js"></script>','text/html')
    if path=='/youtube/':
        return handler.send(b'{"INNERTUBE_CLIENT_VERSION":"2.fixture","VISITOR_DATA":"fixture-web"}','text/html')
    if path=='/youtube/base.js':
        return handler.send(b'clientId:"fixture.apps.googleusercontent.com",secret:"fixtureSecret"','application/javascript')
    if path.startswith('/youtube/storyboard/'):
        name=path.rsplit('/',1)[-1]
        if name not in ['M0.png','M1.png']:return handler.send(b'',status=404)
        with _lock:
            _state['storyboardRequests'].append(name);slow=_state['slow_storyboard'];broken=_state['broken_storyboard']
        if slow:time.sleep(.8)
        return handler.send(b'',status=404) if broken else handler.send(storyboard_png(int(name[1])),'image/png')
    if path.startswith('/youtube/resolve/'):
        identifier=path.rsplit('/',1)[-1]
        if identifier=='B0000000009':
            return handler.send({'url':'http://10.0.2.2:8878/startup/media/master.m3u8'})
        fallback=parse_qs(query).get('fallback')==['1']
        with _lock:
            _state['resolved'].append(identifier)
            if fallback:_state['fallbackResolved'].append(identifier)
            fail=_state['fail_playback'];slow=_state['slow'];split=_state['split_media'];slow_remote=_state['slow_remote'];rejected=_state['rejected_media'];rejected_fallback=_state['rejected_fallback'];no_storyboard=_state['no_storyboard']
        if slow_remote:time.sleep(3)
        if slow:time.sleep(1)
        if fail:return handler.send({'error':'fixture unavailable'},status=503)
        if (rejected and not fallback) or (fallback and rejected_fallback):return handler.send({'url':'http://10.0.2.2:8878/youtube/media/rejected.m3u8'})
        storyboard={} if no_storyboard or identifier.startswith('S') else {'durationMs':60000,
            'storyboardSpec':'http://10.0.2.2:8878/youtube/storyboard/$N.png|80#45#30#5#3#2000#M$M#fixture'}
        if split:return handler.send({'url':'http://10.0.2.2:8878/youtube/media-split/video.mp4',
            'audioUrl':'http://10.0.2.2:8878/youtube/media-split/audio.m4a','videoMimeType':'video/mp4','audioMimeType':'audio/mp4',**storyboard})
        address='http://10.0.2.2:8878/youtube/media-short/index.m3u8' if identifier.startswith('S') else 'http://10.0.2.2:8878/hls/fixture/orig.m3u8'
        return handler.send({'url':address+'?video='+identifier,**storyboard})
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
    raw=handler.rfile.read(length)
    if path.startswith('/youtube/api/lounge/'):return lounge_post(handler,path,parse_qs(raw.decode()))
    body=json.loads(raw or b'{}')
    if path=='/youtube/phone/command':
        with _phone_changed:
            _state['phone_aid']+=1
            _state['phone_events'].append([_state['phone_aid'],[body.get('type','noop'),body.get('data',{})]])
            _state['phone_events']=_state['phone_events'][-120:];_phone_changed.notify_all()
        return handler.send({'ok':True})
    with _lock:
        _state['requests'].append({'path':path,'query':body.get('query',''),'authenticated':bool(handler.headers.get('Authorization')),
            'client':body.get('context',{}).get('client',{}).get('clientName',''),'continuation':body.get('continuation',''),
            'browseId':body.get('browseId',''),'videoId':body.get('videoId',''),'body':body})
        approved=_state['approve'];denied=_state['deny'];slow=_state['slow'];fail=_state['fail']
    if slow and path.endswith('/search') and body.get('query')=='slow':time.sleep(1)
    if fail:return handler.send({'error':'generated failure'},status=503)
    if path=='/youtube/o/oauth2/device/code':
        return handler.send({'device_code':'fixture-device','user_code':'TV-FIXTURE','verification_url':'https://www.google.com/device','expires_in':1800,'interval':5})
    if path=='/youtube/o/oauth2/token':
        if denied:return handler.send({'error':'access_denied'},status=400)
        if not approved and body.get('grant_type')!='refresh_token':return handler.send({'error':'authorization_pending'},status=400)
        return handler.send({'access_token':'fixture-youtube-access','refresh_token':'fixture-youtube-refresh','expires_in':3600})
    if path.endswith('/player'):
        with _lock:paused=_state['history_paused']
        identifier=body.get('videoId','V0000000001')
        tracking={} if paused else {name:{'baseUrl':'http://10.0.2.2:8878/youtube/api/stats/'+stats_path+'?docid='+identifier+'&ei=fixture-ei&vm=fixture-vm&of=fixture-of'}
            for name,stats_path in [('videostatsPlaybackUrl','playback'),('videostatsWatchtimeUrl','watchtime')]}
        return handler.send({'videoDetails':{'videoId':identifier,'title':'Fixture video','lengthSeconds':'60'},
            'playabilityStatus':{'status':'OK'},'historyPaused':paused,'playbackTracking':tracking})
    action_path=path.removeprefix('/youtube/youtubei/v1/')
    if action_path.startswith(('like/','subscription/','playlist/')) or action_path=='browse/edit_playlist':
        with _lock:slow_action=_state['slow_actions'];failed=_state['fail_actions']
        if slow_action:time.sleep(1)
        if not handler.headers.get('Authorization'):return handler.send({'error':{'message':'Sign in required'}},status=401)
        if failed:return handler.send({'responseContext':{},'alerts':[{'alertRenderer':{'type':'ERROR','text':{'simpleText':'Generated action failure'}}}]})
        with _lock:
            if action_path.startswith('like/'):
                identifier=body.get('target',{}).get('videoId','')
                _state['ratings'][identifier]={'like/like':'LIKE','like/dislike':'DISLIKE'}.get(action_path,'INDIFFERENT')
            elif action_path.startswith('subscription/'):_state['subscribed']=action_path.endswith('/subscribe')
            elif action_path=='playlist/get_add_to_playlist':
                identifier=body.get('videoIds',[''])[0]
                options=[{'playlistAddToOptionRenderer':{'playlistId':key,'title':{'simpleText':value['name']},
                    'containsSelectedVideos':'ALL' if identifier in value['videos'] else 'NONE'}} for key,value in _state['playlists'].items()]
                return handler.send({'responseContext':{},'contents':options})
            elif action_path=='browse/edit_playlist':
                playlist=_state['playlists'].get(body.get('playlistId'))
                if not playlist:return handler.send({'error':{'message':'Playlist missing'}},status=404)
                for action in body.get('actions',[]):
                    if action.get('action')=='ACTION_ADD_VIDEO':playlist['videos']=list(dict.fromkeys(playlist['videos']+[action.get('addedVideoId')]))
                    else:playlist['videos']=[v for v in playlist['videos'] if v!=action.get('removedVideoId')]
            elif action_path=='playlist/create':
                key='PLcreated'+str(len(_state['playlists']));_state['playlists'][key]={'name':body.get('title','Playlist'),'videos':body.get('videoIds',[])}
                return handler.send({'responseContext':{},'playlistId':key})
        return handler.send({'responseContext':{}})
    if path.endswith('/reel_item_watch'):
        identifier=body.get('playerRequest',{}).get('videoId','S0000000001')
        return handler.send(reel(int(identifier[1:])))
    if path.endswith('/reel_watch_sequence'):
        page=int(body.get('sequenceParams','shorts-1').rsplit('-',1)[-1])
        return handler.send({'entries':[{'command':{'reelWatchEndpoint':endpoint(page*12+n)}} for n in range(1,13)],'continuation':'shorts-'+str(page+1)})
    if path.endswith('/browse'):
        identifier=body.get('browseId','')
        if identifier==CHANNEL_ID or body.get('continuation','').startswith('fixture-channel-'):
            tab=body.get('params','home')
            if body.get('continuation'):tab=body['continuation'].removeprefix('fixture-channel-')
            with _lock:subscribed=_state['subscribed']
            start={'home':1,'videos':13,'shorts':25,'live':37}.get(tab,1)+(20 if body.get('continuation') else 0)
            return handler.send({'header':{'c4TabbedHeaderRenderer':{'title':'Fixture creator','channelId':CHANNEL_ID,
                'avatar':{'thumbnails':[{'url':'http://10.0.2.2:8878/logo.png','width':180}]},'subscriberCountText':{'simpleText':'12K subscribers'}}},
                'subscribeButtonRenderer':{'channelId':CHANNEL_ID,'subscribed':subscribed},
                'tabs':[{'tabRenderer':{'title':label,'selected':tab==value,'endpoint':{'browseEndpoint':{'browseId':CHANNEL_ID,'params':value}},
                    **({'content':{'contents':[video(n,tab=='shorts') for n in range(start,start+20)],
                        **({'continuationContents':{'nextContinuationData':{'continuation':'fixture-channel-'+tab}}} if not body.get('continuation') else {})}} if value==tab else {})}}
                    for label,value in [('Home','home'),('Videos','videos'),('Shorts','shorts'),('Live','live')]]})
        if identifier.startswith('FEtopics_'):
            with _lock:guest_fail=_state['guest_fail'];empty_guest=_state['empty_guest']
            if guest_fail:return handler.send({'error':'generated guest failure'},status=503)
            start={'FEtopics_music':100,'FEtopics_gaming':200,'FEtopics_news':300}.get(identifier,400)
            return handler.send({'contents':[] if empty_guest else [video(start+n,prefix='G') for n in range(1,13)]})
        if not handler.headers.get('Authorization'):
            return handler.send({'contents':{'feedNudgeRenderer':{'subtitle':{'simpleText':'Sign in for recommendations, or search.'}}}})
        if identifier=='FEplaylist_aggregation':
            with _lock:playlists=json.loads(json.dumps(_state['playlists']))
            return handler.send({'responseContext':{},'contents':[{'gridPlaylistRenderer':{'playlistId':key,'title':{'simpleText':value['name']},
                'thumbnail':{'thumbnails':[{'url':'http://10.0.2.2:8878/logo.png','width':320,'height':180}]},
                'videoCountText':{'simpleText':str(len(value['videos']))+' videos'},'isEditable':True,
                'navigationEndpoint':{'browseEndpoint':{'browseId':'VL'+key}}}} for key,value in playlists.items()]})
        with _lock:
            playlist=json.loads(json.dumps(_state['playlists'].get(identifier.removeprefix('VL')))) if identifier.startswith('VL') else None
            stateful=_state['stateful_account'];ratings=dict(_state['ratings']);subscribed=_state['subscribed']
            watch_later=list(_state['playlists']['WL']['videos'])
        if playlist is not None:return handler.send({'responseContext':{},'contents':[video_id(v) for v in playlist['videos']]})
        if stateful:
            if identifier=='VLLL':return handler.send({'contents':[video_id(v) for v,rating in ratings.items() if rating=='LIKE']})
            if identifier=='FEmy_youtube':return handler.send({'contents':[video_id(v) for v in watch_later]})
            if identifier=='FEsubscriptions':return handler.send({'contents':[video(1)] if subscribed else []})
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
        with _lock:grouped=_state['grouped_search']
        if grouped:
            if body.get('continuation'):
                return handler.send({'contents':[video(n) for n in range(13,33)]+[video(n,True) for n in range(7,13)]})
            # Real TV search can mix canonical Shorts into the main shelf and
            # place a Shorts shelf first. The app must normalize both sources.
            return handler.send({'contents':{'sectionListRenderer':{'contents':[
                shelf('Shorts',[video(n,True) for n in range(1,5)]),
                shelf('Videos',[video(n) for n in range(1,13)]+[video(n,True) for n in range(3,7)],'fixture-grouped-2'),
                shelf('Recently uploaded',[video(n,prefix='R') for n in range(1,7)])]}}})
        start=33 if body.get('continuation') else 1
        return handler.send({'contents':[video(n) for n in range(start,start+32)],
            **({'continuationContents':{'nextContinuationData':{'continuation':'fixture-page-2'}}} if start==1 else {})})
    if path.endswith('/next'):
        with _lock:
            no_heatmap=_state['no_heatmap'];no_comments=_state['no_comments'];no_chapters=_state['no_chapters'];fail_details=_state['fail_details'];slow_next=_state['slow_next']
        web=body.get('context',{}).get('client',{}).get('clientName')=='WEB'
        if web:
            return handler.send({} if no_heatmap else {'frameworkUpdates':{'entityBatchUpdate':{'mutations':[{'payload':{
                'macroMarkersListEntity':{'markersList':{'markerType':'MARKER_TYPE_HEATMAP','markers':[
                    {'startMillis':str(n*1000),'durationMillis':'1000','intensityScoreNormalized':(n%8)/7} for n in range(20)]}}}}]}}})
        if slow_next:time.sleep(2.5)
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
        with _lock:rating=_state['ratings'].get(body.get('videoId',''),'INDIFFERENT');subscribed=_state['subscribed']
        return handler.send({'contents':{'singleColumnWatchNextResults':{'pivot':{'sectionListRenderer':{'contents':[video(n) for n in range(40,60)]}}}},
            **({} if no_chapters else {'playerOverlays':{'playerOverlayRenderer':{'multiMarkersPlayerBarRenderer':{'markersMap':[{'value':{'chapters':[
                {'chapterRenderer':{'title':{'simpleText':title},'timeRangeStartMillis':start}} for title,start in [('Intro',0),('Main section',10000),('Finale',30000)]]}}]}}}}),
            'videoOwnerRenderer':{'title':{'simpleText':'Fixture creator'},'navigationEndpoint':{'browseEndpoint':{'browseId':CHANNEL_ID}}},
            'subscribeButtonRenderer':{'channelId':CHANNEL_ID,'subscribed':subscribed},
            'segmentedLikeDislikeButtonRenderer':{'likeButton':{'toggleButtonRenderer':{'isToggled':rating=='LIKE'}},
                'dislikeButton':{'toggleButtonRenderer':{'isToggled':rating=='DISLIKE'}}},
            'engagementPanels':[{'engagementPanelSectionListRenderer':{'panelIdentifier':'comment-item-section',
                'header':{'engagementPanelTitleHeaderRenderer':{'title':{'simpleText':'Comments'}}},
                'content':{} if no_comments else {'reloadContinuationData':{'continuation':'fixture-comments-1'}}}},
                {'engagementPanelSectionListRenderer':{'panelIdentifier':'engagement-panel-description','content':{
                    'videoDescriptionBodyRenderer':{'descriptionBodyText':{'simpleText':'A generated video description for the native YouTube watch page.\n\nChapter navigation, captions and playback continue with the same decoder.'}}}}}]})
    return handler.send({'error':'unknown fixture'},status=404)


def lounge_frame(events):
    data=json.dumps(events,separators=(',',':'))
    return (str(len(data))+'\n'+data).encode()


def lounge_get(handler,path,query):
    if path.endswith('/generate_screen_id'):
        with _lock:
            _state['phone_screen']+=1;screen=_state['phone_screen'];_state['phone_events']=[];_state['phone_aid']=4
        return handler.send(('fixture-screen-777tv-'+str(screen)).encode(),'text/plain')
    if path.endswith('/bc/bind'):
        aid=int(query.get('AID',['-1'])[0])
        with _phone_changed:
            events=[e for e in _state['phone_events'] if e[0]>aid]
            if not events:_phone_changed.wait(timeout=2);events=[e for e in _state['phone_events'] if e[0]>aid]
        return handler.send(lounge_frame(events or [[aid,['noop']]]),'text/plain')
    return handler.send({'error':'unknown lounge'},status=404)


def lounge_post(handler,path,form):
    if path.endswith('/get_lounge_token_batch'):
        return handler.send({'screens':[{'screenId':form.get('screen_ids',['fixture-screen-777tv'])[0],'loungeToken':'fixture-lounge-token'}]})
    if path.endswith('/get_pairing_code'):
        with _lock:slow=_state['slow_phone']
        if slow:time.sleep(1)
        return handler.send({'code':'123456789012'})
    if path.endswith('/bc/bind'):
        if form.get('count')==['0']:
            return handler.send(lounge_frame([[0,['c','fixture-sid']],[1,['S','fixture-session']],
                [2,['loungeStatus',{'devices':json.dumps([{'type':'LOUNGE_SCREEN'}])}]],[3,['getNowPlaying']],[4,['getDiscoveryDeviceId']]]),'text/plain')
        with _lock:_state['requests'].append({'path':path,'authenticated':bool(handler.headers.get('Authorization')),
            'phoneCommand':form.get('req0__sc',[''])[0],'fields':{key:value[0] for key,value in form.items()}})
        return handler.send(b'','text/plain')
    return handler.send({'error':'unknown lounge'},status=404)
