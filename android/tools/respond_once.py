"""Local test peer, not ESP32 firmware. Sends synthetic silence with a labelled transcript."""
import argparse
import io
import json
import time
import uuid
import wave
from pathlib import Path
import urllib.request


def main():
    parser=argparse.ArgumentParser()
    parser.add_argument('--base-url',default='http://127.0.0.1:8765')
    parser.add_argument('--synthetic',required=True,action='store_true')
    parser.add_argument('--wait',type=int,default=120)
    args=parser.parse_args()
    values={}
    for line in (Path(__file__).resolve().parents[2]/'server'/'.env').read_text().splitlines():
        if '=' in line and not line.startswith('#'):k,v=line.split('=',1);values[k]=v.strip()
    token=values['SONAR_BOB_TOKEN']
    def call(path,data=None,content_type='application/json',method=None):
        request=urllib.request.Request(args.base_url+path,data=data,headers={'Authorization':'Bearer '+token,'Content-Type':content_type},method=method)
        with urllib.request.urlopen(request,timeout=10) as response:return json.load(response)
    p=call('/v1/me')['profile']
    call('/v1/me/context',json.dumps({'expected_revision':p['revision'],'presence':{'place_id':'zhonghuan_jinke','direction':'demo_exit','source':'simulated'},'assist_enabled':True,'mode':'listen','focus':'愿意回答附近路口的拥堵问题'}).encode(),method='PUT')
    print('Test peer ready: waiting for a real Android HTTP question; reply audio will be synthetic.',flush=True)
    end=time.monotonic()+args.wait
    while time.monotonic()<end:
        items=call('/v1/inbox')['items']
        invitation=next((d for d in items if d['type']=='invitation' and d['actionable']),None)
        if invitation:
            time.sleep(4) # Let the Android waiting state be observed.
            did=invitation['id'];qid=invitation['question_id']
            call(f'/v1/inbox/{did}/ack',json.dumps({'event':'alerted','request_id':invitation['request_id']}).encode())
            audio=io.BytesIO()
            with wave.open(audio,'wb') as w:w.setnchannels(1);w.setsampwidth(2);w.setframerate(16000);w.writeframes(bytes(32000))
            metadata={'client_request_id':str(uuid.uuid4()),'kind':'answer','transcript':'[联调模拟回答] 好堵啊，下匝道都要十五分钟。','reply_to':qid,'invitation_id':did}
            boundary='sonar-'+uuid.uuid4().hex
            body=(f'--{boundary}\r\nContent-Disposition: form-data; name="metadata"\r\n\r\n'+json.dumps(metadata,ensure_ascii=False)+f'\r\n--{boundary}\r\nContent-Disposition: form-data; name="audio"; filename="synthetic.wav"\r\nContent-Type: audio/wav\r\n\r\n').encode()+audio.getvalue()+f'\r\n--{boundary}--\r\n'.encode()
            draft=call('/v1/posts/drafts',body,'multipart/form-data; boundary='+boundary)
            result=call('/v1/posts/'+draft['post_id']+'/confirm',b'{}')
            print(json.dumps({'question_id':qid,'answer_id':result['post_id'],'peer':'local_http_simulator','audio':'synthetic_silence','confirmation':'simulated'}),flush=True)
            return
        time.sleep(.5)
    raise SystemExit('No invitation before test deadline')


if __name__=='__main__':main()
