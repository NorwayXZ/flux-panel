#!/usr/bin/env bash
# Run against two independently deployed shared consumers. Requires curl/python3.
# Usage: bash live-check.sh http://127.0.0.1:4450 http://other-host:4450 US
# Use SSH port forwarding for remote APIs; never expose Tequilapi publicly.
set -euo pipefail
API_A=${1:?first locally forwarded API}
API_B=${2:?second locally forwarded API}
COUNTRY=${3:-US}
for api in "$API_A" "$API_B"; do
  case "$api" in http://127.0.0.1:*|http://localhost:*) ;; *) echo 'Forward the API to localhost first' >&2; exit 1 ;; esac
done
python3 - "$API_A" "$API_B" "$COUNTRY" <<'PY'
import concurrent.futures
import json
import sys
import urllib.request

apis = sys.argv[1:3]
country = sys.argv[3].upper()

def request(api, path, method='GET', body=None):
    payload = None if body is None else json.dumps(body).encode()
    req = urllib.request.Request(api+path, data=payload, method=method, headers={'Content-Type':'application/json'})
    with urllib.request.urlopen(req, timeout=90) as resp:
        raw=resp.read()
        return json.loads(raw) if raw else None

ids=[]
for api in apis:
    ids.append(request(api, '/identities/current', 'PUT', {'passphrase':''})['id'])
if len(set(ids)) != 1:
    raise SystemExit('Consumers do not use the same identity')

def connect(args):
    api, consumer = args
    proposals=request(api, '/proposals?service_type=wireguard&ip_type=residential&location_country='+country)['proposals']
    proposals=[p for p in proposals if p.get('location',{}).get('country')==country]
    if not proposals:
        raise RuntimeError('No residential provider in '+country)
    request(api, '/connection', 'PUT', {'consumer_id':consumer,'provider_id':proposals[0]['provider_id'],'service_type':'wireguard','connect_options':{'proxy_port':18990}})
    return request(api,'/connection?id=18990')

with concurrent.futures.ThreadPoolExecutor(2) as workers:
    for api, state in zip(apis, workers.map(connect, zip(apis,ids))):
        print(api, json.dumps(state))
print('Connection requests completed. This is not proof of traffic or accepted payment.')
print('Fetch traffic through each node-local HTTP proxy on port 18990 and inspect Hermes confirmation plus shared-billing.db before declaring success.')
PY
