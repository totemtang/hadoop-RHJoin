package org.ict.totemtang.broadcastjoin;

import java.io.FileInputStream;

import java.io.BufferedWriter;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.FileWriter;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.util.Iterator;
import java.util.List;

import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.conf.Configured;
import org.apache.hadoop.filecache.DistributedCache;
import org.apache.hadoop.fs.FSDataInputStream;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.mapred.FileInputFormat;
import org.apache.hadoop.mapred.FileOutputFormat;
import org.apache.hadoop.mapred.JobClient;
import org.apache.hadoop.mapred.JobConf;
import org.apache.hadoop.mapred.Mapper;
import org.apache.hadoop.mapred.OutputCollector;
import org.apache.hadoop.mapred.Reporter;
import org.apache.hadoop.util.LineReader;
import org.apache.hadoop.util.Tool;
import org.apache.hadoop.util.ToolRunner;
import org.ict.totemtang.rhjoin.RHJoin;
import org.ict.totemtang.rhjoin.RHJoin.CustomerReducer;
import org.ict.totemtang.rhjoin.RHJoin.KeyPartitioner;
import org.ict.totemtang.rhjoin.RHJoin.OrdersMapper;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.hadoop.io.NullWritable;
import org.apache.hadoop.io.Text;
import org.apache.hadoop.io.IntWritable;

public class BroadcastJoinSkew extends Configured implements Tool {
	
