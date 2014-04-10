package org.ict.totemtang.utils;
import java.io.BufferedReader;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;


public class PartitionerSkew {
    public static void main(String[] args){
        if(args.length !=4){
            System.out.println("Usage: Partitioner <inputFile> <outputDir> <partCount> <L/R>");
            return;
        }
        String inputFile = args[0];
        String outputDir = args[1];
        int partCount = Integer.parseInt(args[2]);
        boolean isL = args[3].compareTo("L")==0 ? true : false;
        PrintWriter[] out = new PrintWriter[partCount];
        boolean[] isFirst = new boolean[partCount];
        BufferedReader in;
        String inputLine = null;
        try{
            in = new BufferedReader(new FileReader(inputFile), 4096);
            for(int i = 0; i < out.length; i++){
                isFirst[i] = true;
                out[i] = new PrintWriter(new FileWriter(outputDir + "/" + i));
            }
            int index, key, part;
            while((inputLine = in.readLine()) != null){
                if(isL){
                    index = inputLine.indexOf("|");
                    int index1 = index;
                    index = inputLine.indexOf("|", index1 + 1);
                    key = Integer.parseInt(inputLine.substring(index1 + 1, index));
                }else{
                    index = inputLine.indexOf("|");
                    key = Integer.parseInt(inputLine.substring(0, index));
                }
                part = (key & Integer.MAX_VALUE) % partCount;
                if(isFirst[part]){
                    isFirst[part] = false;
                    out[part].write(inputLine);
                }else
                    out[part].write("\n" + inputLine);
            }
            in.close();
            for(PrintWriter pw : out)
                pw.close();
        }catch(IOException e){
            e.printStackTrace();
        }
    }
}