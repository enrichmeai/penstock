#!/bin/sh
# Verifies this pattern's skeleton still works, as cheaply as "patterns/README.md" allows:
# no Spring context, no full Gradle build. Three checks, each with a visible SKIP rather than
# a false pass when this environment can't do it (CLAUDE.md: "a gate that could not run is
# reported as not run, never as passing").
set -eu
cd "$(dirname "$0")"

status=0

echo "[credential-grep] scanning skeleton/ for credential-shaped values"
if grep -rEn '(sk-[A-Za-z0-9]{10,}|ghp_[A-Za-z0-9]{10,}|AKIA[0-9A-Z]{10,}|-----BEGIN [A-Z ]*PRIVATE KEY-----|(password|token|secret)[[:space:]]*[:=][[:space:]]*[^<[:space:]])' skeleton/ 2>/dev/null; then
    echo "FAIL: skeleton/ contains a credential-shaped value"
    status=1
else
    echo "PASS: no credential-shaped value in skeleton/"
fi

echo "[javac] parsing skeleton/*.java"
if ! command -v javac >/dev/null 2>&1; then
    echo "SKIP (not run): no javac on PATH"
else
    outdir=$(mktemp -d)
    trap 'rm -rf "$outdir"' EXIT

    # The SDK-free file needs no classpath at all.
    if javac -proc:none -d "$outdir" skeleton/ModeSwitchSkeleton.java 2>&1; then
        echo "PASS: ModeSwitchSkeleton.java parses"
    else
        echo "FAIL: ModeSwitchSkeleton.java did not parse"
        status=1
    fi

    # AcpModeSkeleton.java / AcpStdioRunnerSkeleton.java reference types spread across all of
    # the acp-agent-support SDK's own jars (annotations, core, agent-support) — one jar alone
    # is not enough. Fall back visibly rather than guess a classpath that silently fails.
    sdk_jars=$(find "${GRADLE_USER_HOME:-$HOME/.gradle}/caches/modules-2/files-2.1/com.agentclientprotocol" \
        -type f -name '*.jar' 2>/dev/null | tr '\n' ':')
    if [ -z "$sdk_jars" ]; then
        echo "SKIP (not run): no com.agentclientprotocol:*:0.18.0 jars found under \${GRADLE_USER_HOME:-\$HOME/.gradle}/caches — run './gradlew build' first so Gradle has resolved them"
    elif javac -proc:none -cp "$sdk_jars" -d "$outdir" skeleton/AcpModeSkeleton.java skeleton/AcpStdioRunnerSkeleton.java 2>&1; then
        echo "PASS: AcpModeSkeleton.java and AcpStdioRunnerSkeleton.java parse against the cached SDK jars"
    else
        echo "FAIL: skeleton did not parse against the cached SDK jars ($sdk_jars)"
        status=1
    fi
fi

echo "[release-jar] penstock-0.3.0.jar --acp answers initialize with one JSON line on stdout"
cache_dir="${CHECK_SH_CACHE_DIR:-$HOME/.cache/penstock-pattern-check}"
jar_path="$cache_dir/penstock-0.3.0.jar"
jar_url="https://github.com/enrichmeai/penstock/releases/download/v0.3.0/penstock-0.3.0.jar"

if ! command -v java >/dev/null 2>&1; then
    echo "SKIP (not run): no java on PATH"
elif [ ! -f "$jar_path" ] && ! command -v curl >/dev/null 2>&1; then
    echo "SKIP (not run): penstock-0.3.0.jar not cached and no curl on PATH to download it"
else
    if [ ! -f "$jar_path" ]; then
        mkdir -p "$cache_dir"
        if ! curl -fsSL -m 30 -o "$jar_path.tmp" "$jar_url" 2>/dev/null; then
            rm -f "$jar_path.tmp"
            echo "SKIP (not run): could not download $jar_url (no network in this environment)"
        else
            mv "$jar_path.tmp" "$jar_path"
        fi
    fi

    if [ -f "$jar_path" ]; then
        # The agent isn't listening until Spring Boot + the ACP transport have both started
        # (~3s): a request written to a plain pipe before then is lost, not queued — this
        # tripped up an earlier version of this very check. Hold the stdin pipe open (a FIFO,
        # not a pipe that closes after one write) and wait for the transport's own readiness
        # line in stderr before sending anything.
        rundir=$(mktemp -d)
        mkfifo "$rundir/in"
        : > "$rundir/stderr.txt"

        AGENT_WORKSPACE="$rundir" timeout 30 java -jar "$jar_path" --acp \
            < "$rundir/in" >"$rundir/stdout.txt" 2>"$rundir/stderr.txt" &
        appid=$!

        exec 9>"$rundir/in"

        ready=0
        i=0
        while [ "$i" -lt 100 ]; do
            if grep -q "ACP agent transport started" "$rundir/stderr.txt" 2>/dev/null; then
                ready=1
                break
            fi
            if ! kill -0 "$appid" 2>/dev/null; then
                break
            fi
            i=$((i + 1))
            sleep 0.2
        done

        if [ "$ready" = "1" ]; then
            printf '%s\n' '{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":1}}' >&9
            sleep 1
        fi
        exec 9>&-
        wait "$appid" 2>/dev/null || true

        if [ "$ready" != "1" ]; then
            echo "FAIL: penstock-0.3.0.jar --acp never logged transport readiness within 20s"
            status=1
        else
            lines=$(wc -l < "$rundir/stdout.txt" | tr -d ' ')
            if [ "$lines" = "1" ] && grep -q '"result"' "$rundir/stdout.txt"; then
                echo "PASS: exactly one JSON-RPC response line on stdout"
            else
                echo "FAIL: expected exactly one JSON-RPC response line on stdout, got $lines"
                status=1
            fi
        fi
        rm -rf "$rundir"
    fi
fi

exit $status
