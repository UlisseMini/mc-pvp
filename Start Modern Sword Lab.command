#!/bin/zsh
cd -- "${0:A:h}" || exit 1
read "player_name?Your Minecraft Java player name: "
python3 research/sword26/native.py --human "$player_name" --pairs 10
read "ack?Press Return to close."
