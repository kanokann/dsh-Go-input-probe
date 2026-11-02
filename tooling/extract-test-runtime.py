#!/usr/bin/env python3
"""Verify official v1.0.9 APK and its runtime manifest; copy runtime assets only."""
import hashlib
import json
from pathlib import Path, PurePosixPath
import sys
import zipfile

APK_NAME = 'dsh-go-1.0.9-arm64.apk'
APK_SHA256 = '6b8543694b46a44f7c25010c8ef2afbccc1636955b89bbc8d7b6fee995c9d5a1'
JNI = {'libproot.so', 'libproot-loader.so', 'libtalloc.so', 'libandroid-shmem.so'}


def sha256(path):
    with path.open('rb') as f:
        return hashlib.file_digest(f, 'sha256').hexdigest()


def extract(apk, sums, out):
    entries = [line.split() for line in sums.read_text().splitlines() if line.strip()]
    assert any(len(e) == 2 and e[0] == APK_SHA256 and e[1].lstrip('*') == APK_NAME for e in entries), 'Official checksum differs from pinned v1.0.9'
    assert sha256(apk) == APK_SHA256, 'APK SHA256 mismatch'
    # The whole APK hash authenticates manifest and licenses as well as runtime payloads.
    with zipfile.ZipFile(apk) as z:
        assert len(z.namelist()) == len(set(z.namelist())), 'Duplicate ZIP names'
        m = json.loads(z.read('assets/runtime/manifest.json'))
        assert m['schema'] == 1 and m['abi'] == 'arm64-v8a' and m['runtimeVersion']
        assert PurePosixPath(m['rootfs']).name == m['rootfs'], 'Unexpected rootfs path'
        assert set(m['jniLibs']) == JNI, 'Unexpected runtime libraries'
        payloads = {'assets/runtime/' + m['rootfs']: (m['rootfsSha256'], m['rootfsBytes'])}
        payloads.update({'lib/arm64-v8a/' + name: (v['sha256'], v['bytes']) for name, v in m['jniLibs'].items()})
        for name, (digest, size) in payloads.items():
            assert z.getinfo(name).file_size == size, f'{name}: size mismatch'
            with z.open(name) as f:
                assert hashlib.file_digest(f, 'sha256').hexdigest() == digest, f'{name}: hash mismatch'
        # Never extract classes.dex, AndroidManifest.xml, Flutter app snapshots or signatures.
        for name in z.namelist():
            if name.endswith('/'):
                continue
            if name.startswith('assets/runtime/'):
                relative = PurePosixPath(name)
            elif name in payloads and name.startswith('lib/'):
                relative = PurePosixPath('jniLibs', 'arm64-v8a', PurePosixPath(name).name)
            else:
                continue
            assert '..' not in relative.parts and not relative.is_absolute()
            dest = out.joinpath(*relative.parts)
            dest.parent.mkdir(parents=True, exist_ok=True)
            with z.open(name) as src, dest.open('wb') as dst:
                import shutil
                shutil.copyfileobj(src, dst)
    print('Verified official APK and all runtime payloads:', m['runtimeVersion'])


if __name__ == '__main__':
    extract(*(Path(p) for p in sys.argv[1:4]))
