#!/bin/bash
set -e

source ./set-env.sh || { echo "ERROR: failed to load the environment (set-env.sh)" >&2; exit 1; }

mkdir -p ./artifacts

# Drop artifacts left by an earlier build so only the module built from the checked-out
# source gets deployed.
rm -f ./artifacts/*-SNAPSHOT.jar ./artifacts/*-SNAPSHOT.tgz

MODULE_JAR=$(ls -t ../target/${MODULE_ID}-*-SNAPSHOT.jar 2>/dev/null | head -n 1 || true)
if [[ -z "${MODULE_JAR}" ]]; then
  echo "ERROR: no ${MODULE_ID}-*-SNAPSHOT.jar found in ../target" >&2
  echo "       Build the module first: (cd .. && mvn clean install)" >&2
  exit 1
fi

# The jar must be at least as new as the sources it is supposed to contain; otherwise the suite
# would test an older build and report on code that is no longer there.
STALE=$(find ../src ../pom.xml -type f -newer "${MODULE_JAR}" 2>/dev/null | head -n 1)
if [[ -n "${STALE}" ]]; then
  echo "ERROR: ${STALE} is newer than ${MODULE_JAR}" >&2
  echo "       The jar is stale. Rebuild the module: (cd .. && mvn clean install)" >&2
  exit 1
fi
cp "${MODULE_JAR}" ./artifacts/

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