	private static String fileName = "smallTable.tmp";
	private String hdfsDir = "/tmp/mapjoin";
	
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
	
	
	private void uploadToHDFS(JobConf job, String localDir, String hdfsDir)
			throws FileNotFoundException, IOException{
		//upload hash table to HDFS
		Path localPath = new Path(localDir + "/" + fileName);
		Path hdfsPath = new Path(hdfsDir + "/" + fileName);
		FileSystem hdfs = FileSystem.get(job);
		if(hdfs.exists(hdfsPath)){
			hdfs.delete(hdfsPath, false);
		}
		
		hdfs.setReplication(hdfsPath, (short) 10);
		hdfs.copyFromLocalFile(localPath, hdfsPath);
		
		//make hash table as distributed cache
		DistributedCache.createSymlink(job);
		DistributedCache.addCacheArchive(hdfsPath.toUri(), job);
	}
	
	
	public static class JoinMapperSkew implements
			Mapper<Object, Text, NullWritable, Text>{
		
		private static final Log LOG = LogFactory.getLog(JoinMapperSkew.class.getName());
		private HashTableSkew ht = null;
		private JobConf job;

		@Override
		public void configure(JobConf job) {
			if(ht == null){
				FileSystem localFs;
				Path[] localFiles;
				Path htPath = null;
				try {
					localFs = FileSystem.getLocal(job);
					localFiles = DistributedCache.getLocalCacheArchives(job);
					for(Path localFile : localFiles){
						if(localFile.getName().equalsIgnoreCase(fileName)){
							htPath = localFile;
							break;
						}
					}
					
					if(htPath != null){
						LOG.info(htPath.toString());
						ht = new HashTableSkew(job, htPath.toString() + "/" + fileName);
					}
				} catch (IOException e) {
					// TODO Auto-generated catch block
					e.printStackTrace();
					throw new RuntimeException(e);
				}
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
			int index1;
			int index2;
			Integer probeKey;
			String probeVal;
			if(!ht.isShift()){//hash table is from Rtable, read LTable's format
				index1 = valString.indexOf("|", 0);
				index2 = valString.indexOf("|", index1 + 1);
				probeKey = Integer.parseInt(valString.substring(index1 + 1, index2));
				index1 = index2;
				index2 = getIndex(valString, '|', 4);
				probeVal = valString.substring(index1 + 1, index2);
				if(ht.containsKey(probeKey)){
					String val = ht.getFromR(probeKey);
					output.collect(NullWritable.get(), new Text(probeKey.toString() + "|" + val + "|" + probeVal));
				}
			}else{// hash table is from LTable, read RTable's format
				boolean isFirst = true;
				index1 = valString.indexOf("|", 0);
				probeKey = Integer.parseInt(valString.substring(0, index1));
				index2 = getIndex(valString, '|', 3);
				probeVal = valString.substring(index1 + 1, index2);
				if(ht.containsKey(probeKey)){
					List<String> valList = ht.getFromL(probeKey);
					Iterator<String> valIter = valList.iterator();
					StringBuilder sb = new StringBuilder();
					String val;
					while(valIter.hasNext()){
						val = valIter.next();
						if(isFirst){
							sb.append(probeKey.toString() + "|" + probeVal + "|" + val);
							isFirst = false;
						}else{
							sb.append("\n" + probeKey.toString() + "|" + probeVal + "|" + val);
						}
					}
					output.collect(NullWritable.get(), new Text(sb.toString()));
				}
			}
		}
	}
	
	
	
	public static void main(String args[]) throws Exception{
		int res = ToolRunner.run(new Configuration(), new BroadcastJoinSkew(), args);
		System.exit(res);
	}
	
	private void clearCache(JobConf job, String localDir) throws IOException{
		FileSystem hdfs = FileSystem.get(job);
		FileSystem localFs = FileSystem.getLocal(job);
		Path localPath = new Path(localDir + "/" + fileName);
		Path hdfsPath = new Path(hdfsDir + "/" + fileName);
		
		if(localFs.exists(localPath))
			localFs.delete(localPath, false);
		
		DistributedCache.purgeCache(job);
		
		if(hdfs.exists(hdfsPath))
			hdfs.delete(hdfsPath, false);
	}
	
	public void createLocalFileFromHDFS(JobConf job, String inputFile, String outputFile, boolean shift)
			throws IOException{
		Path inputPath = new Path(inputFile);
		FileSystem hdfs = FileSystem.get(job);
		FSDataInputStream fsStream = hdfs.open(inputPath);
		LineReader lr = new LineReader(fsStream, 4096);
		BufferedWriter out = new BufferedWriter(new FileWriter(outputFile));
		Text str = new Text();
		int key;
		String val = null;
		if(!shift){//make R as hash table
			out.write("false");
			String tmp;
			int index1;
			int index2;
			while(lr.readLine(str) > 0){
				tmp = str.toString();
				index1 = tmp.indexOf("|", 0);
				key = Integer.parseInt(tmp.substring(0, index1));
				index2 = getIndex(tmp, '|', 3);
				val = tmp.substring(index1 + 1, index2);
				out.write("\n" + key + "|" + val);
			}
		}else{//make LTable as hash table
			out.write("true");
			int index1;
			int index2;
			String tmp;
			while(lr.readLine(str) > 0){
				tmp = str.toString();
				index1 = tmp.indexOf("|", 0);
				index2 = tmp.indexOf("|", index1 + 1);
				key = Integer.parseInt(tmp.substring(index1 + 1, index2));
				index1 = index2;
				index2 = getIndex(tmp, '|', 4);
				val = tmp.substring(index1 + 1, index2);
				out.write("\n" + key + "|" + val);
			}
		}
		out.close();
		fsStream.close();
	}

	@Override
	public int run(String[] args) throws Exception {
		if(args.length != 4){
			System.err.println("Usage: <RTable> <LTable> <output dir> <localDir>");
			System.exit(2);
		}
		JobConf job = new JobConf(getConf(), BroadcastJoinSkew.class);
		job.setJobName("BroadcastJoinSkew");
		Path inputPath = new Path(args[1]);
		Path outputPath = new Path(args[2]);
		String localDir = args[3];
		
		//make and upload hash table
		createLocalFileFromHDFS(job, args[0], localDir + "/" + fileName, false);
		uploadToHDFS(job, localDir, hdfsDir);
		
		
		//launch map join job
		job.setJarByClass(BroadcastJoinSkew.class);
		job.setMapperClass(JoinMapperSkew.class);
		job.setOutputKeyClass(NullWritable.class);
		job.setOutputValueClass(Text.class);
		job.setNumReduceTasks(0);
		FileInputFormat.addInputPath(job, inputPath);
		FileOutputFormat.setOutputPath(job, outputPath);
		JobClient.runJob(job);
		clearCache(job, localDir);
		return 0;
	}
}
