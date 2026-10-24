"""Inject a debug-only private bootstrap without credentials in argv/stdout/APK."""
import argparse
import json
import os
import subprocess
from pathlib import Path


def main():
    parser=argparse.ArgumentParser()
    parser.add_argument('--serial', required=True)
    parser.add_argument('--base-url',default='http://127.0.0.1:8765')
    parser.add_argument('--debug-transcript',action='store_true')
    parser.add_argument('--mock',action='store_true')
    parser.add_argument('--simulated-location',action='store_true')
    args=parser.parse_args()
    root=Path(__file__).resolve().parents[2]
    token=os.environ.get('SONAR_ALICE_TOKEN','')
    if not token:
        env=root/'server'/'.env'
        if env.exists():
            for line in env.read_text().splitlines():
                if line.startswith('SONAR_ALICE_TOKEN='): token=line.split('=',1)[1].strip().strip('"\'')
    if not token and not args.mock: raise SystemExit('Missing SONAR_ALICE_TOKEN or server/.env')
    adb=['adb','-s',args.serial]
    subprocess.run(adb+['shell','am','force-stop','com.sonar.app'],check=True,stdout=subprocess.DEVNULL)
    subprocess.run(adb+['shell','run-as','com.sonar.app','mkdir','-p','files'],check=True)
    config={'base_url':args.base_url,'token':token,'mock':args.mock,'debug_transcript':args.debug_transcript,'simulated_location':args.simulated_location}
    subprocess.run(adb+['shell',"run-as com.sonar.app sh -c 'umask 077; cat > files/bootstrap.json'"],input=json.dumps(config).encode(),check=True)
    subprocess.run(adb+['reverse','tcp:8765','tcp:8765'],check=True,stdout=subprocess.DEVNULL)
    subprocess.run(adb+['shell','am','start','-n','com.sonar.app/.MainActivity'],check=True,stdout=subprocess.DEVNULL)
    print('Configured debug app via private bootstrap; credentials were not printed. Launch consumes and encrypts the file.')


if __name__=='__main__': main()
