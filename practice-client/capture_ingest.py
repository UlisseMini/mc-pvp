#!/usr/bin/env python3
"""Loopback-only durable ingest into the same runtime/pov archive as lab replays.
No Minecraft connection, commands, filesystem download endpoint or external I/O.
"""
import argparse,base64,fcntl,gzip,hashlib,http.server,io,ipaddress,json,os,re,subprocess,sys,tempfile,time,urllib.request
from pathlib import Path
ROOT=Path(__file__).resolve().parents[1]
PORT=8766
MAX_COMPRESSED=32*1024*1024
MAX_RAW=32*1024*1024

def atomic(path,data):
    fd,tmp=tempfile.mkstemp(prefix='.ingest-',dir=path.parent)
    try:
        with os.fdopen(fd,'wb')as out:out.write(data);out.flush();os.fsync(out.fileno())
        os.replace(tmp,path)
        parent=os.open(path.parent,os.O_RDONLY)
        try:os.fsync(parent)
        finally:os.close(parent)
    finally:Path(tmp).unlink(missing_ok=True)

def ingest(root,session,name,host,digest,body):
    if not re.fullmatch(r'r[0-9a-f]{32}',session):raise ValueError('session')
    if not re.fullmatch(r'a-client-[A-Za-z0-9-]+-events(?:-part[0-9]{1,5})?\.jsonl\.gz',name):raise ValueError('part')
    if len(host)>255 or any(ord(c)<32 for c in host):raise ValueError('host')
    if not re.fullmatch(r'[0-9a-f]{64}',digest)or hashlib.sha256(body).hexdigest()!=digest:raise ValueError('digest')
    folder=root/'pov'/session;folder.mkdir(parents=True,exist_ok=True)
    manifest_path=folder/'manifest.json';manifest=json.loads(manifest_path.read_text())if manifest_path.exists()else{
        'schema':1,'match':session,'role':'human-remote','server_host':host,'start_ms':None,'finish_ms':None,
        'players':{},'expected_sources':['a-client'],'available_sources':['a-client'],'server_replay':None,
        'interactive_server_replay_available':False,'source_scope':'own-client only; no authoritative server or opponent-client POV',
        'clock':'client UNIX milliseconds; monotonic values process-local','parts':{}}
    if manifest['server_host']!=host:raise ValueError('session host changed')
    prior=manifest['parts'].get(name)
    if prior:
        if prior['sha256']!=digest:raise ValueError('conflicting duplicate')
        if not (folder/name).exists():raise ValueError('acknowledged part missing')
        return digest
    poses=[];counts={};footer=None;start=None;last=None;capture_meta={}
    with gzip.GzipFile(fileobj=io.BytesIO(body))as raw:
        total=0
        while True:
            line=raw.readline(4*1024*1024+1)
            if not line:break
            total+=len(line)
            if len(line)>4*1024*1024 or total>MAX_RAW:raise ValueError('decompressed limit')
            row=json.loads(line);kind=row.get('type');counts[kind]=counts.get(kind,0)+1
            if isinstance(row.get('time_ms'),(int,float)):
                at=row['time_ms'];start=at if start is None else min(start,at);last=at if last is None else max(last,at)
            if kind=='capture_start':capture_meta=row.get('data',{})
            if kind=='remote_pose':
                poses.append({'type':'pov_frame','time_ms':row['time_ms'],'monotonic_ns':row['monotonic_ns'],
                    'match':session,'source':'a-client','view':row['data']['view']})
            footer=row
    if not footer or footer.get('type')!='capture_end':raise ValueError('incomplete gzip part')
    if capture_meta and(capture_meta.get('role')!='human-remote'or capture_meta.get('remote_session')!=session):raise ValueError('capture identity')
    metadata={'role':'human-remote','server_host':host,'source_scope':manifest['source_scope'],**capture_meta}
    header={'type':'pov_archive_start','schema':1,'match':session,'source':'a-client','metadata':metadata,'time_ms':start}
    # Preserve every original event and its compressed bytes, with one identity member.
    atomic(folder/name,gzip.compress((json.dumps(header,separators=(',',':'))+'\n').encode(),mtime=0)+body)
    pose_name=name.replace('-events','-poses');pose_footer=dict(footer)
    if pose_footer.get('continues'):pose_footer['continues']=pose_footer['continues'].replace('-events','-poses')
    pose_data=''.join(json.dumps(r,separators=(',',':'))+'\n'for r in[header,*poses,pose_footer]).encode()
    atomic(folder/pose_name,gzip.compress(pose_data,mtime=0))
    manifest['parts'][name]={'sha256':digest,'compressed_source_bytes':len(body),'counts':counts,'writer_integrity':footer,'pose_file':pose_name}
    if start is not None:manifest['start_ms']=min(start,manifest['start_ms']if manifest['start_ms']is not None else start)
    if last is not None:manifest['last_received_sample_ms']=max(last,manifest.get('last_received_sample_ms',last))
    if counts.get('connection_end')or counts.get('capture_stopped'):manifest['finish_ms']=last
    manifest['metadata']=metadata if capture_meta else manifest.get('metadata',metadata)
    atomic(manifest_path,(json.dumps(manifest,indent=2)+'\n').encode())
    index=root/'pov'/'remote-index.json';data=json.loads(index.read_text())if index.exists()else{'schema':1,'sessions':{}}
    data['sessions'][session]={'role':'human-remote','server_host':host,'start_ms':manifest['start_ms'],'last_sample_ms':manifest.get('last_received_sample_ms'),
        'manifest':session+'/manifest.json','source_scope':manifest['source_scope'],'parts':len(manifest['parts'])}
    atomic(index,(json.dumps(data,indent=2)+'\n').encode())
    return digest

