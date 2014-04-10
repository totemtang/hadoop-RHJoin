package org.ict.totemtang.utils;

import java.io.BufferedReader;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;


public class Partitioner {
	public static void main(String args[]){
		if(args.length != 4){
			System.out.println("Usage : <inputFile> <outputDir> <partCount> <Customer/Order>");
			return ;
		}
		String inputFile = args[0];
		String outputDir = args[1];
		int partCount = Integer.parseInt(args[2]);
		PrintWriter out[] = new PrintWriter[partCount];
		boolean isFirst[] = new boolean[partCount];
		BufferedReader in;
		String inputLine = null;
		boolean isCust = args[3].compareToIgnoreCase("Customer") == 0 ? true : false;
		try{
			in = new BufferedReader(new FileReader(inputFile), 4096);
			for(int i = 0; i < out.length; i++){
				isFirst[i] = true;
				out[i] = new PrintWriter(new FileWriter(outputDir + "/" + i));
			}
			int index, index1, index2, key, part;
			while((inputLine = in.readLine()) != null){
				if(isCust){
					index = inputLine.indexOf("|");
					key = Integer.parseInt(inputLine.substring(0, index));
					part = PartitionUtil.partition(key, partCount);
				}else{
					index1 = inputLine.indexOf("|", 0);
					index2 = inputLine.indexOf("|", index1 + 1);
					key = Integer.parseInt(inputLine.substring(index1 + 1, index2));
					part = PartitionUtil.partition(key, partCount);
				}
				if(isFirst[part]){
					isFirst[part] = false;
					out[part].write(inputLine);
				}else{
					out[part].write("\n" + inputLine);
				}
			}
			in.close();
			for(PrintWriter pw : out)
				pw.close();
		}catch(IOException e){
			e.printStackTrace();
		}
	}
}
