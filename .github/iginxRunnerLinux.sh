#!/bin/sh

set -e

sh -c "chmod +x core/target/iginx-core-0.6.0-SNAPSHOT/sbin/start_iginx.sh"

sh -c "nohup core/target/iginx-core-0.6.0-SNAPSHOT/sbin/start_iginx.sh &" 2> /home/runner/work/IginX/IginX/.github/actions/iginxRunner/iginxLog.txt