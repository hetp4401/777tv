"""One bounded build/test job per worker; survives the submitting Mac disconnecting."""
import base64
import fcntl
import hashlib
import json
import os
from pathlib import Path
import shutil
import signal
import subprocess
import sys
import time

DIRECTORY = Path(sys.argv[1])
SPEC = json.loads((DIRECTORY / 'request.json').read_text())
SOURCE = Path('/work/sources') / SPEC['source']
PROJECT = DIRECTORY / 'project'
OUT = DIRECTORY / 'out'
CACHE = Path('/cache')
ENV = dict(os.environ, FLEET_TOOLS_DIR='/cache/tools', ANDROID_HOME='/cache/android-sdk',
    GRADLE_USER_HOME='/cache/gradle', ANDROID_AVD_HOME='/cache/avd',
    ANDROID_USER_HOME='/cache/android-user', ANDROID_EMULATOR_HOME='/cache/android-user',
    ADB_VENDOR_KEYS='/cache/android-user/adbkey')
PROCESSES = []


def run(args, timeout=900, input=None, check=True):
    print('$ ' + ' '.join(map(str, args)), flush=True)
    p = subprocess.Popen(list(map(str, args)), cwd=PROJECT, env=ENV, stdin=subprocess.PIPE if input else subprocess.DEVNULL,
        stdout=sys.stdout, stderr=subprocess.STDOUT, start_new_session=True)
    try:
        p.communicate(input=input, timeout=timeout)
    except subprocess.TimeoutExpired:
        os.killpg(p.pid, signal.SIGKILL);p.wait();raise
    if check and p.returncode: raise RuntimeError(f'Command exited {p.returncode}: {args[0]}')
    return p.returncode


def background(args, log):
    output = (OUT / log).open('wb')
    p = subprocess.Popen(list(map(str, args)), cwd=PROJECT, env=ENV, stdout=output, stderr=subprocess.STDOUT, start_new_session=True)
    output.close();PROCESSES.append(p)
    return p


def copy_artifacts():
    for directory, target in [('app/build/test-results','unit'),('app/build/reports/lint-results-debug.html','lint.html'),
        ('app/build/outputs/apk','apk')]:
        path = PROJECT / directory
        if path.is_dir(): shutil.copytree(path,OUT / target,dirs_exist_ok=True)
        elif path.is_file(): shutil.copyfile(path,OUT / target)
    # Preserve fixture frames through gateways that reject PNG subpaths.
    frames={p.relative_to(OUT).as_posix():base64.b64encode(p.read_bytes()).decode('ascii')
        for p in sorted((OUT/'screenshots').rglob('*.png')) if p.is_file()}
    if frames:(OUT/'fixture-frames.json').write_text(json.dumps(frames))


def prepare(ui):
    run(['python3','scripts/bootstrap.py'],timeout=900)
    ENV['JAVA_HOME'] = '/cache/tools/jdk'
    ENV['PATH'] = '/cache/tools/jdk/bin:/cache/tools/gradle/bin:/cache/android-sdk/cmdline-tools/latest/bin:/cache/android-sdk/platform-tools:/cache/android-sdk/emulator:' + ENV['PATH']
    sdk = Path(ENV['ANDROID_HOME'])
    if not (sdk / 'build-tools/36.0.0/aapt').exists() or not (sdk / 'platforms/android-36/android.jar').exists():
        run(['sdkmanager','--licenses'],input=b'y\n'*200,timeout=300)
        run(['sdkmanager','platforms;android-36','build-tools;36.0.0','platform-tools'],timeout=900)
    if ui:
        if not (sdk/'emulator/emulator').exists() or not (sdk/'system-images/android-36/android-tv/x86_64/system.img').exists():
            run(['sdkmanager','emulator','system-images;android-36;android-tv;x86_64'],timeout=1200)
        run(['emulator','-accel-check'])


