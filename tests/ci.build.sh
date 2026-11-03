#!/bin/bash
set -e

source ./set-env.sh || { echo "ERROR: failed to load the environment (set-env.sh)" >&2; exit 1; }

mkdir -p ./artifacts

# Drop artifacts left by an earlier build so only the module built from the checked-out
# source gets deployed.
rm -f ./artifacts/*-SNAPSHOT.jar ./artifacts/*-SNAPSHOT.tgz

if ! ls ../target/*-SNAPSHOT.jar >/dev/null 2>&1; then
  echo "ERROR: no *-SNAPSHOT.jar found in ../target" >&2
  echo "       Build the module first: (cd .. && mvn clean install)" >&2
  exit 1
fi
cp ../target/*-SNAPSHOT.jar ./artifacts/

# Everything in ./artifacts gets deployed into Jahia. An empty directory means the suite would
# boot a Jahia without the module under test and report a green run that proves nothing, so
# stop here instead.
if ! ls ./artifacts/*-SNAPSHOT.jar >/dev/null 2>&1; then
  echo "ERROR: no *-SNAPSHOT.jar found in ./artifacts" >&2
  echo "       Build the module first: (cd .. && mvn clean install)" >&2
  exit 1
fi

version=$(node -p "require('./package.json').devDependencies['@jahia/cypress']")
echo Using @jahia/cypress@$version...
npx --yes --package @jahia/cypress@$version ci.build
