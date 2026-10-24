"""Opt-in emulator test: real AMap, injected GPS, isolated users and synthetic HTTP answer.

Run with server's Python: uv run --project server python android/tools/run_nearby_flow.py
--serial 127.0.0.1:5555. Requires ADB_SERVER_SOCKET / PATH for the chosen ADB server.
Never uses or resets the application's normal database. Never prints credentials.
"""
import argparse
import io
import json
import os
import secrets
import socket
import sqlite3
import subprocess
import sys
import tempfile
import threading
import time
import uuid
import wave
from datetime import UTC, datetime
from pathlib import Path

import httpx

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / 'server'))


def serve(path):
    import uvicorn
    from app.config import Settings
    from app.main import create_app
    config = json.loads(Path(path).read_text())
    app = create_app(Settings(_env_file=None, **config))
    app.state.service.seed()
    uvicorn.run(app, host='127.0.0.1', port=8766, access_log=False, log_level='warning')


def inject_gps(port):
    # Android's authenticated local emulator console; ADB TCP aliases cannot use `adb emu`.
    with socket.create_connection(('127.0.0.1', port), timeout=3) as connection:
        def response():
            output = b''
            while not output.endswith(b'OK\r\n') and b'KO:' not in output:
                chunk = connection.recv(4096)
                if not chunk:
                    raise RuntimeError('Emulator console disconnected')
                output += chunk
            if b'KO:' in output:
                raise RuntimeError('Emulator rejected console command')
        response()
        token = (Path.home()/'.emulator_console_auth_token').read_text().strip()
        connection.sendall(('auth '+token+'\n').encode())
        response()
        connection.sendall(b'geo fix 121.5 31.2\n')
        response()


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--serial')
    parser.add_argument('--serve')
    parser.add_argument('--console-port',type=int,default=5554)
    parser.add_argument('--two-clients',action='store_true')
    parser.add_argument('--decisions',action='store_true')
    args = parser.parse_args()
    if args.serve:
        serve(args.serve)
        return
    if not args.serial:
        parser.error('--serial is required (emulator only)')
    from app.config import Settings
    settings = Settings(_env_file=ROOT / 'server/.env')
    key = settings.amap_key
    if args.decisions and not settings.bamboo_api_key:
        raise SystemExit('Configure SONAR_BAMBOO_API_KEY first')
    if not key:
        raise SystemExit('Configure SONAR_AMAP_KEY in server/.env first')
    adb = ['adb', '-s', args.serial]
    report = ROOT / ('android/build/reports/decision-flow' if args.decisions else 'android/build/reports/nearby-flow')
    report.mkdir(parents=True, exist_ok=True)
    # Explicit emulator injection; never pass this as physical GPS acceptance.
    if subprocess.check_output(adb+['shell','getprop','ro.kernel.qemu'],text=True).strip() != '1':
        raise SystemExit('This check requires an Android emulator, not a physical phone')
    inject_gps(args.console_port)
    with tempfile.TemporaryDirectory(prefix='sonar-location-check-') as temp:
        config = {'data_dir': str(Path(temp) / 'data'), 'amap_key': key,
                  'alice_token': secrets.token_urlsafe(32), 'bob_token': secrets.token_urlsafe(32),
                  'allow_mock_location': True, 'decision_enabled': args.decisions,
                  'bamboo_api_key': settings.bamboo_api_key if args.decisions else ''}
        path = Path(temp) / 'private.json'
        fd = os.open(path, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
        with os.fdopen(fd, 'w') as f:
            json.dump(config, f)
        server = subprocess.Popen([sys.executable, str(Path(__file__).resolve()), '--serve', str(path)])
        stop = threading.Event()
        peer_errors = []
        try:
            base = 'http://127.0.0.1:8766'
            for _ in range(100):
                try:
                    if httpx.get(base+'/healthz', timeout=1).status_code == 200:
                        break
                except httpx.HTTPError:
                    pass
                if server.poll() is not None:
                    raise RuntimeError('Isolated server failed to start')
                time.sleep(.1)
            else:
                raise RuntimeError('Isolated server not ready')
            def call(user, route, data=None, method=None, **kw):
                with httpx.Client(base_url=base, headers={'Authorization': 'Bearer '+config[user+'_token']}, timeout=15) as client:
                    r = client.request(method or ('POST' if data is not None or 'files' in kw else 'GET'), route, json=data, data=kw.pop('form', None), **kw)
                    if r.status_code >= 400:
                        raise RuntimeError(f'Check failed: {r.status_code} {r.json().get("error",{}).get("code", "HTTP_ERROR")}')
                    return r.json()
            def attention_for(qid):
                with sqlite3.connect(Path(temp)/'data/sonar.sqlite3') as db:
                    rows=db.execute("SELECT attention_json,state FROM deliveries WHERE question_id=? AND type='invitation'",(qid,)).fetchall()
                return [(json.loads(r[0]) if r[0] else None,r[1]) for r in rows]
            def synthetic_question(target,text):
                audio=io.BytesIO()
                with wave.open(audio,'wb') as w:
                    w.setnchannels(1);w.setsampwidth(2);w.setframerate(16000);w.writeframes(bytes(3200))
                meta={'client_request_id':str(uuid.uuid4()),'kind':'question','transcript':text,'target_place_id':target,'direction':'unknown'}
                draft=call('alice','/v1/posts/drafts',files={'audio':('synthetic.wav',audio.getvalue(),'audio/wav')},form={'metadata':json.dumps(meta)})
                return call('alice','/v1/posts/'+draft['post_id']+'/confirm',{})['question_id']
            negative_proof={}
            if args.decisions:
                bob=call('bob','/v1/me')['profile']
                context=call('bob','/v1/me/location',{'expected_revision':bob['revision'],'location':{
                    'latitude':31.2,'longitude':121.5,'coordinate_system':'wgs84','source':'android_mock',
                    'accuracy_m':10,'sampled_at':datetime.now(UTC).isoformat()}},method='PUT')
                target=context['places'][0]['id']
                unrelated=synthetic_question(target,'[决策反例] 景区门口哪家冰淇淋最好吃？')
                end=time.monotonic()+10
                while True:
                    records=attention_for(unrelated)
                    if records and records[0][0] is not None: break
                    if time.monotonic()>end: raise RuntimeError('Decision negative check timed out')
                    time.sleep(.1)
                assert records[0][0]['action']=='ignore', 'Unrelated topic was not ignored'
                assert not call('bob','/v1/inbox')['items']
                negative_proof['unrelated']=records[0][0]['action']
                call('alice','/v1/questions/'+unrelated+'/cancel',{})
                bob=call('bob','/v1/me')['profile']
                call('bob','/v1/me/context',{'expected_revision':bob['revision'],'assist_enabled':True,'mode':'quiet','focus':bob['focus']},method='PUT')
                quiet=synthetic_question(target,'[静音反例] 附近现在堵不堵？')
                assert not attention_for(quiet), 'Quiet user received model candidate'
                assert not call('bob','/v1/inbox')['items']
                negative_proof['quiet_invitation_count']=0
                call('alice','/v1/questions/'+quiet+'/cancel',{})
                bob=call('bob','/v1/me')['profile']
                call('bob','/v1/me/context',{'expected_revision':bob['revision'],'assist_enabled':True,'mode':'listen','focus':bob['focus']},method='PUT')
            def peer():
                try:
                    last = 0
                    last_gps = 0
                    while not stop.is_set():
                        if time.monotonic()-last_gps > 3:
                            inject_gps(args.console_port)
                            last_gps=time.monotonic()
                        if args.two_clients:
                            stop.wait(.5)
                            continue
                        # This is an explicitly synthetic responder at the emulator's test point.
                        if time.monotonic()-last > 20:
                            p = call('bob', '/v1/me')['profile']
                            call('bob', '/v1/me/location', {
                                'expected_revision': p['revision'], 'location': {
                                    'latitude':31.2, 'longitude':121.5, 'coordinate_system':'wgs84',
                                    'source':'android_mock','accuracy_m':10,
                                    'sampled_at':datetime.now(UTC).isoformat()}}, method='PUT')
                            last=time.monotonic()
                        invitations = call('bob','/v1/inbox')['items']
                        inv = next((d for d in invitations if d['type']=='invitation'),None)
                        if not inv:
                            stop.wait(.5)
                            continue
                        if inv['payload'].get('location_scope') != 'nearby':
                            raise RuntimeError('Expected spatial invitation, not simulated place equality')
                        stop.wait(4)
                        call('bob',f"/v1/inbox/{inv['id']}/ack",{'event':'alerted','request_id':inv['request_id']})
                        audio=io.BytesIO()
                        with wave.open(audio,'wb') as w:
                            w.setnchannels(1);w.setsampwidth(2);w.setframerate(16000);w.writeframes(bytes(32000))
                        meta={'client_request_id':str(uuid.uuid4()),'kind':'answer','transcript':'[附近联调模拟回答] 这里现在有点堵。',
                              'reply_to':inv['question_id'],'invitation_id':inv['id']}
                        result=call('bob','/v1/posts/drafts',files={'audio':('synthetic.wav',audio.getvalue(),'audio/wav')},
                                    form={'metadata':json.dumps(meta)})
                        call('bob',f"/v1/posts/{result['post_id']}/confirm",{})
                        (report/'peer.json').write_text(json.dumps({'target':inv['payload']['place_name'],
                            'scope':'nearby','gps':'injected emulator coordinates','map':'live AMap',
                            'responder':'synthetic HTTP peer','reply_audio':'silence WAV'},ensure_ascii=False,indent=2))
                        return
                except Exception as exc:
                    peer_errors.append(type(exc).__name__+': '+str(exc))
            for apk in ['app/build/outputs/apk/debug/app-debug.apk','app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk']:
                subprocess.run(adb+['install','-r',str(ROOT/'android'/apk)],check=True,stdout=subprocess.DEVNULL)
            subprocess.run(adb+['shell','am','force-stop','com.sonar.app'],check=True)
            subprocess.run(adb+['shell','run-as','com.sonar.app','mkdir','-p','files'],check=True)
            bootstrap={'base_url':'http://127.0.0.1:8766','token':config['alice_token'],
                       'debug_transcript':True,'mock':False,'simulated_location':False}
            subprocess.run(adb+['shell',"run-as com.sonar.app sh -c 'umask 077; cat > files/bootstrap.json'"],
                           input=json.dumps(bootstrap).encode(),check=True)
            peer_config={**bootstrap,'token':config['bob_token']}
            subprocess.run(adb+['shell',"run-as com.sonar.app sh -c 'umask 077; cat > files/nearby-peer.json'"],
                           input=json.dumps(peer_config).encode(),check=True)
            subprocess.run(adb+['reverse','tcp:8766','tcp:8766'],check=True,stdout=subprocess.DEVNULL)
            worker=threading.Thread(target=peer,daemon=True)
            worker.start()
            print('Testing injected Android GPS + live AMap + isolated nearby Q&A; reply audio/confirmation are synthetic.',flush=True)
            result=subprocess.run(adb+['shell','am','instrument','-w','-e','live','true','-e','nearby','true',
                '-e','two_clients','true' if args.two_clients else 'false','-e','decisions','true' if args.decisions else 'false','com.sonar.app.test/androidx.test.runner.AndroidJUnitRunner'],capture_output=True,text=True,timeout=150)
            output=result.stdout+result.stderr
            (report/'instrumentation.txt').write_text(output)
            print(output,flush=True)
            if args.two_clients:
                stop.set()
            worker.join(timeout=10)
            if peer_errors:
                raise RuntimeError('; '.join(peer_errors))
            with sqlite3.connect(Path(temp)/'data/sonar.sqlite3') as diagnostic_db:
                diagnostic_db.row_factory=sqlite3.Row
                diagnostic={
                    'profiles':[dict(r) for r in diagnostic_db.execute('SELECT user_id,place_id,source,updated_at,sampled_at,accuracy_m,mode,assist_enabled,focus,revision FROM profiles')],
                    'posts':[dict(r) for r in diagnostic_db.execute('SELECT id,author_id,kind,transcript,question_status,created_at,published_at,expires_at FROM posts')],
                    'deliveries':[dict(r) for r in diagnostic_db.execute('SELECT recipient_id,question_id,type,state,reason_code,profile_revision,expires_at,attention_json FROM deliveries')],
                }
                (report/'diagnostics.json').write_text(json.dumps(diagnostic,ensure_ascii=False,indent=2))
            if 'OK (2 tests)' not in output or result.returncode or worker.is_alive():
                raise RuntimeError('Device check failed; inspect '+str(report))
            with (report/'answered.png').open('wb') as f:
                subprocess.run(adb+['exec-out','run-as','com.sonar.app','cat','files/test-live-answer.png'],stdout=f,check=True)
            if args.two_clients:
                with (report/'invitation.png').open('wb') as f:
                    subprocess.run(adb+['exec-out','run-as','com.sonar.app','cat','files/test-nearby-invite.png'],stdout=f,check=True)
                qid=call('alice','/v1/me')['recent_question_ids'][0]
                q=call('alice','/v1/questions/'+qid)
                assert 'bob' in q['answer']['author_label']
                (report/'peer.json').write_text(json.dumps({'target':q['target_place']['label'],
                    'scope':'nearby','gps':'injected emulator coordinates','map':'live AMap',
                    'responder':'two Android user sessions on one emulator','reply_audio':'emulator AudioRecord, no human speech'},ensure_ascii=False,indent=2))
            if args.decisions:
                qid=call('alice','/v1/me')['recent_question_ids'][0]
                records=attention_for(qid)
                assert len(records)==1 and records[0][0]['action']=='notify' and records[0][1]=='answered'
                (report/'decisions.json').write_text(json.dumps({'provider':'bamboo','model':records[0][0]['model'],
                    'positive_action':'notify','positive_invitation_state':'answered','negative_checks':negative_proof,
                    'input':'injected GPS and test transcripts','client':'Android two user sessions'},ensure_ascii=False,indent=2))
            print('PASS: Android location API, live POI association, nearby invitation, question/answer and playback callback. Physical GPS, human STT and speaker still need acceptance.')
        finally:
            stop.set()
            server.terminate()
            server.wait(timeout=10)
            # The temporary credentials are deliberately expired; restore normal configuration explicitly.
            subprocess.run(adb+['shell','am','force-stop','com.sonar.app'],check=False)
            subprocess.run(adb+['shell','run-as','com.sonar.app','rm','-f','files/nearby-peer.json'],check=False)


if __name__ == '__main__':
    main()
