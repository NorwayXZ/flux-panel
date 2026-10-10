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
INSERT INTO smart_entry_group (id,user_id,name,provider_ref_id,provider,zone_name,domain,record_type,ttl,public_port,enabled,state,last_checked_at,created_time,updated_time)
VALUES (910001,1,'isolated-dns-policy',900001,'dnspod','example.test','runtime.example.test','A',60,10000,1,'healthy',4102444800000,1,1);
INSERT INTO smart_entry_route (group_id,carrier,forward_id,entry_node_id,entry_host,entry_address,entry_port,forward_name,node_name,status,created_time,updated_time)
VALUES (910001,'default',900001,900001,'192.0.2.1','192.0.2.1',10000,'default','default','healthy',1,1),
       (910001,'mobile',900002,900002,'192.0.2.2','192.0.2.2',10000,'mobile','mobile','healthy',1,1),
       (910001,'unicom',900003,900003,'192.0.2.3','192.0.2.3',10000,'unicom','unicom','healthy',1,1);
""")
created=api("service-publishing/connector/create",{"name":"isolated-openwrt","platform":"openwrt","connectorRole":"openwrt_dns"})
connector_id=int(created["connector"]["id"])
for endpoint in ["cross-entry-failover/probe-sources","virtual-lan/overview","system-self-check/overview"]:
    choices=api(endpoint,{})["connectors"]
    assert all(int(choice["id"])!=connector_id for choice in choices), "DNS-only Agent leaked into ordinary resource choices"
assert all(int(choice["id"])!=connector_id for choice in api("service-publishing/connector/list",{}))
secret=sql(f"SELECT secret FROM internal_connector WHERE id={connector_id}")
config={"connectorId":connector_id,"interfaceCarriers":{iface:"mobile"},"smartEntryGroupIds":[910001]}
api("openwrt-dns/configure",config)
process=None
with tempfile.TemporaryDirectory(prefix="flux-openwrt-runtime-") as directory:
    state=Path(directory)/"state.json"
    connection=Path(directory)/"config.json"
    connection.write_text(json.dumps({"addr":base,"secret":secret,"openwrtDnsStatePath":str(state)}))
    os.chmod(connection,0o600)
    try:
        process=subprocess.Popen([binary,"-agent-config",str(connection)],stdout=subprocess.DEVNULL,stderr=subprocess.DEVNULL)
        wait_until(lambda: state.exists() and sql(f"SELECT applied_revision FROM openwrt_dns_resolver WHERE connector_id={connector_id}")=="1")
        wait_until(lambda: sql(f"SELECT active_carrier FROM openwrt_dns_resolver WHERE connector_id={connector_id}")=="mobile")
        assert dns_answer()=="192.0.2.2"
        config["interfaceCarriers"]={iface:"unicom"}
        api("openwrt-dns/configure",config)
        wait_until(lambda: sql(f"SELECT applied_revision FROM openwrt_dns_resolver WHERE connector_id={connector_id}")=="2")
        wait_until(lambda: sql(f"SELECT active_carrier FROM openwrt_dns_resolver WHERE connector_id={connector_id}")=="unicom")
        assert dns_answer()=="192.0.2.3"
        process.terminate();process.wait(timeout=15)
        process=subprocess.Popen([binary,"-agent-config",str(connection)],stdout=subprocess.DEVNULL,stderr=subprocess.DEVNULL)
        wait_until(lambda: api("openwrt-dns/list",{})[0]["online"])
        wait_until(lambda: dns_answer()=="192.0.2.3")
        api("openwrt-dns/remove",{"connectorId":connector_id})
        wait_until(lambda: sql(f"SELECT applied_revision FROM openwrt_dns_resolver WHERE connector_id={connector_id}")=="3")
        assert json.loads(state.read_text())["groups"]==[]
        assert (Path(str(state)+".d")/"flux-panel-smart-entry.conf").read_text()=="\n"
    finally:
        if process and process.poll() is None:
            process.terminate();process.wait(timeout=15)
wait_until(lambda: not api("openwrt-dns/list",{})[0]["online"])
api("service-publishing/connector/delete",{"id":connector_id})
assert api("openwrt-dns/list",{})==[]
sql("DELETE FROM smart_entry_route WHERE group_id=910001; DELETE FROM smart_entry_group WHERE id=910001;")
print("OpenWrt DNS encrypted policy, carrier selection, reconnect and cleanup integration tests passed")
