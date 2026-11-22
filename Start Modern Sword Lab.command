#!/bin/zsh
cd -- "${0:A:h}" || exit 1
read "player_name?Your Minecraft Java player name: "
print "1. Fight the trained bot"
print "2. Fight its retreating and strafing rival"
print "3. Blind comparison against the trained bot and plain W-tap"
print "4. Fight the league-trained sword bot"
print "5. Fight the self-play bot that learned to jump when hit"
league_champion=research/sword26/evidence/ladder26/champion.net
if [[ -f "$league_champion" ]]; then
  print "6. Fight the latest champion promoted in ordinary Java"
fi
read "opponent?Choose an opponent [1]: "
lab_args=(--human "$player_name")
case "${opponent:-1}" in
  1) lab_args+=(--pairs 10 --aim-profile tournament) ;;
  2) lab_args+=(--pairs 10 --aim-profile tournament --weights research/sword26/evidence/frozen/opponents/population.net) ;;
  3) lab_args+=(--pairs 5 --aim-profile tournament --blind) ;;
  4) lab_args+=(--pairs 10 --aim-profile tournament --weights research/sword26/evidence/league26/fighter.net) ;;
  5) lab_args+=(--pairs 10 --aim-profile tournament --weights research/sword26/evidence/random26/fighter.net) ;;
  6) [[ -f "$league_champion" ]] || { print "The league is still testing its challengers."; exit 1; }
     lab_args+=(--pairs 10 --weights "$league_champion") ;;
  *) print "Choose one of the displayed opponents."; exit 1 ;;
esac
python3 research/sword26/native.py "${lab_args[@]}"
read "ack?Press Return to close."
