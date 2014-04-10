package org.ict.totemtang.rhjoin;

import java.io.IOException;
import java.net.URI;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.StringTokenizer;

import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.conf.Configured;
import org.apache.hadoop.fs.FSDataInputStream;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.io.IntWritable;
import org.apache.hadoop.io.NullWritable;
import org.apache.hadoop.io.Text;
import org.apache.hadoop.mapred.FileInputFormat;
import org.apache.hadoop.mapred.FileOutputFormat;
import org.apache.hadoop.mapred.JobClient;
import org.apache.hadoop.mapred.JobConf;
import org.apache.hadoop.mapred.Mapper;
import org.apache.hadoop.mapred.OutputCollector;
import org.apache.hadoop.mapred.Partitioner;
import org.apache.hadoop.mapred.Reducer;
import org.apache.hadoop.mapred.Reporter;
import org.apache.hadoop.mapred.TextInputFormat;
import org.apache.hadoop.util.GenericOptionsParser;
import org.apache.hadoop.util.LineReader;
import org.apache.hadoop.util.Tool;
import org.apache.hadoop.util.ToolRunner;
import org.ict.totemtang.utils.PartitionUtil;

import sun.org.mozilla.javascript.Context;

public class RHJoin extends Configured implements Tool {
	
	private int reduceNum = -1;
	private boolean isNoSort = false;
	private String reduceInput = null;
	
	
	private static int getIndex(String str, int ch, int num){
		int i = 0;
		int indexstart = 0;
		int indexend = indexstart;
		while(i < num){
			indexend = str.indexOf(ch, indexstart);
			if(indexend == -1)
				break;
			indexstart = indexend + 1;
			i++;
		}
		return indexend;
	}
	
	public static class KeyPartitioner implements Partitioner<IntWritable, Text>{

		@Override
		public void configure(JobConf job) {
			// TODO Auto-generated method stub
			
		}

		@Override
		public int getPartition(IntWritable key, Text value, int numPartitions) {
			// TODO Auto-generated method stub
			//return (key.hashCode() & Integer.MAX_VALUE) % numPartitions;
			return PartitionUtil.partition(key.get(), numPartitions);
		}
	}
	
	public static class OrdersMapper implements
			Mapper<Object, Text, IntWritable, Text> {

		@Override
		public void configure(JobConf arg0) {
			// TODO Auto-generated method stub
		}

		@Override
		public void close() throws IOException {
			// TODO Auto-generated method stub

		}

		@Override
		public void map(Object key, Text value,
				OutputCollector<IntWritable, Text> context, Reporter arg3)
				throws IOException {
			String valString = value.toString();
			int index1 = valString.indexOf("|", 0);
			int index2 = valString.indexOf("|", index1 + 1);
			IntWritable outKey = new IntWritable(Integer.parseInt(valString.substring(index1 + 1, index2)));
			
			index1 = index2;
			index2 = getIndex(valString, '|', 5);
			Text outVal = new Text(valString.substring(index1 + 1, index2));
			context.collect(outKey,  outVal);
		}
	}

	public static class CustomerReducer implements
			Reducer<IntWritable, Text, NullWritable, Text> {
		

		private int partition = -1;
		private HashMap<IntWritable, Text> hashMap = null;
		
		private void createHashMap(int partition, JobConf conf) throws IOException{
			String uri = conf.get("mapred.reduce.input") + "/" + partition;
			hashMap = new HashMap<IntWritable, Text>();
			FileSystem fs = FileSystem.get(conf);
			FSDataInputStream fsStream = fs.open(new Path(uri));
			LineReader lr = new LineReader(fsStream, 4096);
			Text str = new Text();
			String tmp;
			int index1;
			int index2;
			IntWritable key = null;
			Text val = null;
			while(lr.readLine(str) > 0){
				tmp = str.toString();
				index1 = tmp.indexOf("|", 0);
				key = new IntWritable(Integer.parseInt(tmp.substring(0, index1)));
				index2 = getIndex(tmp, '|', 3);
				val = new Text(tmp.substring(index1 + 1, index2));
				hashMap.put(key, val);
				tmp = null;
			}
			fsStream.close();
		}
		
		@Override
		public void configure(JobConf conf) {
			// TODO Auto-generated method stub
			partition = conf.getInt("mapred.task.partition", -1);
			if(partition < 0)
				throw new RuntimeException("Partition Number Error");
			else
				try {
					createHashMap(partition, conf);
				} catch (IOException e) {
					// TODO Auto-generated catch block
					throw new RuntimeException(e);
				}
		}

		@Override
		public void close() throws IOException {
			// TODO Auto-generated method stub

		}

		@Override
		public void reduce(IntWritable key, Iterator<Text> values,
				OutputCollector<NullWritable, Text> context, Reporter arg3)
				throws IOException {
			// TODO Auto-generated method stub
			boolean isContain = hashMap.containsKey(key);
			Text outOrders;
			if(isContain){
				Text outCust = hashMap.get(key);
				while(values.hasNext()){
					outOrders = values.next();
					context.collect(NullWritable.get(), 
							new Text(key.toString() + "|" + outCust + "|" + outOrders));
				}
			}
		}
	}
	

	public static void main(String[] args) throws Exception {
		int res = ToolRunner.run(new Configuration(), new RHJoin(), args);
		System.exit(res);
	}

	@Override
	public int run(String[] args) throws Exception {
		// TODO Auto-generated method stub
		if (args.length != 4) {
			System.err.println("Usage: <inputPath> <reduceinput> <outputPath> <reduceNum>");
			System.exit(2);
		}
		reduceNum = Integer.parseInt(args[3]);
		JobConf job = new JobConf(getConf(), RHJoin.class);
		job.setJobName("RHJoin");
		job.setBoolean("mapred.map.nosort.on", true);
		reduceInput = args[1];
		job.set("mapred.reduce.input", reduceInput);
		job.setJarByClass(RHJoin.class);
		job.setMapperClass(OrdersMapper.class);
		job.setReducerClass(CustomerReducer.class);
		job.setOutputKeyClass(IntWritable.class);
		job.setOutputValueClass(Text.class);
		job.setNumReduceTasks(reduceNum);
		job.setPartitionerClass(KeyPartitioner.class);
		FileInputFormat.addInputPath(job, new Path(args[0]));
		FileOutputFormat.setOutputPath(job, new Path(args[2]));
		JobClient.runJob(job);
		return 0;
	}
}
