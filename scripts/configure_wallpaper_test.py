"""CI-only overrides. Never changes production applicationId or release signing."""
from pathlib import Path
root = Path(__file__).resolve().parents[1]
p = root/'src/android/app/build.gradle.kts'
s=p.read_text()
old='''        getByName("debug") {
            buildConfigField("boolean", "DEV_TOOLS", "true")
        }'''
new='''        getByName("debug") {
            applicationIdSuffix = ".wallpapertest"
            versionNameSuffix = "-wallpaper-test"
            buildConfigField("boolean", "DEV_TOOLS", "false")
        }'''
assert s.count(old)==1, 'Debug build block changed; inspect before patching'
s=s.replace(old,new)
for key in ('SELF_UPDATE','AUTO_UPDATE_CHECK','IN_APP_INSTALL'):
    s=s.replace(f'buildConfigField("boolean", "{key}", "true")',f'buildConfigField("boolean", "{key}", "false")')
p.write_text(s)
strings=root/'src/android/app/src/main/res/values/strings.xml'
s=strings.read_text()
old='<string name="app_name">Minis</string>'
assert s.count(old)==1, 'App label changed; inspect before patching'
strings.write_text(s.replace(old,'<string name="app_name">Minis Background Test</string>'))
print('CI test package: com.openminis.app.wallpapertest; updates and debug server disabled')
