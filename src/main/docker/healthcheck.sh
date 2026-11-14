#!/bin/bash
# Container health check for images without curl (the native micro image has bash only).
# Exit 0 only when GET <path> on the loopback-bound HTTP server answers 200. Default path: liveness.
# ECS task definition: "healthCheck": {"command": ["CMD", "/work/healthcheck.sh"], ...}
path="${1:-/q/health/live}"
exec 3<>"/dev/tcp/127.0.0.1/${QUARKUS_HTTP_PORT:-8080}" || exit 1
printf 'GET %s HTTP/1.0\r\nHost: localhost\r\n\r\n' "$path" >&3
read -r -t 5 status <&3 || exit 1
[[ "$status" == *" 200 "* ]]
