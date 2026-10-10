#!/usr/bin/env python3
"""Run the real installer against an isolated iStoreOS-style filesystem and CLI."""
import hashlib
import json
import os
from pathlib import Path
import re
import subprocess
import sys
import tempfile

project = Path(__file__).resolve().parents[1]
version = (project / "AGENT_VERSION").read_text().strip()
source = (project / "install-openwrt-dns-agent.sh").read_text()

for custom in (False, True):
    with tempfile.TemporaryDirectory(prefix="flux-openwrt-install-test-") as temporary:
        root = Path(temporary)
        commands = root / "bin"
        commands.mkdir()
        for directory in ("etc/init.d", "var/etc", "tmp", "proc"):
            (root / directory).mkdir(parents=True, exist_ok=True)
        (root / "etc/openwrt_release").write_text("DISTRIB_ARCH='x86_64'\n")
        (root / "etc/rc.common").write_text("#!/bin/sh\nexit 0\n")
        confdir = root / ("etc/custom-dns.d" if custom else "tmp/dnsmasq.cfg01411c.d")
        confdir.mkdir()
        unrelated = confdir / "other-plugin.conf"
        unrelated.write_text("server=192.0.2.53\n")
        (root / "var/etc/dnsmasq.conf.cfg01411c").write_text("port=53\nconf-dir=" + str(confdir) + "\n")
        state_file = root / "uci.json"
        state_file.write_text(json.dumps({"confdir": str(confdir) if custom else None}))
        agent = ("#!/bin/sh\nprintf '%s\\n' '" + version + "'\n").encode()
        digest = hashlib.sha256(agent).hexdigest()
        def executable(name, body):
            file = commands / name
            file.write_text("#!" + sys.executable + "\n" + body)
            file.chmod(0o755)
        executable("id", "print('0')\n")
        executable("uname", "print('x86_64')\n")
        executable("sleep", "pass\n")
        executable("ip", "pass\n")
        executable("sha256sum", "import hashlib,sys\np=sys.argv[1]\nprint(hashlib.sha256(open(p,'rb').read()).hexdigest(),p)\n")
        executable("curl", "import sys\nfrom pathlib import Path\na=sys.argv[1:]\noutput=Path(a[a.index('-o')+1])\noutput.write_bytes(" + repr(agent) + " if not any('SHA256SUMS' in v for v in a) else " + repr((digest + "  flux-openwrt-dns-amd64\n").encode()) + ")\n")
        executable("uci", r"""import json,sys
from pathlib import Path
store=Path(%s)
data=json.loads(store.read_text())
args=sys.argv[1:]
if 'show' in args:
    # Anonymous display syntax must never be used as the generated instance ID.
    print('dhcp.cfgDisabled=dnsmasq\ndhcp.cfg01411c=dnsmasq' if '-X' in args else 'dhcp.@dnsmasq[0]=dnsmasq')
elif 'get' in args:
    key=args[-1]
    if key.endswith('cfgDisabled.disabled'): print('1')
    elif key.endswith('.disabled'): print('0')
    elif key.endswith('.port'): print('53')
    elif key.endswith('.confdir'):
        if data['confdir'] is None: sys.exit(1)
        print(data['confdir'])
elif 'set' in args:
    key,value=args[-1].split('=',1)
    assert key=='dhcp.cfg01411c.confdir',key
    data['confdir']=value
elif 'delete' in args:
    assert args[-1]=='dhcp.cfg01411c.confdir',args[-1]
    data['confdir']=None
store.write_text(json.dumps(data))
""" % repr(str(state_file)))
        dnsmasq = root / "etc/init.d/dnsmasq"
        dnsmasq.write_text("#!/bin/sh\nexit 0\n")
        dnsmasq.chmod(0o755)
        # Rewrite only system paths once; no command can touch the host's /etc or /proc.
        rewritten = re.sub(r"/var/etc/|/etc/|/tmp/|/proc/", lambda match: str(root) + match.group(0), source)
        script = root / "installer.sh"
        script.write_text(rewritten)
        environment = {**os.environ, "PATH": str(commands) + os.pathsep + os.environ["PATH"]}
        result = subprocess.run(["sh", str(script), "install", "http://panel.example.test", "test-router-secret"], env=environment, text=True, capture_output=True)
        assert result.returncode == 0, result.stderr
        managed = root / "etc/flux-panel-dns"
        assert (managed / "dnsmasq-confdir").read_text().strip() == str(confdir)
        assert unrelated.read_text() == "server=192.0.2.53\n"
        assert (managed / "owned-confdir-setting").exists() is (not custom)
        result = subprocess.run(["sh", str(script), "uninstall"], env=environment, text=True, capture_output=True)
        assert result.returncode == 0, result.stderr
        assert unrelated.read_text() == "server=192.0.2.53\n"
        assert json.loads(state_file.read_text())["confdir"] == (str(confdir) if custom else None)
        assert not (managed / "config.json").exists()
print("OpenWrt installer stable anonymous IDs, effective conf-dir, preservation and uninstall tests passed")
