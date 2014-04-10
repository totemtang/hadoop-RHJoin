#!/bin/bash
SKEWHOME=$(cd "$(dirname "$0")"; pwd)
DATAHOME=$SKEWHOME/data
LDATA=$DATAHOME/LData
RDATA=$DATAHOME/RData
CLASSPATH=$SKEWHOME/../../RHJoin-Test.jar
mkdir $DATAHOME $LDATA $RDATA
#The following parameters can be tuned
L=5000 #5GB of L
R=100  #100M of R
partNum=200
reduceNum=38
skewfactor=0.5

#Generating Data
echo $CLASSPATH
echo "Generating R${R}"
java -cp $CLASSPATH org.ict.totemtang.utils.ZipfR 0.5 ${R} $RDATA

echo "Partitioning Data for partition number ${partNum}"
mkdir $RDATA/RTable-${R}M-${partNum}
java -cp $CLASSPATH org.ict.totemtang.utils.PartitionerSkew $RDATA/RTable-${R}M $RDATA/RTable-${R}M-${partNum} ${partNum} R

echo "Partitioning Data for reducer number ${reduceNum}"
mkdir $RDATA/RTable-${R}M-${reduceNum}
java -cp $CLASSPATH org.ict.totemtang.utils.PartitionerSkew $RDATA/RTable-${R}M $RDATA/RTable-${R}M-${reduceNum} ${reduceNum} R 

echo "Generating L${L}"
java -cp $CLASSPATH org.ict.totemtang.utils.ZipfL 0.5 ${R} ${L} $LDATA

echo "Partitioning Data for Directed Join with partition number ${partNum}"
mkdir $LDATA/LTable-${L}M-${R}M-${partNum}
java -cp $CLASSPATH org.ict.totemtang.utils.PartitionerSkew $LDATA/LTable-${L}M-${R}M $LDATA/LTable-${L}M-${R}M-${partNum} ${partNum} L

#Upload Data
HADOOP=$SKEWHOME/../..
RHJOIN=/RHJOIN
$HADOOP/bin/hadoop -put $LDATA $RDATA $RHJOIN/