def ui_tests():
    run(['./gradlew','--no-daemon','--build-cache','--max-workers=2','-Dorg.gradle.jvmargs=-Xmx2g','-Pkotlin.compiler.execution.strategy=in-process',
        '-PfleetAbis=x86_64',':app:assembleDebug',':app:assembleDebugAndroidTest'],timeout=1200)
    Path('/cache/avd').mkdir(exist_ok=True)
    if not Path('/cache/avd/FleetTVRemote.ini').exists():
        run(['avdmanager','create','avd','--name','FleetTVRemote','--package','system-images;android-36;android-tv;x86_64','--device','tv_1080p'],input=b'no\n',timeout=120)
    # A new worker has no host ADB key. Seed it before the emulator injects
    # its trusted public key; generating one afterward leaves the guest
    # unauthorized and cannot be confirmed on a headless test machine.
    android_user=Path(ENV['ANDROID_USER_HOME']);android_user.mkdir(mode=0o700,parents=True,exist_ok=True)
    adb_key=android_user/'adbkey'
    if not adb_key.exists():run(['adb','keygen',str(adb_key)],timeout=30)
    adb_key.chmod(0o600)
    run(['adb','kill-server'],check=False,timeout=30)
    run(['adb','start-server'],timeout=30)
    media = CACHE / 'fixture-media-v2';media.mkdir(exist_ok=True)
    (PROJECT / '.tools').mkdir(exist_ok=True)
    (PROJECT / '.tools/fixture-media-v2').symlink_to(media,target_is_directory=True)
    background(['python3','scripts/fixture_server.py'],'fixture.log')
    background(['emulator','-avd','FleetTVRemote','-port','5554','-no-window','-no-audio','-no-snapshot','-wipe-data',
        '-gpu','swiftshader','-accel','on','-memory','2048','-cores','2','-no-boot-anim'],'emulator.log')
    adb = ['adb','-s','emulator-5554']
    # A transport can briefly reconnect while the emulator boots. Keep these
    # bounded startup retries separate from instrumentation success/failure.
    for attempt in range(3):
        try:
            if run(adb+['wait-for-device'],timeout=120,check=False)==0:break
        except subprocess.TimeoutExpired:
            pass
        if attempt==2:raise RuntimeError('Android TV ADB did not become available')
        time.sleep(2)
    deadline = time.monotonic()+240
    while time.monotonic()<deadline:
        result=subprocess.run(adb+['shell','getprop','sys.boot_completed'],env=ENV,capture_output=True,text=True,timeout=15)
        if result.stdout.strip()=='1':break
        time.sleep(2)
    else: raise RuntimeError('Remote Android TV did not boot')
    for name in ['window_animation_scale','transition_animation_scale','animator_duration_scale']:
        # These only improve test timing; a transient Settings-provider startup
        # failure must not be reported as an app/instrumentation failure.
        for attempt in range(3):
            if run(adb+['shell','settings','put','global',name,'0'],check=False)==0:break
            time.sleep(1)
        else:print('Animation timing preference unavailable: '+name,flush=True)
    # TV emulators have no lock screen; Menu is only a best-effort wake-up.
    # The input service can still be starting just after boot_completed.
    for attempt in range(3):
        if run(adb+['shell','input','keyevent','82'],check=False)==0:break
        time.sleep(1)
    else:print('Optional emulator wake-up unavailable; continuing to APK install.',flush=True)
    run(adb+['install','-r','app/build/outputs/apk/debug/app-debug.apk'])
    run(adb+['install','-r','app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk'])
    with (OUT/'instrumentation.log').open('wb') as log:
        p=subprocess.Popen(adb+['shell','am','instrument','-w','-r','-e','renderer','remote-swiftshader','-e','class',SPEC['classes'],
            'xyz.billybishop4.fleet.tv.test/androidx.test.runner.AndroidJUnitRunner'],env=ENV,stdout=log,stderr=subprocess.STDOUT,start_new_session=True)
        try:p.wait(timeout=1800)
        except subprocess.TimeoutExpired:os.killpg(p.pid,signal.SIGKILL);p.wait();raise
    run(adb+['pull','/sdcard/Download',str(OUT/'screenshots')],check=False)
    run(adb+['pull','/sdcard/Android/data/xyz.billybishop4.fleet.tv/files',str(OUT/'device-files')],check=False)
    run(adb+['logcat','-d','-t','1000'],check=False)
    output=(OUT/'instrumentation.log').read_text()
    print(output[-10000:],flush=True)
    if 'FAILURES!!!' in output or 'Process crashed' in output or not __import__('re').search(r'OK \(\d+ tests?\)',output):
        raise RuntimeError('Remote instrumentation did not pass')


def main():
    CACHE.mkdir(exist_ok=True);OUT.mkdir(exist_ok=True)
    started=time.time()
    state='passed';error=None
    with (CACHE/'job.lock').open('w') as lock:
        fcntl.flock(lock,fcntl.LOCK_EX)
        try:
            shutil.copytree(SOURCE,PROJECT)
            prepare(SPEC['task']=='ui')
            gradle=['./gradlew','--no-daemon','--build-cache','--max-workers=2','-Dorg.gradle.jvmargs=-Xmx2g','-Pkotlin.compiler.execution.strategy=in-process']
            if SPEC['task']=='build':
                run(gradle+['-PfleetUnsigned=true',':app:assembleRelease'],timeout=1200)
            elif SPEC['task']=='unit':run(gradle+[':app:testDebugUnitTest',':app:lintDebug'],timeout=1200)
            else:ui_tests()
        except Exception as e:
            state='failed';error=str(e);print('JOB FAILED:',error,flush=True)
        finally:
            for p in reversed(PROCESSES):
                if p.poll() is None:
                    os.killpg(p.pid,signal.SIGTERM)
                    try:p.wait(timeout=10)
                    except subprocess.TimeoutExpired:os.killpg(p.pid,signal.SIGKILL);p.wait()
            copy_artifacts()
    shutil.copyfile(DIRECTORY/'job.log',OUT/'job.log')
    artifacts=[]
    for path in sorted(OUT.rglob('*')):
        if path.is_file():artifacts.append({'path':path.relative_to(OUT).as_posix(),'bytes':path.stat().st_size,'sha256':hashlib.file_digest(path.open('rb'),'sha256').hexdigest()})
    report={'state':state,'error':error,'elapsedSeconds':round(time.time()-started,2),'artifacts':artifacts}
    temp=DIRECTORY/'result.tmp';temp.write_text(json.dumps(report,indent=2));temp.replace(DIRECTORY/'result.json')


if __name__=='__main__':main()
