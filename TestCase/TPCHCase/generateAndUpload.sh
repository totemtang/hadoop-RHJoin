#!/bin/bash

#We assume that table *customer* and *orders* have been generated
#Table *customer* is located at $TPCHHOME/data/customer/customer
#Table *orders* is located at $TPCHHOME/data/orders/orders

TPCHHOME=$(cd "$(dirname "$0")"; pwd)
DATAHOME=$TPCHHOME/data
LDATA=$DATAHOME/orders
RDATA=$DATAHOME/customer
CLASSPATH=$TPCHHOME/../../RHJoin-Test.jar
#The following parameters can be tuned
partNum=200
reduceNum=38

#Generating Data
echo "Partitioning Customer for partition number ${partNum}"
mkdir $RDATA/customer_${partNum}
java -cp $CLASSPATH org.ict.totemtang.utils.Partitioner $RDATA/customer $RDATA/customer_${partNum} ${partNum} customer

echo "Partitioning Customer for reducer number ${reduceNum}"
mkdir $RDATA/customer_${reduceNum}
java -cp $CLASSPATH org.ict.totemtang.utils.Partitioner $RDATA/customer $RDATA/customer_${reduceNum} ${reduceNum} customer

echo "Partitioning Orders for partition number ${partNum}"
mkdir $LDATA/orders_${partNum}
java -cp $CLASSPATH org.ict.totemtang.utils.Partitioner $LDATA/orders $LDATA/orders_${partNum} ${partNum} orders

#Upload Data
HADOOP=$TPCHHOME/../..
RHJOIN=/RHJOIN
$HADOOP/bin/hadoop -put $LDATA/* $RDATA/* $RHJOIN/
