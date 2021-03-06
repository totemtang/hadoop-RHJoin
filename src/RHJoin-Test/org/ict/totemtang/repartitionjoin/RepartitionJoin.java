package org.ict.totemtang.repartitionjoin;

import java.io.IOException;
import java.util.Iterator;

import org.apache.hadoop.conf.Configured;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.io.ByteWritable;
import org.apache.hadoop.io.IntWritable;
import org.apache.hadoop.io.NullWritable;
import org.apache.hadoop.io.Text;
import org.apache.hadoop.io.WritableComparable;
import org.apache.hadoop.io.WritableComparator;
import org.apache.hadoop.mapred.FileOutputFormat;
import org.apache.hadoop.mapred.JobClient;
import org.apache.hadoop.mapred.JobConf;
import org.apache.hadoop.mapred.Mapper;
import org.apache.hadoop.mapred.OutputCollector;
import org.apache.hadoop.mapred.Partitioner;
import org.apache.hadoop.mapred.Reducer;
import org.apache.hadoop.mapred.Reporter;
import org.apache.hadoop.mapred.TextInputFormat;
import org.apache.hadoop.mapred.lib.MultipleInputs;
import org.apache.hadoop.util.Tool;
import org.apache.hadoop.util.ToolRunner;
import org.ict.totemtang.utils.PartitionUtil;

public class RepartitionJoin extends Configured implements Tool {
	
	
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
	
	//Handle Customer Table With Tag 0
	static class CustomerMapper implements Mapper<Object, Text, TaggedKey, TaggedValue>{
		
		static byte CTag = 0;

		@Override
		public void configure(JobConf job) {
			// TODO Auto-generated method stub
			
		}

		@Override
		public void close() throws IOException {
			// TODO Auto-generated method stub
			
		}

		@Override
		public void map(Object key, Text value,
				OutputCollector<TaggedKey, TaggedValue> output, Reporter reporter)
				throws IOException {
			// TODO Auto-generated method stub
			String valString = value.toString();
			int indexStart = getIndex(valString, '|', 1);
			IntWritable outJoinKey = new IntWritable(Integer.parseInt(valString.substring(0, indexStart)));
			ByteWritable outTag = new ByteWritable(CTag);
			TaggedKey outKey = new TaggedKey(outJoinKey, outTag);
			
			int indexEnd = getIndex(valString, '|', 3);
			Text outVal = new Text(valString.substring(indexStart + 1, indexEnd));
			ByteWritable tag = new ByteWritable(CTag);
			output.collect(outKey, new TaggedValue(outVal, tag));
			
		}
	}
	
	//Handle Orders Table With Tag 1
	static class OrdersMapper implements Mapper<Object, Text, TaggedKey, TaggedValue>{
		
		static byte OTag = 1;

		@Override
		public void configure(JobConf job) {
			// TODO Auto-generated method stub
			
		}

		@Override
		public void close() throws IOException {
			// TODO Auto-generated method stub
			
		}

		@Override
		public void map(Object key, Text value,
				OutputCollector<TaggedKey, TaggedValue> output, Reporter reporter)
				throws IOException {
			// TODO Auto-generated method stub
			
			String valString = value.toString();
			int index1 = valString.indexOf("|", 0);
			int index2 = valString.indexOf("|", index1 + 1);
			IntWritable outJoinKey = new IntWritable(Integer.parseInt(valString.substring(index1 + 1, index2)));
			ByteWritable outTag = new ByteWritable(OTag);
			TaggedKey outKey = new TaggedKey(outJoinKey, outTag);
			
			index1 = index2;
			index2 = getIndex(valString, '|', 5);
			ByteWritable tag = new ByteWritable(OTag);
			Text outVal = new Text(valString.substring(index1 + 1, index2));
			output.collect(outKey, new TaggedValue(outVal, tag));
		}
	}
	
