import argparse
import importlib.util
import os
from pathlib import Path
import shutil
import subprocess
import sys

root=Path(__file__).resolve().parents[2]
sys.path.insert(0,str(root))
import forge_runtime
spec=importlib.util.spec_from_file_location('practice_build',root/'practice-client/build.py')
builder=importlib.util.module_from_spec(spec)
spec.loader.exec_module(builder)
result=root/'runtime/bow-aim'
parser=argparse.ArgumentParser(description="Run the bow checks in an isolated native Forge client.")
parser.add_argument('--build',type=Path,default=root/'build/bow-aim/practice-client')
parser.add_argument('--name',default='forge-smoke')
args=parser.parse_args()
game=result/args.name
built=args.build.resolve()
for d in ('mods','config/pvp-practice','home','tmp'):
    (game/d).mkdir(parents=True,exist_ok=True)
cp=forge_runtime.stage_dependencies()
java_cp=os.pathsep.join(map(str,[built/'compile-api.jar',built/'api',built/'core',*cp]))
builder.compile_to(game/'probe-classes',[root/'research/bow-aim/ForgeProbe.java'],java_cp)
builder.jar(game/'mods/bow-aim-probe.jar',game/'probe-classes')
for src,dst in ((built/'practice-bridge.jar','mods/practice-bridge.jar'),
                (built/'practice-module.jar','config/pvp-practice/practice-module.jar'),
                (result/'native-cases.bin','native-cases.bin'),
                (root/'practice-client/settings.properties','config/pvp-practice/settings.properties')):
    shutil.copyfile(src,game/dst)
(game/'config/splash.properties').write_text('enabled=false\n')
(game/'config/pvp-practice/bow-aim.properties').unlink(missing_ok=True)
(game/'options.txt').write_text('renderDistance:2\nmaxFps:30\nsoundCategory_master:0.0\n')
jvm_args=['-Xmx768M','-Dhmc.lwjgl.update_sleep=1','-Dlog4j.configurationFile='+str(root/'.cache/server-logging.xml'),
      '-javaagent:'+str(forge_runtime.HEADLESS),'-cp',os.pathsep.join(map(str,cp)),
      'net.minecraft.launchwrapper.Launch','--username','BowProbe','--version','1.8.9',
      '--gameDir',str(game),'--assetsDir',str(forge_runtime.ASSETS),'--assetIndex','1.8',
      '--uuid','f114a4fe-ff05-4b22-a946-78fc97bac7fc','--accessToken','0','--userProperties','{}',
      '--userType','legacy','--tweakClass','net.minecraftforge.fml.common.launcher.FMLTweaker']
log=result/('native-smoke.log' if game.name=='forge-smoke' else game.name+'.log')
with log.open('w') as out:
    proc=subprocess.run(forge_runtime.command(jvm_args,game),cwd=game,stdout=out,stderr=subprocess.STDOUT,
                        env={'PATH':'/usr/bin:/bin','LANG':'en_US.UTF-8','TMPDIR':str(game/'tmp')+'/'},timeout=100)
lines=log.read_text(errors='replace').splitlines()
ok=[line for line in lines if 'BOW_NATIVE_PASS' in line]
if proc.returncode or not ok:
    print('\n'.join(lines[-28:]))
    raise SystemExit(1)
print('\n'.join(ok))