class Handler(http.server.BaseHTTPRequestHandler):
    server_version='PracticeLocalCapture/1'
    def log_message(self,*args):pass  # Never print payload/chat/host/account data.
    def response(self,status,body=b''):
        self.send_response(status);self.send_header('Content-Type','text/plain');self.send_header('Content-Length',str(len(body)));self.end_headers();self.wfile.write(body)
    def do_GET(self):
        if self.path=='/health':self.response(200,b'practice-local-capture-v1\n')
        else:self.response(404)
    def do_POST(self):
        if not ipaddress.ip_address(self.client_address[0]).is_loopback or self.path!='/practice/capture':return self.response(403)
        try:
            length=int(self.headers.get('Content-Length','-1'))
            if self.headers.get('Content-Type')!='application/gzip'or not 1<=length<=MAX_COMPRESSED:raise ValueError('request size/type')
            self.connection.settimeout(10);body=self.rfile.read(length)
            if len(body)!=length:raise ValueError('short request')
            host=base64.b64decode(self.headers.get('X-Practice-Host',''),validate=True).decode('utf-8')
            digest=ingest(self.server.archive_root,self.headers.get('X-Practice-Session',''),self.headers.get('X-Practice-Part',''),host,self.headers.get('X-Practice-Sha256',''),body)
            self.response(200,(digest+'\n').encode())
        except(ValueError,KeyError,TypeError,UnicodeError,OSError,EOFError):self.response(400,b'capture rejected; local source retained\n')

class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self,*args,**kwargs):return None

def ensure_running(root=ROOT/'runtime'):
    try:
        req=urllib.request.Request(f'http://127.0.0.1:{PORT}/health')
        opener=urllib.request.build_opener(urllib.request.ProxyHandler({}),NoRedirect())
        with opener.open(req,timeout=.3)as response:
            if response.read(100)==b'practice-local-capture-v1\n':return
    except OSError:pass
    root.mkdir(parents=True,exist_ok=True);log=(root/'capture-ingest.log').open('ab')
    subprocess.Popen([sys.executable,str(Path(__file__).resolve()),'--serve','--root',str(root)],stdin=subprocess.DEVNULL,stdout=log,stderr=log,start_new_session=True);log.close()

if __name__=='__main__':
    ap=argparse.ArgumentParser(description=__doc__);ap.add_argument('--serve',action='store_true');ap.add_argument('--root',type=Path,default=ROOT/'runtime');args=ap.parse_args()
    if not args.serve:ensure_running(args.root)
    else:
        args.root.mkdir(parents=True,exist_ok=True);lock=(args.root/'capture-ingest.lock').open('a')
        try:fcntl.flock(lock,fcntl.LOCK_EX|fcntl.LOCK_NB)
        except BlockingIOError:raise SystemExit('Local capture receiver already running')
        server=http.server.HTTPServer(('127.0.0.1',PORT),Handler);server.archive_root=args.root
        (args.root/'capture-ingest.pid').write_text(str(os.getpid()));server.serve_forever()
