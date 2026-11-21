#!/bin/zsh
export PATH="/opt/homebrew/opt/openjdk@17/bin:/opt/homebrew/bin:/usr/local/bin:/usr/bin:/bin"
cd -- "${0:A:h}"
read -r 'speed?Sumo game speed (0.10 to 1.00, Return for 0.50): '
python3 slow_sumo.py "${speed:-0.5}"
read -r '?Press Return to close this window.'
