#!/bin/sh

set -e

sed -i "s/storageEngineList=127.0.0.1#6667#iotdb12/#storageEngineList=127.0.0.1#6667#iotdb12/g" conf/config.properties

sed -i "s/#storageEngineList=127.0.0.1#5432#postgresql/storageEngineList=127.0.0.1#5432#postgresql/g" conf/config.properties

sh -c "sudo sh -c 'echo \"deb http://apt.postgresql.org/pub/repos/apt $(lsb_release -cs)-pgdg main\" > /etc/apt/sources.list.d/pgdg.list'"

sh -c "wget --quiet -O - https://www.postgresql.org/media/keys/ACCC4CF8.asc | sudo apt-key add -"

sh -c "sudo apt-get update"

sh -c "sudo apt-get -y install postgresql-15"

sh -c "sudo chmod +x /var/lib/postgresql"

sh -c "/usr/lib/postgresql/15/bin/initdb -D /var/lib/postgresql/15/main --auth-local peer --auth-host scram-sha-256 --no-instructions"

sh -c "sudo su postgres"

sh -c "sudo systemctl status postgresql.service"