#!/bin/bash
TPCHHOME=$(cd "$(dirname "$0")"; pwd)
partNum=200
reduceNum=38
HADOOP=$TPCHHOME/../..
RHJOIN=/RHJOIN
OUTPUT=$RHJOIN/output

#We assume table *customer* is at $RHJOIN/customer,
#partitions of *customer* are at $RHJOIN/customer_${partNum} and $RHJOIN/customer_${reduceNum}
#table *orders* is at $RHJOIN/orders,
#and partitions of *orders* are at $RHJOIN/orders_${partNum}

echo "Directed Join Start"
time hadoop jar $HADOOP/RHJoin-Test.jar org.ict.totemtang.directedjoin.DirectedJoin $RHJOIN/customer_${partNum} $RHJOIN/orders_${partNum} $OUTPUT
sleep 10s
hadoop fs -rmr $OUTPUT
echo "Directed Join End"

echo "Repartition Join Start"
time hadoop jar $HADOOP/RHJoin-Test.jar org.ict.totemtang.repartitionjoin.RepartitionJoin $RHJOIN/customer $RHJOIN/orders $OUTPUT ${reduceNum}
sleep 10s
hadoop fs -rmr $OUTPUT
echo "Repartition Join End"

echo "RHJoin Start"
time hadoop jar $HADOOP/RHJoin-Test.jar org.ict.totemtang.rhjoin.RHJoin $RHJOIN/orders $RHJOIN/customer_${reduceNum} $OUTPUT ${reduceNum}
sleep 10s
hadoop fs -rmr $OUTPUT
echo "RHJoin End"
echo ""

echo "Broadcast Join Start"
time hadoop jar $HADOOP/RHJoin-Test.jar org.ict.totemtang.broadcastjoin.BroadcastJoin $RHJOIN/customer $RHJOIN/orders $OUTPUT $HADOOP
sleep 10s
hadoop fs -rmr $OUTPUT
echo " Broadcast Join End"

