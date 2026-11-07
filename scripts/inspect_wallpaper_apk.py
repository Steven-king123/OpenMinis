"""Advisory package inventory, not a gate for saving the wallpaper test APK."""
from pathlib import Path
import hashlib,json,zipfile,subprocess,os
root=Path(__file__).resolve().parents[1]
apk=root/'src/android/app/build/outputs/apk/github/debug/app-github-debug.apk'
out=root/'apk-diagnostics';out.mkdir(exist_ok=True)
with zipfile.ZipFile(apk) as z:
 names=sorted(z.namelist())
 (out/'apk-files.txt').write_text('\n'.join(names)+'\n')
 required=['lib/arm64-v8a/libproot.so','lib/arm64-v8a/libproot-loader.so','lib/arm64-v8a/libproot-loader32.so','lib/arm64-v8a/libgojni.so','assets/alpine-minirootfs.tar.gz']
 missing=[p for p in required if p not in names]
 report={'sha256':hashlib.sha256(apk.read_bytes()).hexdigest(),'bytes':apk.stat().st_size,'expected_paths':{p:p in names for p in required},'missing_expected_paths':missing,'native_and_rootfs_entries':[p for p in names if p.startswith('lib/') or 'alpine' in p or 'proot' in p],'status':'needs-review' if missing else 'expected-paths-present','device_tested':False}
 (out/'package-report.json').write_text(json.dumps(report,indent=2)+'\n')
print(json.dumps(report,indent=2))
if missing: print('::warning::Package inventory needs review; test APK is preserved, no runtime claim is made.')
aapt=Path(os.environ['ANDROID_HOME'])/'build-tools/35.0.0/aapt'
r=subprocess.run([str(aapt),'dump','badging',str(apk)],capture_output=True,text=True)
(out/'badging.txt').write_text(r.stdout+r.stderr)
print('\n'.join(r.stdout.splitlines()[:5]))
