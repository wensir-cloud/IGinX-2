#!/bin/sh

set -e

sed -i "" "s/storageEngineList=127.0.0.1#6667#iotdb12/#storageEngineList=127.0.0.1#6667#iotdb12/g" conf/config.properties

sed -i "" "s/#storageEngineList=127.0.0.1#5432#postgresql/storageEngineList=127.0.0.1#5432#postgresql/g" conf/config.properties

sh -c "wget https://get.enterprisedb.com/postgresql/postgresql-15.2-1-osx-binaries.zip"

sh -c "sudo unzip -q postgresql-15.2-1-osx-binaries.zip"

sh -c "sudo dscl . -create /Users/postgres"

sh -c "sudo dscl . -create /Users/postgres UserShell /bin/bash"

sh -c "sudo dscl . -passwd /Users/postgres postgres"

sh -c "sudo dscl . -append /Groups/admin GroupMembership postgres"

sh -c "sudo reboot"

sh -c "sudo mkdir -p /usr/local/var/postgresql@15"

sh -c "sudo chown -R postgres /usr/local/var/postgresql@15"

sh -c "sudo chmod -R 777 /var/lib/postgresql/15/main"

sh -c "/Users/runner/work/IGinX-2/IGinX-2/pgsql/bin/initdb -D /usr/local/var/postgresql@15"

sh -c "/Users/runner/work/IGinX-2/IGinX-2/pgsql/bin/pg_ctl -D /usr/local/var/postgresql@15 start"

sh -c "psql -c \"ALTER USER postgres WITH PASSWORD 'postgres';\""
