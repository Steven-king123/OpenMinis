"""One-time workspace-only overrides for the unified Simone development build.
No CI dispatch, repository secrets, or automatic key generation.
Missing explicit signing configuration produces an unsigned APK for verification.
"""
from pathlib import Path
root=Path(__file__).resolve().parents[1]
p=root/'src/android/app/build.gradle.kts'
s=p.read_text()
assert 'applicationIdSuffix = ".memorytest"' not in s, 'Already configured; use a clean source snapshot'
old='''        getByName("debug") {
            buildConfigField("boolean", "DEV_TOOLS", "true")
        }'''
new='''        getByName("debug") {
            applicationIdSuffix = ".memorytest"
            versionNameSuffix = "-dev"
            // Never generate a random server debug key for an allegedly compatible upgrade.
            signingConfig = null
            buildConfigField("boolean", "DEV_TOOLS", "false")
        }'''
assert s.count(old)==1
s=s.replace(old,new).replace('versionCode = 37','versionCode = 40')
assert 'versionCode = 40' in s
s=s.replace('    compileSdk = 36','    compileSdk = 36\n    ndkVersion = "28.0.13004108"')
for key in ('SELF_UPDATE','AUTO_UPDATE_CHECK','IN_APP_INSTALL'):
 s=s.replace(f'buildConfigField("boolean", "{key}", "true")',f'buildConfigField("boolean", "{key}", "false")')
p.write_text(s)
p=root/'src/android/app/src/main/res/values/strings.xml'
s=p.read_text();old='<string name="app_name">Minis</string>';assert s.count(old)==1
p.write_text(s.replace(old,'<string name="app_name">Minis Dev</string>'))
print('Server build: com.openminis.app.memorytest / Minis Dev / versionCode 40 / unsigned pending signature verification')
