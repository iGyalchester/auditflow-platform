#!/bin/bash
# LocalStack "ready" hook: create the evidence bucket enrichment-service
# writes to, so a fresh `docker compose up` needs no manual step.
awslocal s3 mb s3://auditflow-events 2>/dev/null || true
echo "auditflow-events bucket ready"
