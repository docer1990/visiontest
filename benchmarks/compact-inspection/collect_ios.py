import argparse, json, subprocess, time, urllib.request, hashlib, platform
from pathlib import Path
import tiktoken
enc=tiktoken.get_encoding('o200k_base')
parser=argparse.ArgumentParser(description='Collect one full/compact iOS fixture pair per case; requires an isolated ready simulator.')
parser.add_argument('--udid', required=True)
parser.add_argument('--runtime', required=True)
parser.add_argument('--output', required=True, type=Path)
args=parser.parse_args()
root=Path(__file__).resolve().parents[2]
out=args.output
out.mkdir(exist_ok=False,parents=True)
udid=args.udid; bundle='com.example.visiontest.benchmark'
base=['java','-jar',str(root/'app/build/libs/visiontest.jar')]
def rpc(method,params):
 req=urllib.request.Request('http://localhost:9009/jsonrpc',data=json.dumps({'jsonrpc':'2.0','id':1,'method':method,'params':params}).encode(),headers={'Content-Type':'application/json'})
 with urllib.request.urlopen(req, timeout=10) as r: return json.load(r)
def reset(scenario):
 p=subprocess.run(['xcrun','simctl','launch','--terminate-running-process',udid,bundle,scenario,'en'],capture_output=True,text=True,timeout=30)
 if p.returncode: return {'ok':False,'exitCode':p.returncode,'error':p.stderr}
 deadline=time.monotonic()+15
 while time.monotonic()<deadline:
  r=rpc('ui.findElement',{'resourceId':'fixture_ready','bundleId':bundle}).get('result',{})
  if r.get('found') is True: return {'ok':True}
  if r.get('error'): return {'ok':False,'error':r['error']}
  time.sleep(.1)
 return {'ok':False,'error':'Fixture readiness timeout'}
report={'revision':subprocess.check_output(['git','rev-parse','HEAD'],cwd=root,text=True).strip(),'platform':'ios','simulator':udid,'runtime':args.runtime,'host':platform.platform(),'tokenizer':{'name':'tiktoken','version':tiktoken.__version__,'encoding':enc.name,'accounting':'estimated stdout text tokens; no agent/provider usage'},'samples':[],'methodology':'One sample per case/mode; setup outside timing; warm native server, fresh JVM per invocation; no latency inference; full then compact order; all attempted samples retained.'}
for name,path in [('jar','app/build/libs/visiontest.jar'),('fixture','benchmarks/fixtures/ios/.derivedData/Build/Products/Debug-iphonesimulator/BenchmarkFixture.app/BenchmarkFixture')]:
 report[name+'Sha256']=hashlib.sha256((root/path).read_bytes()).hexdigest()
scope=' --bundle-id '+bundle
cases=[('form','navigation',['input_text agent@example.test --target-resource-id email'+scope,'wait_for_element --resource-id email'+scope]),('navigation','navigation',['tap_on_element --resource-id continue'+scope,'wait_for_element --resource-id done'+scope]),('ambiguous','ambiguous',['tap_on_element --resource-id continue_secondary'+scope,'wait_for_element --resource-id continue_secondary'+scope]),('missing','missing',['wait_for_element --resource-id never-present --timeout 500'+scope])]
for name,scenario,steps in cases:
 for compact in (False,True):
  sample={'case':name,'mode':'compact' if compact else 'full','setup':reset(scenario)}
  report['samples'].append(sample)
  if sample['setup']['ok']:
   commands=[('inspection',base+['get_interactive_elements','-p','ios','--bundle-id',bundle,'--json']+(['--compact'] if compact else [])),('flow',base+['flow','-p','ios','--bundle-id',bundle,'--json','--observe','interactive-elements','--on-failure','interactive-elements']+sum((['--step',s] for s in steps),[])+(['--compact'] if compact else []))]
   for kind,argv in commands:
    start=time.monotonic()
    try:
     p=subprocess.run(argv,capture_output=True,text=True,timeout=60)
     raw=p.stdout; data=json.loads(raw) if raw else None
     sample[kind]={'durationMs':round((time.monotonic()-start)*1000,3),'exitCode':p.returncode,'stdout':data,'stderr':p.stderr,'bytes':len(raw.encode()),'estimatedTokens':len(enc.encode(raw))}
    except Exception as e: sample[kind]={'error':str(e),'durationMs':round((time.monotonic()-start)*1000,3)}
  (out/'results.json').write_text(json.dumps(report,ensure_ascii=False,indent=2)+'\n')
  print(name,sample['mode'],{k:v.get('exitCode',v.get('error')) for k,v in sample.items() if k in ('inspection','flow')},flush=True)
print(out/'results.json')
