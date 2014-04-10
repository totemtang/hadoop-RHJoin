RHJOIN
=================
This is the project of RHJoin.

RHJoin is a MapReduce-based join method for big log processing. Compared to other methods, RHJoin is more balanced and suitable for big log procesing. Specifically, RHJoin is designed to achieve linear scalability, efficient space utilization and high query speed. 

This package includes two parts:
(1) The optimized hadoop for RHJoin;
(2) Two test cases to evaluate RHJoin, Directed Join, Repartition Join and Broadcast Join;

====Optimized Hadoop===

We optimize hadoop by eliminating the sort operation in the shuffle and overlapping mappers and reducers. We modify the code of MapTask.java and ReduceTask.java to implement our optimizations. Users can set the option "mapred.nosort.on" as yes or no to activate or deactivate this feature. To compile this hadoop, users need use commands "ant jar". The generated hadoop jar is build/hadoop-0.20.3-dev-core.jar and hadoop-0.20.2-core.jar is a soft link to it.

====Test Case====

The codes of the two test cases can be found in src/RHJoin-Test. Users can directly use the bash files under TestCase to test the efficiency of RHJoin along with other three typical join methods.

