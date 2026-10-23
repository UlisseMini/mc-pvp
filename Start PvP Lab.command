#!/bin/zsh
export PATH="/opt/homebrew/opt/openjdk@17/bin:/opt/homebrew/bin:/usr/local/opt/openjdk@17/bin:/usr/local/bin:/usr/bin:/bin:/usr/sbin:/sbin"
cd -- "${0:A:h}"
python3 lab.py serve
read -r '?Press Return to close this window.'
