package org.ict.totemtang.repartitionjoin;

import java.io.DataInput;
import java.io.DataOutput;
import java.io.IOException;

import org.apache.hadoop.io.ByteWritable;
import org.apache.hadoop.io.IntWritable;
import org.apache.hadoop.io.WritableComparable;

public class TaggedKey implements WritableComparable<TaggedKey>{
	private IntWritable joinKey;
	private ByteWritable tag;
	
	public TaggedKey(){
		this.joinKey = new IntWritable();
		this.tag = new ByteWritable();
	}
	
	public TaggedKey(int joinKey, byte tag){
		this.joinKey = new IntWritable(joinKey);
		this.tag = new ByteWritable(tag);
	}
	
	public TaggedKey(IntWritable joinKey, ByteWritable tag){
		this.joinKey = joinKey;
		this.tag = tag;
	}
	
	public IntWritable getJoinKey(){
		return joinKey;
	}
	
	public ByteWritable getTag(){
		return tag;
	}
	

	@Override
	public void write(DataOutput out) throws IOException {
		// TODO Auto-generated method stub
		joinKey.write(out);
		tag.write(out);
	}

	@Override
	public void readFields(DataInput in) throws IOException {
		// TODO Auto-generated method stub
		joinKey.readFields(in);
		tag.readFields(in);
	}

	@Override
	public int compareTo(TaggedKey arg0) {
		// TODO Auto-generated method stub
		int cmp = joinKey.compareTo(arg0.joinKey);
		if(cmp !=0){
			return cmp;
		}
		return tag.compareTo(arg0.tag);
	}
	
	@Override
	public int hashCode(){
		return joinKey.hashCode() * 163 + tag.hashCode();
	}
	
	@Override
	public boolean equals(Object o){
		if(o instanceof TaggedKey){
			TaggedKey ip = (TaggedKey) o;
			return joinKey.equals(ip.joinKey) && tag.equals(ip.tag);
		}
		return false;
	}
	
	@Override
	public String toString(){
		return joinKey + "\t" + tag;
	}
}
