package org.ict.totemtang.directedjoin;

import java.io.IOException;
import java.util.HashMap;

import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.conf.Configured;
import org.apache.hadoop.fs.FSDataInputStream;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.io.IntWritable;
import org.apache.hadoop.io.Text;
import org.apache.hadoop.mapred.FileInputFormat;
import org.apache.hadoop.mapred.FileOutputFormat;
import org.apache.hadoop.mapred.JobClient;
import org.apache.hadoop.mapred.JobConf;
import org.apache.hadoop.mapred.Mapper;
import org.apache.hadoop.mapred.OutputCollector;
import org.apache.hadoop.mapred.Reporter;
import org.apache.hadoop.mapred.TextInputFormat;
import org.apache.hadoop.util.LineReader;
import org.apache.hadoop.util.Tool;
import org.apache.hadoop.util.ToolRunner;
import org.apache.hadoop.io.NullWritable;

public class DirectedJoin extends Configured implements Tool{
	
	public static class NonsplittableTextInputFormat extends TextInputFormat {
		@Override
		protected boolean isSplitable(FileSystem fs, Path filename){
			return false;
		}
	}
	
	static class DirectedMapper implements Mapper<Object, Text, NullWritable, Text>{
		
		private HashMap<IntWritable, Text> hashMap = null;
		
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
		
		private void createHashMap(String inputStr, JobConf conf) throws IOException{
			String uri = inputStr;
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
		public void configure(JobConf job) {
			// TODO Auto-generated method stub
			String fileStr = job.get("map.input.file", "");
			String inputPath = job.get("map.dfs.input", "");
			if(fileStr == "" || inputPath == "")
				throw new RuntimeException("map input file Error");
			String []splits = fileStr.split("/");
			String inputStr = inputPath + "/" + splits[splits.length - 1];
			try {
				createHashMap(inputStr, job);
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
		public void map(Object key, Text value,
				OutputCollector<NullWritable, Text> output, Reporter reporter)
				throws IOException {
			// TODO Auto-generated method stub
			String valString = value.toString();
			int index1 = valString.indexOf("|", 0);
			int index2 = valString.indexOf("|", index1 + 1);
			IntWritable probeKey = new IntWritable(Integer.parseInt(valString.substring(index1 + 1, index2)));
			
			boolean isContain = hashMap.containsKey(probeKey);
			
			if(isContain){
				index1 = index2;
				index2 = getIndex(valString, '|', 5);
				Text outOrders = new Text(valString.substring(index1 + 1, index2));
				
				Text outCust = hashMap.get(probeKey);
				output.collect(NullWritable.get(), 
							new Text(probeKey.toString() + "|" + outCust + "|" + outOrders));
			}
		}
	}

	@Override
	public int run(String[] args) throws Exception {
		// TODO Auto-generated method stub
		if(args.length != 3){
			System.err.println("Usage: <customer path> <orders path> <output path>");
			System.exit(2);
		}
		JobConf job = new JobConf(getConf(), DirectedJoin.class);
		job.setJobName("DirectedJoin");
		Path inputPath = new Path(args[1]);
		Path outputPath = new Path(args[2]);
		job.set("map.dfs.input", args[0]);
		
		//launch map join job
		job.setJarByClass(DirectedJoin.class);
		job.setMapperClass(DirectedMapper.class);
		job.setOutputKeyClass(NullWritable.class);
		job.setOutputValueClass(Text.class);
		job.setNumReduceTasks(0);
		job.setInputFormat(NonsplittableTextInputFormat.class);
		FileInputFormat.addInputPath(job, inputPath);
		FileOutputFormat.setOutputPath(job, outputPath);
		JobClient.runJob(job);
		return 0;
	}
	
	public static void main(String args[]) throws Exception{
		int res = ToolRunner.run(new Configuration(), new DirectedJoin(), args);
		System.exit(res);
	}
}
