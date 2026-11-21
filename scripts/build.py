#!/usr/bin/env python3
"""Build a reproducible plugin using a local IDEA SDK, without dependency downloads."""
import argparse
import hashlib
import io
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import sys
import time
import xml.etree.ElementTree as ET
import zipfile

ROOT = Path(__file__).resolve().parents[1]


def properties():
    values = {}
    for line in (ROOT / 'gradle.properties').read_text(encoding='utf-8').splitlines():
        if line.strip() and not line.lstrip().startswith('#'):
            key, value = line.split('=', 1)
            values[key.strip()] = value.strip()
    if not re.fullmatch(r'\d+\.\d+\.\d+(?:-[0-9A-Za-z.-]+)?', values['pluginVersion']):
        raise ValueError('pluginVersion must be a semantic version')
    return values


def archive_bytes(entries, timestamp):
    buffer = io.BytesIO()
    with zipfile.ZipFile(buffer, 'w', compression=zipfile.ZIP_DEFLATED, compresslevel=9) as archive:
        for name, content in sorted(entries.items()):
            info = zipfile.ZipInfo(name, timestamp)
            info.create_system = 3
            info.external_attr = 0o100644 << 16
            info.compress_type = zipfile.ZIP_DEFLATED
            archive.writestr(info, content, compresslevel=9)
    return buffer.getvalue()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--idea-home', default=os.environ.get('IDEA_HOME', '/Applications/IntelliJ IDEA.app/Contents'))
    parser.add_argument('--java-home', default=os.environ.get('JAVA_HOME'))
    parser.add_argument('--test', action='store_true', help='run standalone HTTP tests against the packaged JAR')
    args = parser.parse_args()
    try:
        config = properties()
        sdk = Path(args.idea_home).expanduser().resolve()
        if sdk.suffix == '.app':
            sdk = sdk / 'Contents'
        product_file = next((p for p in (sdk / 'product-info.json', sdk / 'Resources/product-info.json') if p.is_file()), None)
        if product_file is None:
            parser.error('--idea-home must contain product-info.json (an unpacked IntelliJ IDEA SDK)')
        product = json.loads(product_file.read_text(encoding='utf-8'))
        if product.get('productCode') not in ('IU', 'IC', 'II'):
            parser.error('--idea-home must point to IntelliJ IDEA with Java support')
        branch = str(product.get('buildNumber', '')).split('.')[0]
        if branch != config['sinceBuild'].split('.')[0] or config['untilBuild'] != branch + '.*':
            parser.error('IDEA SDK build must match the supported branch in gradle.properties')
        if tuple(map(int, product['buildNumber'].split('.'))) < tuple(map(int, config['sinceBuild'].split('.'))):
            parser.error('IDEA SDK is older than the minimum supported stable build')
        if args.java_home:
            java = Path(args.java_home).expanduser().resolve()
        else:
            java = next((p for p in (sdk / 'jbr/Contents/Home', sdk / 'jbr') if (p / 'bin').is_dir()), sdk / 'jbr')
        suffix = '.exe' if os.name == 'nt' else ''
        javac, runtime = java / ('bin/javac' + suffix), java / ('bin/java' + suffix)
        if not javac.is_file() or not runtime.is_file():
            parser.error('--java-home must point to a complete JDK; an explicit invalid path is not replaced')
        compiler_version = subprocess.run([str(javac), '-version'], check=True, capture_output=True, text=True)
        compiler_text = (compiler_version.stdout + compiler_version.stderr).strip()
        match = re.search(r'javac (\d+)', compiler_text)
        release = int(config['javaVersion'])
        if match is None or int(match[1]) < release:
            parser.error('JDK %s+ is required; found %s' % (release, compiler_text))
        jars = sorted((sdk / 'lib').glob('*.jar')) + sorted((sdk / 'plugins/java/lib').rglob('*.jar'))
        if not jars or not (sdk / 'plugins/java/lib').is_dir():
            parser.error('IDEA SDK must include platform libraries and the bundled Java plugin')
        epoch = int(os.environ.get('SOURCE_DATE_EPOCH', '315532800'))
        if not 315532800 <= epoch <= 4354819198:
            parser.error('SOURCE_DATE_EPOCH must be within ZIP timestamp range (1980–2107)')
        timestamp = time.gmtime(epoch)[:6]
        descriptor_file = ROOT / 'src/main/resources/META-INF/plugin.xml'
        descriptor = ET.fromstring(descriptor_file.read_bytes())
        if descriptor.find('version') is not None:
            parser.error('Keep pluginVersion only in gradle.properties; source plugin.xml must omit <version>')
        idea_version = descriptor.find('idea-version')
        if idea_version is None or idea_version.attrib != {'since-build': config['sinceBuild'], 'until-build': config['untilBuild']}:
            parser.error('plugin.xml compatibility must match gradle.properties')
        version_element = ET.Element('version')
        version_element.text = config['pluginVersion']
        descriptor.insert(2, version_element)
    except (OSError, ValueError, KeyError, ET.ParseError) as error:
        parser.error(str(error))

    cp = os.pathsep.join(map(str, jars))
    # Argument files avoid Windows command-line length limits from the SDK classpath.
    build = ROOT / 'build'
    offline = build / 'offline'
    classes = offline / 'classes'
    if classes.exists():
        shutil.rmtree(classes)
    classes.mkdir(parents=True)

    def compile_sources(sources, destination, classpath, name, sourcepath=None):
        arguments = ['--release', str(release), '-encoding', 'UTF-8', '-classpath', classpath, '-d', str(destination)]
        if sourcepath is not None:
            arguments += ['-sourcepath', str(sourcepath)]
        arguments += [str(p) for p in sources]
        argument_file = build / name
        argument_file.write_text('\n'.join('"' + a.replace('\\', '\\\\').replace('"', '\\"') + '"' for a in arguments), encoding='utf-8')
        subprocess.run([str(javac), '@' + str(argument_file)], check=True)

    compile_sources(sorted((ROOT / 'src/main/java').rglob('*.java')), classes, cp, 'compile.args')
    entries = {}
    for folder in (classes, ROOT / 'src/main/resources'):
        for path in sorted(folder.rglob('*')):
            if path.is_file():
                entries[path.relative_to(folder).as_posix()] = path.read_bytes()
    entries['META-INF/plugin.xml'] = ET.tostring(descriptor, encoding='utf-8', xml_declaration=True)
    legal_files = ('LICENSE', 'NOTICE', 'THIRD_PARTY_NOTICES.md')
    for name in legal_files:
        entries['META-INF/' + name] = (ROOT / name).read_bytes()
    jar = offline / 'diagram-source-navigator.jar'
    jar.write_bytes(archive_bytes(entries, timestamp))

    if args.test:
        tests = offline / 'test-classes'
        if tests.exists():
            shutil.rmtree(tests)
        tests.mkdir()
        test_cp = str(jar) + os.pathsep + cp
        test_source = ROOT / 'src/test/java'
        compile_sources([test_source / 'dev/learning/navigator/NavigationServerTest.java'], tests, test_cp, 'test-compile.args', test_source)
        runtime_cp = str(tests) + os.pathsep + test_cp
        launch_args = ['-ea', '-classpath', runtime_cp, 'dev.learning.navigator.NavigationServerTest']
        launch_file = build / 'test-run.args'
        launch_file.write_text('\n'.join('"' + a.replace('\\', '\\\\').replace('"', '\\"') + '"' for a in launch_args), encoding='utf-8')
        subprocess.run([str(runtime), '@' + str(launch_file)], check=True)

    distribution = build / 'distributions' / ('diagram-source-navigator-%s.zip' % config['pluginVersion'])
    distribution.parent.mkdir(exist_ok=True)
    files = {'diagram-source-navigator/lib/' + jar.name: jar.read_bytes()}
    files.update({'diagram-source-navigator/' + name: (ROOT / name).read_bytes() for name in legal_files})
    distribution.write_bytes(archive_bytes(files, timestamp))
    digest = hashlib.sha256(distribution.read_bytes()).hexdigest()
    distribution.with_suffix('.zip.sha256').write_text(digest + '  ' + distribution.name + '\n', encoding='utf-8')
    (build / 'build-info.json').write_text(json.dumps({
        'pluginVersion': config['pluginVersion'], 'ideaVersion': product['version'],
        'ideaBuild': product['buildNumber'], 'compiler': compiler_text,
        'javaRelease': release, 'httpTestsRun': args.test, 'sha256': digest,
    }, indent=2) + '\n', encoding='utf-8')
    print(distribution)
    print('SHA-256: ' + digest)


if __name__ == '__main__':
    try:
        main()
    except subprocess.CalledProcessError as error:
        sys.exit('Build/test command failed (exit %s); see diagnostics above.' % error.returncode)
    except OSError as error:
        sys.exit('Build failed: %s' % error)
