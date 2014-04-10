package org.ict.totemtang.broadcastjoin;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.ObjectInputStream;
import java.io.Serializable;
import java.io.ObjectOutputStream;
import java.io.IOException;
import org.apache.hadoop.io.IntWritable;
import org.apache.hadoop.io.Text;
import org.apache.hadoop.mapred.JobConf;
import org.apache.hadoop.util.LineReader;
import org.apache.hadoop.fs.FSDataInputStream;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.Path;
import java.util.*;
import java.util.Map.Entry;
import java.io.DataOutput;

public final class HashTable{
	private Map<Integer, List<String>> ordersMap = null;
	private Map<Integer, String> custMap = null;
	private boolean shift;

	public HashTable(JobConf job, String input)throws IOException{
		BufferedReader in = new BufferedReader(new FileReader(input), 4096);
		String str;
		Integer key = null;
		String val = null;
		if((str = in.readLine()) != null){
			shift = str.toString().compareToIgnoreCase("true") == 0 ;
		}else{
			throw new IOException("File Format Error");
		}
		if(!shift){//make customer as hash table
			custMap = new HashMap<Integer, String>();
			String tmp;
			int index1;
			while((str = in.readLine()) != null){
				tmp = str;
				index1 = tmp.indexOf("|", 0);
				key = Integer.parseInt(tmp.substring(0, index1));
				val = tmp.substring(index1 + 1);
				custMap.put(key, val);
			}
		}else{//make orders as hash table
			ordersMap = new HashMap<Integer, List<String>>();
			int index1;
			String tmp;
			List<String> valList = null;
			while((str = in.readLine()) != null){
				tmp = str;
				index1 = tmp.indexOf("|", 0);
				key = Integer.parseInt(tmp.substring(0, index1));
				val = tmp.substring(index1 + 1);
				if(!ordersMap.containsKey(key)){
					valList = new LinkedList<String>();
					valList.add(val);
					ordersMap.put(key, valList);
				}else{
					valList = ordersMap.get(key);
					valList.add(val);
				}
			}
		}
		in.close();
	}
	
	public boolean isShift(){
		return shift;
	}
	
	public String getFromCust(Integer key){
		return custMap.get(key);
	}
	
	public List<String> getFromOrders(Integer key){
		return ordersMap.get(key);
	}
	
	public boolean containsKey(Integer key){
		if(!shift)
			return custMap.containsKey(key);
		else
			return ordersMap.containsKey(key);
	}
	
}
