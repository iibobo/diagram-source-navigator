#!/usr/bin/env python3
"""Verify core Java/PSI behavior with external network connections denied on macOS."""
import argparse
import os
from pathlib import Path
import subprocess
import sys

ROOT = Path(__file__).resolve().parents[1]
PROFILE = '''(version 1)
(allow default)
(deny network*)
(allow network-bind network-inbound)
(allow network-outbound (remote ip "localhost:*") (regex #"^/"))
'''
PROBE = '''import errno, socket
listener = socket.socket()
listener.bind(('127.0.0.1', 0))
listener.listen()
with socket.create_connection(listener.getsockname(), timeout=2):
    print('PASS loopback connection')
for address in ('1.1.1.1', '8.8.8.8'):
    with socket.socket() as client:
        client.settimeout(2)
        try:
            client.connect((address, 443))
        except OSError as error:
            assert error.errno == errno.EPERM, (address, error)
        else:
            raise AssertionError('External connection allowed: ' + address)
print('PASS external IP connections denied by the operating system')
'''


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    local_idea = '/Applications/IntelliJ IDEA.app/Contents'
    parser.add_argument('--idea-home', default=os.environ.get('IDEA_HOME') or (local_idea if Path(local_idea).is_dir() else None))
    args = parser.parse_args()
    if sys.platform != 'darwin':
        parser.error('This verification requires macOS sandbox-exec')
    output = ROOT / 'build/reports/offline'
    output.mkdir(parents=True, exist_ok=True)
    profile = output / 'loopback-only.sb'
    profile.write_text(PROFILE, encoding='utf-8')
    command = ['/usr/bin/sandbox-exec', '-f', str(profile)]
    environment = dict(os.environ)
    if args.idea_home:
        environment.setdefault('JAVA_HOME', str(Path(args.idea_home) / 'jbr/Contents/Home'))
    # Java's IPv4-mapped IPv6 sockets do not match the sandbox localhost filter.
    environment['JAVA_TOOL_OPTIONS'] = (environment.get('JAVA_TOOL_OPTIONS', '') + ' -Djava.net.preferIPv4Stack=true').strip()
    steps = [
        ('network-policy.log', command + [sys.executable, '-c', PROBE]),
        ('gradle.log', command + [str(ROOT / 'gradlew'), '--offline', 'test', 'httpTest',
                                '--rerun-tasks', '--no-daemon', '--console=plain'] +
         (['-PideaHome=' + args.idea_home] if args.idea_home else [])),
    ]
    for name, arguments in steps:
        with (output / name).open('w', encoding='utf-8') as log:
            result = subprocess.run(arguments, cwd=ROOT, env=environment, stdout=log, stderr=subprocess.STDOUT)
        print(('PASS ' if result.returncode == 0 else 'FAIL ') + str(output / name), flush=True)
        if result.returncode:
            return result.returncode
    return 0


if __name__ == '__main__':
    raise SystemExit(main())
