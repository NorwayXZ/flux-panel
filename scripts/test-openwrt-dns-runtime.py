#!/usr/bin/env python3
"""Exercise the DNS-only executable with the isolated backend and database."""
import base64
import hashlib
import hmac
import json
import os
import socket
import struct
import subprocess
import sys
import tempfile
import time
import urllib.request
from pathlib import Path

assert os.environ.get("FLUX_PANEL_RUNTIME_TEST") == "1"
base, binary = sys.argv[1:3]
assert base.startswith("http://127.0.0.1:") and os.path.isfile(binary)

def encoded(value):
    return base64.urlsafe_b64encode(json.dumps(value).encode()).rstrip(b"=").decode()

message = encoded({"alg":"HS256","typ":"JWT"}) + "." + encoded({"sub":"1","role_id":0,"user":"admin","name":"admin","exp":int(time.time())+900})
signature = base64.urlsafe_b64encode(hmac.new(b"test-only-secret-with-sufficient-length-123456",message.encode(),hashlib.sha256).digest()).rstrip(b"=").decode()
token = message + "." + signature

def api(path,payload):
    request=urllib.request.Request(base+"/api/v1/"+path,json.dumps(payload).encode(),{"Authorization":token,"Content-Type":"application/json"})
    with urllib.request.urlopen(request,timeout=20) as response:
        result=json.load(response)
    assert result["code"]==0, result.get("msg")
    return result["data"]

def sql(statement):
    return subprocess.check_output(["docker","exec","flux-panel-runtime-test-db","mysql","-uroot","-ptestroot","flux_test","-Nse",statement],text=True,stderr=subprocess.DEVNULL).strip()

def wait_until(predicate):
    deadline=time.monotonic()+60
    while time.monotonic()<deadline:
        try:
            if predicate(): return
        except (OSError, AssertionError):
            pass
        time.sleep(.5)
    raise AssertionError("OpenWrt DNS runtime condition did not converge")

def dns_answer():
    name="runtime.example.test"
    encoded_name=b"".join(bytes([len(part)])+part.encode() for part in name.split("."))+b"\0"
    request=struct.pack("!HHHHHH",12345,0x0100,1,0,0,0)+encoded_name+struct.pack("!HH",1,1)
    with socket.socket(socket.AF_INET,socket.SOCK_DGRAM) as sock:
        sock.settimeout(2);sock.sendto(request,("127.0.0.1",5354));reply,_=sock.recvfrom(4096)
    identifier,flags,questions,answers,_,_=struct.unpack("!HHHHHH",reply[:12])
    assert identifier==12345 and flags&15==0 and answers==1
    return socket.inet_ntoa(reply[-4:])

route=subprocess.check_output(["ip","-4","route","get","1.1.1.1"],text=True).split()
iface=route[route.index("dev")+1]
sql("""
UPDATE node SET server_ip='192.0.2.1' WHERE id=900001;
UPDATE node SET server_ip='192.0.2.2' WHERE id=900002;
UPDATE node SET server_ip='192.0.2.3' WHERE id=900003;
""")
created=api("service-publishing/connector/create",{"name":"isolated-openwrt","platform":"openwrt","connectorRole":"openwrt_dns"})
connector_id=int(created["connector"]["id"])
strategy={"name":"Cloudflare-compatible-local-policy","dnsMode":"local","domain":"runtime.example.test",
          "recordType":"A","dnsAgentIds":[connector_id],"routes":[
              {"carrier":"default","forwardId":900001}, {"carrier":"mobile","forwardId":900002},
              {"carrier":"unicom","forwardId":900003}]}
group_id=int(api("smart-entry/save",strategy)["id"])
strategy["id"]=group_id
assert sql(f"SELECT CONCAT(dns_mode,':',provider_ref_id,':',ttl) FROM smart_entry_group WHERE id={group_id}")=="local:0:5"
assert api("openwrt-dns/list",{})[0]["smartEntryGroupIds"]==[group_id]
assert api("openwrt-dns/list",{})[0]["interfaceCarriers"]=={}
sql(f"UPDATE smart_entry_group SET state='healthy',last_checked_at=4102444800000,sync_requested=0 WHERE id={group_id}; "
    f"UPDATE smart_entry_route SET status='healthy' WHERE group_id={group_id}")
