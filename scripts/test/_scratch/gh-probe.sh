#!/bin/sh
set -e
for n in 74 76 79 83; do
  echo "=== pr $n ==="
  gh pr view "$n" --repo enrichmeai/penstock --json number,title,body,mergeCommit,files
  echo
done
