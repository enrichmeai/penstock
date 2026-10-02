#!/bin/sh
# Fixture for scripts/test-check-cards.sh: the manifest.yaml next to this check.sh is
# deliberately invalid YAML, so only the YAML cross-check and staleness rules should fail —
# this check.sh itself stays a clean pass, isolating those two failure paths.
exit 0
