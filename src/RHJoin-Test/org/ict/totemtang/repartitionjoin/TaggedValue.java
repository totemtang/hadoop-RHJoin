package org.ict.totemtang.repartitionjoin;

import java.io.DataInput;
import java.io.DataOutput;
import java.io.IOException;

import org.apache.hadoop.io.IntWritable;
import org.apache.hadoop.io.Text;
import org.apache.hadoop.io.Writable;
import org.apache.hadoop.io.ByteWritable;

public class TaggedValue implements Writable {
	private Text text;
	private ByteWritable tag;
	private static byte CTag = 0;
	private static byte OTag = 1;
	
	public TaggedValue(){
		text = new Text();
		tag = new ByteWritable();
	}
	
	public TaggedValue(Text text, ByteWritable tag){
		this.tag = tag;
		this.text = text;
	}
	
	public TaggedValue(String text, byte tag){
		this.tag = new ByteWritable(tag);
		this.text = new Text(text);
	}
	
	public ByteWritable getTag(){
		return tag;
	}
	
	public Text getText(){
		return text;
	}
	
	
	@Override
	public void write(DataOutput out) throws IOException {
		tag.write(out);
		text.write(out);
	}
	
	@Override
	public void readFields(DataInput in) throws IOException {
		tag.readFields(in);
		text.readFields(in);
	}
	
	@Override
	public int hashCode(){
		return tag.hashCode() * 163 + text.hashCode();
	}
	
	@Override
	public String toString(){
		return tag + "|" + text;
	}
}
