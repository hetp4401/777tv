"""Authenticated, disposable build boxes. Source and jobs are scoped to /work."""
import hashlib
import hmac
import io
import json
import os
from pathlib import Path
import re
import socket
import subprocess
import sys
import tarfile
import uuid
from aiohttp import web

ROOT = Path('/work')
SOURCES = ROOT / 'sources'
JOBS = ROOT / 'jobs'
TOKEN = os.environ['DEV_TOKEN']
INSTANCE = str(uuid.uuid4())
assert len(TOKEN) >= 32
for directory in (SOURCES, JOBS): directory.mkdir(parents=True, exist_ok=True)


def kvm():
    try:
        import fcntl
        fd = os.open('/dev/kvm', os.O_RDWR)
        try: return fcntl.ioctl(fd, 0xAE00) == 12
        finally: os.close(fd)
    except OSError: return False


@web.middleware
async def auth(request, handler):
    if request.path != '/health' and not hmac.compare_digest(request.headers.get('Authorization', ''), 'Bearer ' + TOKEN):
        raise web.HTTPUnauthorized()
    return await handler(request)


async def health(request):
    return web.json_response({'ok': True, 'instance': INSTANCE, 'host': socket.gethostname(),
        'replica': os.environ.get('FLEET_REPLICA'), 'cpus': os.cpu_count(), 'kvm': kvm()})


async def source(request):
    raw = await request.read()
    digest = hashlib.sha256(raw).hexdigest()
    if digest != request.headers.get('X-Source-SHA256'): raise web.HTTPBadRequest(text='Source digest mismatch')
    dest = SOURCES / digest
    if not dest.exists():
        stage = SOURCES / ('.' + uuid.uuid4().hex)
        with tarfile.open(fileobj=io.BytesIO(raw), mode='r:gz') as archive:
            members = archive.getmembers()
            if len(members) > 5000 or sum(m.size for m in members) > 32 * 1024 * 1024: raise web.HTTPBadRequest(text='Source too large')
            for m in members:
                if not (m.isfile() or m.isdir()) or not (stage / m.name).resolve().is_relative_to(stage.resolve()):
                    raise web.HTTPBadRequest(text='Invalid source member')
            archive.extractall(stage, filter='data')
        stage.rename(dest)
    return web.json_response({'source': digest, 'ready': True})


async def submit(request):
    spec = await request.json()
    digest, task = spec.get('source', ''), spec.get('task', '')
    if not re.fullmatch('[0-9a-f]{64}', digest) or not (SOURCES / digest / 'remote/job.py').is_file():
        raise web.HTTPBadRequest(text='Sync source first')
    if task not in ('build', 'unit', 'ui'): raise web.HTTPBadRequest(text='Unknown task')
    if task == 'ui' and not kvm(): raise web.HTTPConflict(text='KVM unavailable on this worker')
    classes = spec.get('classes', 'xyz.billybishop4.fleet.tv.TvFlowTest,xyz.billybishop4.fleet.tv.EngineLayoutTest,xyz.billybishop4.fleet.tv.CategoryVisibilityTest')
    if not isinstance(classes, str) or not re.fullmatch('[A-Za-z0-9_.,#]+', classes): raise web.HTTPBadRequest(text='Invalid test classes')
    job = uuid.uuid4().hex
    directory = JOBS / job
    directory.mkdir()
    config = {'id': job, 'source': digest, 'task': task, 'classes': classes, 'instance': INSTANCE}
    (directory / 'request.json').write_text(json.dumps(config))
    with (directory / 'job.log').open('wb') as log:
        proc = subprocess.Popen([sys.executable, str(SOURCES / digest / 'remote/job.py'), str(directory)],
            stdout=log, stderr=subprocess.STDOUT, start_new_session=True)
    config['pid'] = proc.pid
    (directory / 'receipt.json').write_text(json.dumps(config))
    return web.json_response(config)


def jobdir(request):
    job = request.match_info['job']
    if not re.fullmatch('[0-9a-f]{32}', job) or not (JOBS / job).is_dir(): raise web.HTTPNotFound()
    return JOBS / job


async def status(request):
    directory = jobdir(request)
    receipt = json.loads((directory / 'receipt.json').read_text())
    result = directory / 'result.json'
    data = json.loads(result.read_text()) if result.exists() else {'state': 'running' if receipt['instance'] == INSTANCE else 'interrupted'}
    data.update({'id': directory.name, 'source': receipt['source'], 'task': receipt['task'], 'instance': receipt['instance']})
    log = (directory / 'job.log').read_bytes()
    data['logTail'] = log[-12000:].decode('utf-8', 'replace')
    return web.json_response(data)


async def file(request):
    directory = jobdir(request)
    base = directory / 'out'
    path = (base / request.match_info['path']).resolve()
    if not path.is_relative_to(base.resolve()) or not path.is_file(): raise web.HTTPNotFound()
    return web.FileResponse(path, headers={'Cache-Control': 'no-store'})


app = web.Application(middlewares=[auth], client_max_size=4 * 1024 * 1024)
app.router.add_get('/health', health)
app.router.add_post('/sources', source)
app.router.add_post('/jobs', submit)
app.router.add_get('/jobs/{job}', status)
app.router.add_get('/artifacts/{job}/{path:.*}', file)
web.run_app(app, host='0.0.0.0', port=8879, access_log=None)
