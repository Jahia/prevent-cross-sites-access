#!/bin/bash
set -e

source ./set-env.sh

mkdir -p ./artifacts

if [[ -e ../target ]]; then
  cp -R ../target/*-SNAPSHOT.jar ./artifacts/
fi

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
