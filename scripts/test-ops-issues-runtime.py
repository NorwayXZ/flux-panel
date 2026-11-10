#!/usr/bin/env python3
"""Only exercises the disposable MySQL runtime-test containers, never a user panel."""
import base64, hashlib, hmac, json, os, subprocess, sys, time, urllib.request, urllib.error
assert os.environ.get('FLUX_PANEL_RUNTIME_TEST') == '1'
base=sys.argv[1]
assert base.startswith('http://127.0.0.1:')
def sql(statement):
 return subprocess.check_output(['docker','exec','flux-panel-runtime-test-db','mysql','-uroot','-ptestroot','flux_test','-Nse',statement],text=True).strip()
def b64(value):return base64.urlsafe_b64encode(json.dumps(value).encode()).rstrip(b'=').decode()
def token(role):
 message=b64({'alg':'HS256','typ':'JWT'})+'.'+b64({'sub':'1','role_id':role,'user':'admin','iat':int(time.time()),'exp':int(time.time())+600})
 return message+'.'+base64.urlsafe_b64encode(hmac.new(b'test-only-secret-with-sufficient-length-123456',message.encode(),hashlib.sha256).digest()).rstrip(b'=').decode()
def api(path,payload=None,role=0):
 request=urllib.request.Request(base+'/api/v1/'+path,json.dumps(payload or {}).encode(),{'Authorization':token(role),'Content-Type':'application/json'})
 try:
  with urllib.request.urlopen(request,timeout=30) as response:return response.status,json.load(response)
 except urllib.error.HTTPError as error:
  raw=error.read()
  try: result=json.loads(raw)
  except (json.JSONDecodeError, UnicodeDecodeError): result={}
  return error.code,result
now=int(time.time()*1000)
sql(f"""INSERT INTO monitoring_alert(resource_type,resource_id,resource_name,owner_user_id,severity,status,title,detail,started_at,updated_at)
 VALUES('node',991122,'isolated-issue',1,'critical','open','isolated-issue-workflow','offline',{now},{now});""")
r=api('ops-issues/list')[1];assert r['code']==0,r
issue=next(i for i in r['data'] if i['title']=='isolated-issue-workflow')
assert issue['phase']=='detected'
r=api('ops-issues/context',{'id':issue['id']})[1];assert r['code']==0 and r['data']['nodeIds']==[991122],r
r=api('ops-issues/note',{'id':issue['id'],'note':'仅记录处理，不代表修复成功'})[1];assert r['code']==0,r
assert sql(f"SELECT phase FROM ops_issue WHERE id={issue['id']}")=='awaiting_verification'
r=api('ops-issues/verify',{'id':issue['id']})[1];assert r['code']==0,r
assert sql(f"SELECT phase FROM ops_issue WHERE id={issue['id']}")=='awaiting_verification', 'missing resource must never pass verification'
code,result=api('ops-issues/list',role=1);assert code==403 or result.get('code') in [403,-1],(code,result)
# Removed controllers must not remain reachable in a clean packaged backend.
for path in ['aws-access/list','docker-apps/overview']:
 code,result=api(path);assert code==404 or result.get('code')==404,(path,code,result)
print('Operations workflow real MySQL/API checks passed')
