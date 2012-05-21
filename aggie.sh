#!/bin/bash

AGGIE_HOME=$(dirname $0)

if [ ! -e $AGGIE_HOME/target/aggie*jar ]
	then
	echo "Need to compile aggie. This may take a while..."
	mvn install
fi

if [ ! -e $AGGIE_HOME/target/dependency ]
	then
	echo "Installing dependencies..."
	mvn dependency:copy-dependencies
fi

export CLASSPATH=$AGGIE_HOME/target/*:$AGGIE_HOME/target/dependency/*

exec java net.rootdev.aggie.App -o index.html $@
echo "Result is index.html"

# Unused

(
  if flock -n 89
  then
    java net.rootdev.aggie.App -o aggie/index.html $@
  else
    echo Lock failed
    exit 1
  fi
) 89>lockfile
