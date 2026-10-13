#!/bin/bash

# Exports every variable declared in .env (falling back to .env.example).
#
# The variable names are extracted with sed+xargs, so comment lines and blank lines are
# stripped first: a "#" line reaches xargs as bare words and an apostrophe inside one makes
# xargs abort on an unmatched quote. Under `set -e` that kills the calling script before it
# does any work, which looks exactly like a successful no-op run.
ENV_FILE=.env.example
if [[ -f .env ]]; then
  ENV_FILE=.env
fi

source "${ENV_FILE}"
export $(grep -v '^[[:space:]]*#' "${ENV_FILE}" | grep -v '^[[:space:]]*$' | sed 's/=.*//g' | xargs)
