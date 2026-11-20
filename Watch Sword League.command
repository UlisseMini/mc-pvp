#!/bin/zsh
cd -- "${0:A:h}" || exit 1
exec python3 research/sword26/watch_server.py --open