	//Handle Real Join
	static class JoinReducer implements
		Reducer<TaggedKey, TaggedValue, NullWritable, Text>{
		
		private static byte CTag = 0;

		@Override
		public void configure(JobConf job) {
			// TODO Auto-generated method stub
			
		}

		@Override
		public void close() throws IOException {
			// TODO Auto-generated method stub
			
		}

		@Override
		public void reduce(TaggedKey key, Iterator<TaggedValue> values,
				OutputCollector<NullWritable, Text> output, Reporter reporter)
				throws IOException {
			// TODO Auto-generated method stub
			Text customer = null;
			TaggedValue tt = null;
			if(values.hasNext()){
				tt = values.next();
				if(tt.getTag().get() != CTag)
					return;
				customer = new Text(tt.getText());
			}
			else
				return;
			while(values.hasNext()){
				tt = values.next();
				output.collect(NullWritable.get(),
						new Text(key.getJoinKey().toString() + "|" + customer.toString() + "|" + tt.getText().toString()));
			}
		}
		
	}
	
	//Partition
	public static class KeyPartitioner implements Partitioner<TaggedKey, TaggedValue>{

		@Override
		public void configure(JobConf job) {
			// TODO Auto-generated method stub
			
		}

		@Override
		public int getPartition(TaggedKey key, TaggedValue value, int numPartitions) {
			// TODO Auto-generated method stub
			//return (key.getJoinKey().hashCode() & Integer.MAX_VALUE) % numPartitions;
			return PartitionUtil.partition(key.getJoinKey().get(), numPartitions);
		}
		
	}
	
	//Make records with the same customer key in one group
	public static class GroupComparator extends WritableComparator{
		protected GroupComparator(){
			super(TaggedKey.class, true);
		}
		
		@Override
		public int compare(WritableComparable w1, WritableComparable w2) {
			TaggedKey ip1 = (TaggedKey)w1;
			TaggedKey ip2 = (TaggedKey)w2;
			return ip1.getJoinKey().compareTo(ip2.getJoinKey());
		}
	}
	
	//Make comparison between two keys 
	public static class KeyComparator extends WritableComparator {
		protected KeyComparator() {
			super(TaggedKey.class, true);
		}
		@Override
		public int compare(WritableComparable w1, WritableComparable w2){
			TaggedKey ip1 = (TaggedKey)w1;
			TaggedKey ip2 = (TaggedKey)w2;
			return ip1.compareTo(ip2);
		}
	}

	@Override
	public int run(String[] args) throws Exception {
		// TODO Auto-generated method stub
		if(args.length != 4){
			System.out.println("Usage: <customer> <orders> <output> <reduceNum>");
			return -1;
		}
		JobConf  conf = new JobConf(getConf(), getClass());
		conf.setJobName("RepartitionJoin");
		
		Path cPath = new Path(args[0]);
		Path oPath = new Path(args[1]);
		Path outputPath = new Path(args[2]);
		
		conf.setNumReduceTasks(Integer.parseInt(args[3]));
		
		MultipleInputs.addInputPath(conf, cPath, TextInputFormat.class, CustomerMapper.class);
		MultipleInputs.addInputPath(conf, oPath, TextInputFormat.class, OrdersMapper.class);
		
		FileOutputFormat.setOutputPath(conf, outputPath);
		
		conf.setPartitionerClass(KeyPartitioner.class);
		conf.setOutputKeyComparatorClass(KeyComparator.class);
		conf.setOutputValueGroupingComparator(GroupComparator.class);
		
		conf.setOutputKeyClass(TaggedKey.class);
		conf.setOutputValueClass(TaggedValue.class);
		
		conf.setReducerClass(JoinReducer.class);
		conf.setJarByClass(getClass());
		
		JobClient.runJob(conf);
		return 0;
	}
	
	public static void main(String[] args) throws Exception {
		int exitCode = ToolRunner.run(new RepartitionJoin(), args);
		System.exit(exitCode);
	}
	
}
