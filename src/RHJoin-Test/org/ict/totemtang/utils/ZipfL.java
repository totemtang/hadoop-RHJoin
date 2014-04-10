package org.ict.totemtang.utils;

import java.io.PrintWriter;
import java.io.FileWriter;
import java.io.IOException;

public class ZipfL{
    private final static long M=1000000;
    private final static long K=1000;
    private final static long L=100;
    public static void main(String[] args){
        if(args.length != 4){
            System.out.println("Usage: <skewfactor> <rNum> <lNum> <outputDir>");
            return;
        }
        double s = Double.parseDouble(args[0]);
        long rNum = Long.parseLong(args[1])*M/L;
        long lNum = Long.parseLong(args[2])*M/L;
        String lTable = "LTable-"+lNum*L/M+"M"+"-"+rNum*L/M+"M";
        String output = args[3];
        PrintWriter outL;
        try{
            outL = new PrintWriter(new FileWriter(output + "/" + lTable));
        }catch(IOException ioe){
            ioe.printStackTrace();
            return;
        }
        String appendingL = "abcabcabcl|abcabcabclabcd|abcabcabclabcabcabclabcd|abcabcabclabcabcabclabcd|abcabcabclabcabcab";
        //Calculate denominator
        double d = 0.0;
        long i;
        for(i = 1; i <= rNum; i++){
            d += 1/Math.pow(i, s);
        }
        System.out.println("Denominator: "+d);
        double f = 0.0;
        long j, tmpNum, k = 0, tmplNum = lNum;
        //Generating Data
        for(i = 1; i<= rNum; i++){
        	f = 1/(Math.pow(i, s)*d);
        	//System.out.println("Frequency: "+f);
        	tmpNum = (long) (f*lNum);
        	for(j = 0; j < tmpNum; j++){
        		if(i== 1 && j == 0){
        			outL.write(k+"|"+i+"|"+appendingL);
        		}else
        			outL.write("\n"+k+"|"+i+"|"+appendingL);
        		k++;
        	}
        }
       outL.close();
    }
}