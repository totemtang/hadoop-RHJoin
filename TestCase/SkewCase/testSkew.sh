#!/bin/bash
SKEWHOME=$(cd "$(dirname "$0")"; pwd)
L=5000 #5GB of L
R=100  #100M of R
partNum=200
reduceNum=38
HADOOP=$SKEWHOME/../..
RHJOIN=/RHJOIN
OUTPUT=$RHJOIN/output

echo "Directed Join Start"
time hadoop jar $HADOOP/RHJoin-Test.jar org.ict.totemtang.directedjoin.DirectedJoinSkew $RHJOIN/RTable-${R}M-${partNum} $RHJOIN/LTable-${L}M-${R}M-${partNum} $OUTPUT
sleep 10s
hadoop fs -rmr $OUTPUT
echo "Directed Join End"

echo "Repartition Join Start"
time hadoop jar $HADOOP/RHJoin-Test.jar org.ict.totemtang.repartitionjoin.RepartitionJoinSkew $RHJOIN/RTable-${R}M $RHJOIN/LTable-${L}M-${R}M $OUTPUT ${reduceNum}
sleep 10s
hadoop fs -rmr $OUTPUT
echo "Repartition Join End"

echo "RHJoin Start"
time hadoop jar $HADOOP/RHJoin-Test.jar org.ict.totemtang.rhjoin.RHJoinSkew $RHJOIN/LTable-${L}M-${R}M $RHJOIN/RTable-${R}M-${reduceNum} $OUTPUT ${reduceNum}
sleep 10s
hadoop fs -rmr $OUTPUT
echo "RHJoin End"
echo ""

echo "Broadcast Join Start"
time hadoop jar $HADOOP/RHJoin-Test.jar org.ict.totemtang.broadcastjoin.BroadcastJoinSkew $RHJOIN/RTable-${R}M $RHJOIN/LTable-${L}M-${R}M $OUTPUT $HADOOP
sleep 10s
hadoop fs -rmr $OUTPUT
echo " Broadcast Join End"

