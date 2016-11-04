#!/bin/bash 

echo 'Resolving $TASK_NAME.hdfs.mesos';
while [ -z `dig +short ${TASK_NAME}.hdfs.mesos` ]; do
    echo 'Cannot resolve DNS name: $TASK_NAME.hdfs.mesos'; 
    dig +short $TASK_NAME.hdfs.mesos;
    sleep 1;
done; 

echo 'Resolved name: $TASK_NAME.hdfs.mesos';
dig +short $TASK_NAME.hdfs.mesos;

echo 'Creating Name Node name directory';
mkdir -p volume/hdfs/data/name;

if [ ! -f volume/hdfs/data/name/current/VERSION ]; then
    echo 'Formatting or Bootstrapping has not been done';

    echo 'NameNode bootstrap';
    ./$HDFS_VERSION/bin/hdfs namenode -bootstrapStandBy;
    echo Bootstrap exit code: $?;

    echo 'Formatting filesystem';
    ./$HDFS_VERSION/bin/hdfs namenode -format -nonInteractive;
    echo Format exit code: $?;

    echo 'ZKFC format';
    ./$HDFS_VERSION/bin/hdfs zkfc -formatZK -nonInteractive;
    echo Format ZKFC exit code: $?;

    echo 'Initialize Shared Edits';
    ./$HDFS_VERSION/bin/hdfs namenode -initializeSharedEdits -nonInteractive;
    echo Initialize shared edits exit code: $?;
fi

echo 'Starting Name Node' && ./$HDFS_VERSION/bin/hdfs namenode;
