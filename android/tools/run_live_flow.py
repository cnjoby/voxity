"""Opt-in emulator/device HTTP flow with synthetic peer, preserving app test configuration."""
import argparse
import subprocess
import sys
from pathlib import Path


def main():
    parser=argparse.ArgumentParser()
    parser.add_argument('--serial',required=True)
    parser.add_argument('--synthetic-peer',required=True,action='store_true')
    parser.add_argument('--base-url',default='http://127.0.0.1:8765')
    args=parser.parse_args()
    root=Path(__file__).resolve().parents[1]
    report=root/'build'/'reports'/'live-flow';report.mkdir(parents=True,exist_ok=True)
    adb=['adb','-s',args.serial]
    for path in ('app/build/outputs/apk/debug/app-debug.apk','app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk'):
        subprocess.run(adb+['install','-r',str(root/path)],check=True)
    subprocess.run([sys.executable,str(root/'tools/configure_device.py'),'--serial',args.serial,'--base-url',args.base_url,'--debug-transcript'],check=True)
    subprocess.run(adb+['shell','pm','grant','com.sonar.app','android.permission.RECORD_AUDIO'],check=True)
    peer=subprocess.Popen([sys.executable,str(root/'tools/respond_once.py'),'--base-url',args.base_url,'--synthetic','--wait','120'],stdout=subprocess.PIPE,stderr=subprocess.STDOUT,text=True)
    try:
        line=peer.stdout.readline()
        print(line.strip(),flush=True)
        if 'Test peer ready' not in line: raise RuntimeError('Test peer failed before initialization')
        result=subprocess.run(adb+['shell','am','instrument','-w','-e','live','true','com.sonar.app.test/androidx.test.runner.AndroidJUnitRunner'],capture_output=True,text=True,timeout=120)
        output=result.stdout+result.stderr
        (report/'instrumentation.txt').write_text(output)
        print(output,flush=True)
        if 'OK (2 tests)' not in output or result.returncode: raise RuntimeError('Device tests did not pass; inspect instrumentation.txt')
        peer_output=peer.communicate(timeout=10)[0]
        (report/'peer.txt').write_text(line+peer_output)
        print(peer_output,flush=True)
        if peer.returncode: raise RuntimeError('Synthetic HTTP peer failed')
        with (report/'answered.png').open('wb') as out:
            subprocess.run(adb+['exec-out','run-as','com.sonar.app','cat','files/test-live-answer.png'],stdout=out,check=True)
        print('PASS: live HTTP flow and private recovery on device; peer audio/confirmation are synthetic; STT and physical speaker not verified.')
    finally:
        if peer.poll() is None:peer.terminate()
        peer.communicate(timeout=5)


if __name__=='__main__':main()
