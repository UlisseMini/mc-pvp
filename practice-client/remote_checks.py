#!/usr/bin/env python3
"""Bounded local ingest/outbox integrity, retry, proxy and redirect checks."""
from build import *
import capture_ingest as ingest
import gzip,threading,http.server,base64,socket,tempfile

def main():
    checks=0
    def check(value,why):
        nonlocal checks
        checks+=1
        if not value:raise AssertionError(why)
    with tempfile.TemporaryDirectory(prefix='practice-remote-')as tmp:
        root=Path(tmp);cp=os.pathsep.join(map(str,[BUILD/'api',BUILD/'core']));classes=root/'classes'
        compile_to(classes,[SRC/'tools/RemoteUploadFixture.java'],cp);cp=os.pathsep.join([str(classes),cp]);game=root/'client';game.mkdir()
        java=['nice','-n','10',str(JAVA),'-Xmx128m','-cp',cp,'RemoteUploadFixture']
        # Receiver absent: descriptor/gzip survive, no acknowledgement may appear.
        subprocess.run([*java,'prepare',str(game)],check=True,timeout=10)
        check(any((game/'remote-outbox').glob('*.properties')),'durable descriptor created while receiver down')
        check(not any((game/'remote-outbox').glob('*.ack')),'no ack while receiver down')
        sources={p.name:hashlib.sha256(p.read_bytes()).hexdigest()for p in(game/'captures').glob('*.jsonl.gz')};check(bool(sources),'local gzip preserved')
        class Redirect(http.server.BaseHTTPRequestHandler):
            def do_POST(self):self.rfile.read(int(self.headers['Content-Length']));self.send_response(302);self.send_header('Location','http://127.0.0.1:9/must-not-follow');self.end_headers()
            def log_message(self,*args):pass
        redirect=http.server.HTTPServer(('127.0.0.1',8766),Redirect);thread=threading.Thread(target=redirect.serve_forever,daemon=True);thread.start()
        # Existing backlog gets a redirect; never acknowledged, never redirected.
        r=subprocess.run([*java,'retry',str(game)],capture_output=True,text=True,timeout=12)
        check(r.returncode!=0 and'HTTP 302'in r.stderr,'redirect rejected rather than followed')
        check(not any((game/'remote-outbox').glob('*.ack')),'redirect cannot acknowledge source')
        redirect.shutdown();redirect.server_close();thread.join()
        server=http.server.HTTPServer(('127.0.0.1',8766),ingest.Handler);server.archive_root=root/'archive';server.archive_root.mkdir();thread=threading.Thread(target=server.serve_forever,daemon=True);thread.start()
        # NO_PROXY must beat even a JVM-configured unusable HTTP proxy.
        retried=['nice','-n','10',str(JAVA),'-Xmx128m','-Dhttp.proxyHost=127.0.0.1','-Dhttp.proxyPort=9','-cp',cp,'RemoteUploadFixture','retry',str(game)]
        try:
            subprocess.run(retried,check=True,timeout=12)
            check(all(hashlib.sha256((game/'captures'/name).read_bytes()).hexdigest()==sha for name,sha in sources.items()),'sources unchanged after ack')
            session=server.archive_root/'pov'/('r'+'a'*32);manifest=json.loads((session/'manifest.json').read_text());check(manifest['role']=='human-remote'and manifest['expected_sources']==['a-client'],'single-POV archive truthfully tagged')
            check(len(manifest['parts'])==len(sources),'all parts acknowledged')
            for name,digest in sources.items():
                rows=[json.loads(line)for line in gzip.decompress((session/name).read_bytes()).splitlines()];check(rows[0]['type']=='pov_archive_start'and rows[-1]['type']=='capture_end','canonical archive headers/footers')
                before=(session/'manifest.json').read_bytes();body=(game/'captures'/name).read_bytes()
                check(ingest.ingest(server.archive_root,'r'+'a'*32,name,'example.invalid:25565',digest,body)==digest,'repeat delivery checksum dedupes')
                check((session/'manifest.json').read_bytes()==before,'duplicate does not add rows/parts')
            badbody=gzip.compress(b'{"type":"capture_end"}\n')
            for session_id,name,digest,body in [('r../escape','a-client-bad-events.jsonl.gz',hashlib.sha256(badbody).hexdigest(),badbody),('r'+'b'*32,'../escape',hashlib.sha256(badbody).hexdigest(),badbody),('r'+'b'*32,'a-client-bad-events.jsonl.gz','0'*64,badbody),('r'+'b'*32,'a-client-bad-events.jsonl.gz',hashlib.sha256(badbody[:-3]).hexdigest(),badbody[:-3])]:
                failed=False
                try:ingest.ingest(server.archive_root,session_id,name,'host',digest,body)
                except(ValueError,OSError,EOFError):failed=True
                check(failed,'unsafe/corrupt upload rejected')
        finally:server.shutdown();server.server_close();thread.join()
    print(json.dumps({'checks':checks,'scope':'local-only ingest, durable retry, idempotence, no proxy/redirect; no MC connection'},indent=2))
if __name__=='__main__':main()
