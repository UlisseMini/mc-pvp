"""Train a fresh population from duel outcomes and select by cross-play against learned opponents."""
import argparse
from pathlib import Path
import subprocess
import time
from setup import BUILD, SRC, jdk

SOURCES = ["Fighter","Model","FastDuel","FlatPhysics","TreePolicy","Train","PolicyGradient","League","Refine","Starts","Cup"]


def train(dest,scale,engine="flat26"):
    dest.mkdir(parents=True,exist_ok=False)
    classes=dest/"classes";classes.mkdir()
    subprocess.run([jdk()/"javac","-d",classes,*[SRC/(name+".java") for name in SOURCES]],check=True)
    def run(name,*args,output=None):
        subprocess.run([jdk()/"java","-Xmx1g","-Dsword.transport="+("lockstep" if engine=="flat26" else "historical"),"-cp",classes,"sword."+name,*map(str,args)],check=True,stdout=output)
    def count(n):return max(16,int(n*scale))
    def field(folder,number):
        files=sorted(folder.glob("gen-*.net"))
        return list(dict.fromkeys(files[round((len(files)-1)*i/(number-1))] for i in range(number)))
    evolution=dest/"population";run("Train",evolution,count(420),535713,4)
    gradient=dest/"gradient";run("PolicyGradient",gradient,count(360),157339,*field(evolution,9))
    early=field(evolution,8)+field(gradient,8)
    one=dest/"league-a";two=dest/"league-b"
    run("League",one,count(520),126321,4,*early)
    run("League",two,count(650),773315,4,*early,*field(one,10))
    mid=early+field(one,10)+field(two,10)
    three=dest/"league-c";run("League",three,count(650),821735,4,*mid)
    robust=dest/"refined";run("Refine",robust,count(440),435529,*field(one,10),*field(two,10),*field(three,10),*early)
    folders=[evolution,gradient,one,two,three,robust]
    candidates=dest/"candidates.txt";opponents=dest/"opponents.txt"
    candidates.write_text("".join(str(p)+"\n" for f in folders for p in field(f,17)))
    opponents.write_text("".join(str(p)+"\n" for f in folders for p in field(f,12)))
    with (dest/"crossplay.tsv").open("w") as out:run("Cup",candidates,opponents,193721,output=out)
    rows=[l.split("\t") for l in (dest/"crossplay.tsv").read_text().splitlines()]
    best=max(rows,key=lambda r:.8*float(r[0])+.2*float(r[1]))
    (dest/"champion.net").write_bytes(Path(best[3]).read_bytes())
    (dest/"selection.txt").write_text("\t".join(best)+"\n")
    print("Frozen policy",dest/"champion.net",flush=True)


if __name__=="__main__":
    args=argparse.ArgumentParser()
    args.add_argument("--out",type=Path,default=BUILD/("training-"+str(int(time.time()))))
    args.add_argument("--scale",type=float,default=1)
    args.add_argument("--engine",choices=["flat26","historical"],default="flat26")
    opts=args.parse_args();train(opts.out.resolve(),opts.scale,opts.engine)
