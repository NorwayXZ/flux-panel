#!/usr/bin/env python3
"""Exercise real HTTP/SQL lifecycle in the isolated runtime-test containers only."""
import base64
import concurrent.futures
import hashlib
import hmac
import json
import os
import subprocess
import sys
import time
import urllib.request

assert os.environ.get("FLUX_PANEL_RUNTIME_TEST") == "1", "Only run from the isolated runtime smoke test"
base = sys.argv[1]
assert base.startswith("http://127.0.0.1:"), "Only the local test container is allowed"


def sql(statement):
    return subprocess.check_output([
        "docker", "exec", "flux-panel-runtime-test-db", "mysql", "-uroot", "-ptestroot",
        "flux_test", "-Nse", statement,
    ], text=True).strip()


def encoded(value):
    return base64.urlsafe_b64encode(json.dumps(value).encode()).rstrip(b"=").decode()


message = encoded({"alg": "HS256", "typ": "JWT"}) + "." + encoded({
    "sub": "1", "role_id": 0, "user": "admin", "name": "admin", "iat": int(time.time()), "exp": int(time.time()) + 600,
})
signature = base64.urlsafe_b64encode(hmac.new(b"test-only-secret-with-sufficient-length-123456", message.encode(), hashlib.sha256).digest()).rstrip(b"=").decode()
token = message + "." + signature


def api(path, payload):
    request = urllib.request.Request(base + "/api/v1/" + path, json.dumps(payload).encode(),
                                     {"Authorization": token, "Content-Type": "application/json"})
    with urllib.request.urlopen(request, timeout=30) as response:
        return json.load(response)


sql("""
INSERT INTO dynamic_dns_provider(id,name,provider,credential_a,enabled,created_time,updated_time)
VALUES (900001,'isolated-smart-test','dnspod','invalid-test-key',1,1,1);
INSERT INTO node(id,owner_user_id,name,secret,server_ip,port_sta,port_end,created_time,status)
VALUES (900002,1,'isolated-node-2','test-node-2','203.0.113.2',10000,10010,1,0),
       (900003,1,'isolated-node-3','test-node-3','203.0.113.3',10000,10010,1,0);
INSERT INTO tunnel(id,name,in_node_id,in_ip,out_node_id,out_ip,type,protocol,flow,created_time,updated_time,status)
VALUES (900001,'isolated-tunnel-1',900001,'203.0.113.1',900001,'203.0.113.1',1,'tcp',1,1,1,1),
       (900002,'isolated-tunnel-2',900002,'203.0.113.2',900002,'203.0.113.2',1,'tcp',1,1,1,1),
       (900003,'isolated-tunnel-3',900003,'203.0.113.3',900003,'203.0.113.3',1,'tcp',1,1,1,1);
INSERT INTO forward(id,user_id,user_name,name,tunnel_id,in_port,remote_addr,protocol_mode,created_time,updated_time,status)
VALUES (900001,1,'admin','isolated-forward-1',900001,10000,'192.0.2.1:443','tcp',1,1,1),
       (900002,1,'admin','isolated-forward-2',900002,10000,'192.0.2.1:443','tcp',1,1,1),
       (900003,1,'admin','isolated-forward-3',900003,10000,'192.0.2.1:443','tcp',1,1,1);
""")


def create(index):
    config = {"name": "isolated-plan-" + str(index), "providerRefId": 900001, "zoneName": "example.com",
              "domain": "isolated-" + str(index) + ".example.com", "recordType": "A", "ttl": 60, "enabled": False,
              "routes": [{"carrier": "default", "forwardId": 900001}, {"carrier": "telecom", "forwardId": 900002}]}
    response = api("smart-entry/save", config)
    assert response["code"] == 0, response
    config["id"] = response["data"]["id"]
    assert sql(f"SELECT domain FROM smart_entry_group WHERE id={int(config['id'])}") == config["domain"]
    return config


with concurrent.futures.ThreadPoolExecutor(max_workers=4) as executor:
    configurations = list(executor.map(create, range(4)))
assert len({config["id"] for config in configurations}) == 4, "Generated IDs crossed connections"
config = configurations[0]
group_id = int(config["id"])
# Keep automated probes out of the controlled editing scenario. The test never enables real nodes or DNS credentials.
sql("UPDATE smart_entry_group SET last_checked_at=4102444800000,sync_requested=0")
sql(f"UPDATE smart_entry_route SET status='unhealthy',fail_count=4,current_forward_id=900001,current_address='203.0.113.1',"
    f"total_connections=123,reported_total_connections=77,activity_in_flow=456 WHERE group_id={group_id} AND carrier='telecom'")
route_id = sql(f"SELECT id FROM smart_entry_route WHERE group_id={group_id} AND carrier='telecom'")
config["name"] = "renamed-plan"
assert api("smart-entry/save", config)["code"] == 0
assert sql(f"SELECT CONCAT(id,':',status,':',current_forward_id,':',total_connections,':',reported_total_connections) "
           f"FROM smart_entry_route WHERE group_id={group_id} AND carrier='telecom'") == f"{route_id}:unhealthy:900001:123:77"
# Dependency guard must stop both normal and forced deletion before contacting an Agent.
for endpoint in ["forward/delete", "forward/force-delete", "node/delete", "tunnel/delete"]:
    response = api(endpoint, {"id": 900002})
    assert response["code"] != 0 and "三网优化" in response["msg"], response

config["routes"][1]["forwardId"] = 900003
assert api("smart-entry/save", config)["code"] == 0
assert sql(f"SELECT CONCAT(id,':',status,':',total_connections,':',reported_total_connections) FROM smart_entry_route "
           f"WHERE group_id={group_id} AND carrier='telecom'") == f"{route_id}:unknown:0:0"
assert sql(f"SELECT CONCAT(total_connections,':',in_flow) FROM smart_entry_activity_archive WHERE group_id={group_id}") == "123:456"
overview = api("smart-entry/overview", {})
assert overview["code"] == 0, overview
assert len(overview["data"]["groups"]) == 4
events = api("smart-entry/events", {"id": group_id})
assert any(event["eventType"] == "configuration" for event in events["data"])
for config in configurations:
    assert api("smart-entry/delete", {"id": config["id"]})["code"] == 0
deadline = time.monotonic() + 45
while time.monotonic() < deadline and sql("SELECT COUNT(*) FROM smart_entry_group") != "0":
    time.sleep(1)
assert sql("SELECT COUNT(*) FROM smart_entry_group") == "0", "Deletion worker did not finish"
assert sql("SELECT COUNT(*) FROM smart_entry_route") == "0"
assert sql("SELECT COUNT(*) FROM smart_entry_activity_archive") == "0"
print("Smart entry HTTP/SQL lifecycle integration tests passed")
