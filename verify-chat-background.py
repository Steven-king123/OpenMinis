"""Static regression checks; NOT Android compilation or instrumented tests."""
from pathlib import Path
import xml.etree.ElementTree as ET
root = Path(__file__).parent
base = root/'src/android/app/src/main'
kt = base/'java/com/openminis/app'
checks=[]
def check(name, ok):
    checks.append((name, bool(ok)))
    print(('PASS ' if ok else 'FAIL ')+name)
chat=(kt/'ui/chat/ChatScreen.kt').read_text()
appearance=(kt/'ui/settings/AppearanceScreen.kt').read_text()
ui=(kt/'ui/chat/ChatBackground.kt').read_text()
store=(kt/'ui/chat/ChatBackgroundStore.kt').read_text()
check('Wallpaper drawn before conversation Column', 'ChatBackgroundLayer(modifier = Modifier.matchParentSize())\n        Column(' in chat)
check('Existing Scaffold surface unchanged', 'containerColor = ChatColors.background,' in chat)
check('Settings entry integrated', 'com.openminis.app.ui.chat.ChatBackgroundSettings()' in appearance)
check('Picker limited to images', 'picker.launch("image/*")' in ui)
check('Selected file copied to private app storage', 'context.filesDir, "chat-background"' in store)
check('Bounded import and downsample', all(x in store for x in ['MAX_BYTES', 'MAX_EDGE', 'inSampleSize', 'inJustDecodeBounds']))
check('Unique names prevent stale Coil cache', 'UUID.randomUUID()' in store)
check('No permission or model requests in feature', not any(x in ui+store for x in ['requestPermissions(', 'takePersistableUriPermission(', 'createEmbedding(', 'LLMMessage', 'Base64']))
check('Preference listener disposed', 'unregisterOnSharedPreferenceChangeListener(listener)' in ui)
check('No image touch interception', not any(x in ui for x in ['pointerInput(', 'clickable(']))
check('Cancellation and failed import clean up', 'CancellationException' in ui and 'if (!published) candidate?.delete()' in store and 'source.delete()' in store)
check('Serialized operations and cancellation-safe commit', 'Mutex()' in store and 'withContext(NonCancellable)' in store)
check('Theme overlay and Crop fit', 'ContentScale.Crop' in ui and 'surface.copy(alpha = dim)' in ui)
resources={}
for locale in ('values', 'values-zh'):
    path=base/'res'/locale/'strings_chat_background.xml'
    tree=ET.parse(path)
    resources[locale]={x.attrib['name'] for x in tree.getroot()}
    check(locale+' valid nonempty XML', len(resources[locale])>=10)
check('English and Chinese resource keys match', resources['values']==resources['values-zh'])
import re
refs=set(re.findall(r'R.string.(chat_background_\w+)',ui+store))
check('All feature string references defined', refs<=resources['values'])
print(f'{sum(ok for _,ok in checks)}/{len(checks)} static checks passed')
raise SystemExit(0 if all(ok for _,ok in checks) else 1)
