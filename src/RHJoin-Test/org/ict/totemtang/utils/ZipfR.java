package org.ict.totemtang.utils;

import java.io.PrintWriter;
import java.io.FileWriter;
import java.io.IOException;

public class ZipfR{
    private final static long M=1000000;
    private final static long K=1000;
    private final static long L=100;
    public static void main(String[] args){
        if(args.length != 3){
            System.out.println("Usage: <skewfactor> <rNum> <outputDir>");
            return;
        }
        double s = Double.parseDouble(args[0]);
        long rNum = Long.parseLong(args[1])*M/L;
        String rTable = "RTable-"+rNum*L/M+"M";
        String output = args[2];
        PrintWriter outR;
        try{
            outR = new PrintWriter(new FileWriter(output + "/" + rTable));
        }catch(IOException ioe){
            ioe.printStackTrace();
            return;
        }
        String appendingR = "defdefdefl|defdefdefldefg|defdefdefldefdefdefldefg|defdefdefldefdefdefldefg|defdefdefldefdefdefldefg";
        long i, j;
        //Generating Data
        for(i = 1; i<= rNum; i++){
        	if(i == 1)
        		outR.write(i+"|"+appendingR);
        	else
        		outR.write("\n"+i+"|"+appendingR);
          }
         outR.close();
    }
}