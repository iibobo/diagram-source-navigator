#!/usr/bin/env python3
"""Validate plugin ZIP contents before signing or uploading; does not extract files."""
import argparse
import hashlib
import io
from pathlib import Path
import sys
import xml.etree.ElementTree as ET
import zipfile

from build import properties


def check_distribution(path):
    config = properties()
    with zipfile.ZipFile(path) as distribution:
        names = distribution.namelist()
        if any(name.startswith(('/', '\\')) or '\\' in name or '..' in name.split('/') for name in names):
            raise ValueError('unsafe ZIP entry path')
        jars = [name for name in names if '/lib/' in name and name.endswith('.jar')]
        if len(jars) != 1:
            raise ValueError('expected exactly one plugin JAR and no bundled platform libraries')
        with zipfile.ZipFile(io.BytesIO(distribution.read(jars[0]))) as plugin:
            descriptor = ET.fromstring(plugin.read('META-INF/plugin.xml'))
            if descriptor.findtext('version') != config['pluginVersion']:
                raise ValueError('packaged plugin version differs from gradle.properties')
            if descriptor.findtext('id') != 'dev.learning.diagram-source-navigator':
                raise ValueError('plugin ID changed unexpectedly')
            idea = descriptor.find('idea-version')
            if idea is None or idea.attrib != {'since-build': config['sinceBuild'], 'until-build': config['untilBuild']}:
                raise ValueError('packaged compatibility differs from gradle.properties')
            vendor = descriptor.find('vendor')
            if vendor is None or vendor.text != 'iibobo' or vendor.get('email') != 'iibobo@zohomail.cn':
                raise ValueError('packaged vendor metadata is incomplete')
            icon = ET.fromstring(plugin.read('META-INF/pluginIcon.svg'))
            if icon.get('width') != '40' or icon.get('height') != '40':
                raise ValueError('plugin icon must be 40 by 40')
            if not plugin.read('navigator-bridge.js'):
                raise ValueError('missing browser bridge')
            for name in ('LICENSE', 'NOTICE', 'THIRD_PARTY_NOTICES.md'):
                if plugin.read('META-INF/' + name) != (Path(__file__).resolve().parents[1] / name).read_bytes():
                    raise ValueError('missing or stale ' + name)
            bytecode = plugin.read('dev/learning/navigator/NavigatorService.class')
            if bytecode[:4] != b'\xca\xfe\xba\xbe' or int.from_bytes(bytecode[6:8], 'big') != int(config['javaVersion']) + 44:
                raise ValueError('unexpected Java bytecode level')
            if any(name.startswith(('com/intellij/', 'com/google/gson/')) for name in plugin.namelist()):
                raise ValueError('platform and Gson classes must not be bundled')
            if any(name.endswith('Test.class') for name in plugin.namelist()):
                raise ValueError('test classes must not be packaged')
    checksum = path.with_suffix('.zip.sha256')
    if not checksum.is_file():
        raise ValueError('missing SHA-256 sidecar')
    fields = checksum.read_text(encoding='utf-8').split()
    if len(fields) != 2 or fields[0] != hashlib.sha256(path.read_bytes()).hexdigest() or fields[1] != path.name:
        raise ValueError('SHA-256 sidecar does not match archive')
    print('PASS distribution metadata, resources, license, bytecode, and checksum: ' + path.name)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('archives', nargs='+', type=Path)
    for path in parser.parse_args().archives:
        check_distribution(path)


if __name__ == '__main__':
    try:
        main()
    except (OSError, ValueError, KeyError, ET.ParseError, zipfile.BadZipFile) as error:
        sys.exit('Distribution validation failed: ' + str(error))
