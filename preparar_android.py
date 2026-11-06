import concurrent.futures, pathlib, urllib.request, zipfile, xml.etree.ElementTree as ET
root=pathlib.Path(__file__).resolve().parent/'.tuner-tools'
root.mkdir(exist_ok=True)
if (root/'jdk-17.0.17+10'/'bin'/'javac.exe').exists() and (root/'android-sdk'/'cmdline-tools'/'19.0'/'bin'/'sdkmanager.bat').exists():
    print('Herramientas locales listas',flush=True)
    raise SystemExit(0)
def download_extract(url,name,destination):
    archive=root/name
    if not archive.exists():
        print('Downloading '+name,flush=True)
        urllib.request.urlretrieve(url,archive)
    with zipfile.ZipFile(archive) as z: z.extractall(destination)
    print(name+' ready',flush=True)
xml=ET.fromstring(urllib.request.urlopen('https://dl.google.com/android/repository/repository2-1.xml',timeout=60).read())
cmd_url=None
for package in xml:
    if package.attrib.get('path')=='cmdline-tools;19.0':
        for archive in package.iter():
            if archive.tag.split('}')[-1]=='archive':
                values={e.tag.split('}')[-1]:e.text for e in archive.iter()}
                if values.get('host-os')=='windows': cmd_url='https://dl.google.com/android/repository/'+values['url']; break
if not cmd_url: raise RuntimeError('Official Windows SDK command-line tools not found')
jobs=[]
if not (root/'jdk-17.0.17+10'/'bin'/'javac.exe').exists():
    jobs.append(('https://github.com/adoptium/temurin17-binaries/releases/download/jdk-17.0.17%2B10/OpenJDK17U-jdk_x64_windows_hotspot_17.0.17_10.zip','jdk.zip',root))
if not (root/'android-sdk'/'cmdline-tools'/'19.0').exists():
    jobs.append((cmd_url,'sdk-tools-19.zip',root/'sdk-unpack-19'))
with concurrent.futures.ThreadPoolExecutor(max_workers=2) as pool:
    list(pool.map(lambda args:download_extract(*args),jobs))
sdk=root/'android-sdk'
latest=sdk/'cmdline-tools'/'19.0'
if not latest.exists():
    latest.parent.mkdir(parents=True,exist_ok=True)
    (root/'sdk-unpack-19'/'cmdline-tools').rename(latest)
print('Build tools downloaded',flush=True)
