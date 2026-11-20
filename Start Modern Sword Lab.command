#!/bin/zsh
cd -- "${0:A:h}" || exit 1
read "player_name?Your Minecraft Java player name: "
print "1. Fight the trained bot"
print "2. Fight its retreating and strafing rival"
print "3. Blind comparison against the trained bot and plain W-tap"
print "4. Fight the new league-trained sword bot"
read "opponent?Choose an opponent [1]: "
lab_args=(--human "$player_name")
case "${opponent:-1}" in
  1) lab_args+=(--pairs 10) ;;
  2) lab_args+=(--pairs 10 --weights research/sword26/evidence/frozen/opponents/population.net) ;;
  3) lab_args+=(--pairs 5 --blind) ;;
  4) lab_args+=(--pairs 10 --weights research/sword26/evidence/league26/fighter.net) ;;
  *) print "Enter a number from 1 through 4."; exit 1 ;;
esac
python3 research/sword26/native.py "${lab_args[@]}"
read "ack?Press Return to close."
