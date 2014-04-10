package org.ict.totemtang.utils;

public class PartitionUtil {
	public static int partition(int key, int numPartitions){
		return (key & Integer.MAX_VALUE) % numPartitions;
	}
}