for endpoint in ["cross-entry-failover/probe-sources","virtual-lan/overview","system-self-check/overview"]:
    choices=api(endpoint,{})["connectors"]
    assert all(int(choice["id"])!=connector_id for choice in choices), "DNS-only Agent leaked into ordinary resource choices"
assert all(int(choice["id"])!=connector_id for choice in api("service-publishing/connector/list",{}))
secret=sql(f"SELECT secret FROM internal_connector WHERE id={connector_id}")
config={"connectorId":connector_id,"interfaceCarriers":{iface:"mobile"},"smartEntryGroupIds":[group_id]}
api("openwrt-dns/configure",config)
process=None
with tempfile.TemporaryDirectory(prefix="flux-openwrt-runtime-") as directory:
    state=Path(directory)/"state.json"
    connection=Path(directory)/"config.json"
    connection.write_text(json.dumps({"addr":base,"secret":secret,"openwrtDnsStatePath":str(state)}))
    os.chmod(connection,0o600)
    try:
        process=subprocess.Popen([binary,"-agent-config",str(connection)],stdout=subprocess.DEVNULL,stderr=subprocess.DEVNULL)
        revision=sql(f"SELECT policy_revision FROM openwrt_dns_resolver WHERE connector_id={connector_id}")
        wait_until(lambda: state.exists() and int(sql(f"SELECT applied_revision FROM openwrt_dns_resolver WHERE connector_id={connector_id}"))>=int(revision))
        wait_until(lambda: sql(f"SELECT active_carrier FROM openwrt_dns_resolver WHERE connector_id={connector_id}")=="mobile")
        assert dns_answer()=="192.0.2.2"
        config["interfaceCarriers"]={iface:"unicom"}
        api("openwrt-dns/configure",config)
        revision=sql(f"SELECT policy_revision FROM openwrt_dns_resolver WHERE connector_id={connector_id}")
        wait_until(lambda: int(sql(f"SELECT applied_revision FROM openwrt_dns_resolver WHERE connector_id={connector_id}"))>=int(revision))
        wait_until(lambda: sql(f"SELECT active_carrier FROM openwrt_dns_resolver WHERE connector_id={connector_id}")=="unicom")
        assert dns_answer()=="192.0.2.3"
        process.terminate();process.wait(timeout=15)
        process=subprocess.Popen([binary,"-agent-config",str(connection)],stdout=subprocess.DEVNULL,stderr=subprocess.DEVNULL)
        wait_until(lambda: api("openwrt-dns/list",{})[0]["online"])
        wait_until(lambda: dns_answer()=="192.0.2.3")
        # A strategy edit automatically switches the existing router to automatic detection.
        api("smart-entry/save",strategy)
        sql(f"UPDATE smart_entry_group SET last_checked_at=4102444800000,sync_requested=0 WHERE id={group_id}")
        assert api("openwrt-dns/list",{})[0]["interfaceCarriers"]=={}
        revision=sql(f"SELECT policy_revision FROM openwrt_dns_resolver WHERE connector_id={connector_id}")
        wait_until(lambda: int(sql(f"SELECT applied_revision FROM openwrt_dns_resolver WHERE connector_id={connector_id}"))>=int(revision))
        assert json.loads(state.read_text())["interfaceCarriers"]=={}
        api("smart-entry/delete",{"id":group_id})
        revision=sql(f"SELECT policy_revision FROM openwrt_dns_resolver WHERE connector_id={connector_id}")
        wait_until(lambda: int(sql(f"SELECT applied_revision FROM openwrt_dns_resolver WHERE connector_id={connector_id}"))>=int(revision))
        assert json.loads(state.read_text())["groups"]==[]
        assert (Path(str(state)+".d")/"flux-panel-smart-entry.conf").read_text()=="\n"
    finally:
        if process and process.poll() is None:
            process.terminate();process.wait(timeout=15)
wait_until(lambda: not api("openwrt-dns/list",{})[0]["online"])
api("service-publishing/connector/delete",{"id":connector_id})
assert api("openwrt-dns/list",{})==[]
wait_until(lambda: sql(f"SELECT COUNT(*) FROM smart_entry_group WHERE id={group_id}")=="0")
assert sql(f"SELECT COUNT(*) FROM smart_entry_route WHERE group_id={group_id}")=="0"
print("OpenWrt DNS local strategy creation, automatic binding, encrypted policy, carrier selection, reconnect and cleanup integration tests passed")
