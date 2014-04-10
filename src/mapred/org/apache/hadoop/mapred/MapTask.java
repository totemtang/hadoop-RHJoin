/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.hadoop.mapred;

import static org.apache.hadoop.mapred.Task.Counter.COMBINE_INPUT_RECORDS;
import static org.apache.hadoop.mapred.Task.Counter.COMBINE_OUTPUT_RECORDS;
import static org.apache.hadoop.mapred.Task.Counter.MAP_INPUT_BYTES;
import static org.apache.hadoop.mapred.Task.Counter.MAP_INPUT_RECORDS;
import static org.apache.hadoop.mapred.Task.Counter.MAP_OUTPUT_BYTES;
import static org.apache.hadoop.mapred.Task.Counter.MAP_OUTPUT_RECORDS;

import java.io.DataInput;
import java.io.DataOutput;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.nio.ByteBuffer;
import java.nio.LongBuffer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;
import java.util.zip.CRC32;
import java.util.zip.CheckedInputStream;
import java.util.zip.CheckedOutputStream;
import java.util.zip.Checksum;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.ChecksumException;
import org.apache.hadoop.fs.FSDataInputStream;
import org.apache.hadoop.fs.FSDataOutputStream;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.LocalFileSystem;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.io.BytesWritable;
import org.apache.hadoop.io.DataInputBuffer;
import org.apache.hadoop.io.IOUtils;
import org.apache.hadoop.io.RawComparator;
import org.apache.hadoop.io.SequenceFile;
import org.apache.hadoop.io.Text;
import org.apache.hadoop.io.SequenceFile.CompressionType;
import org.apache.hadoop.io.compress.CompressionCodec;
import org.apache.hadoop.io.compress.DefaultCodec;
import org.apache.hadoop.io.serializer.Deserializer;
import org.apache.hadoop.io.serializer.SerializationFactory;
import org.apache.hadoop.io.serializer.Serializer;
import org.apache.hadoop.mapred.IFile.Writer;
import org.apache.hadoop.mapred.Merger.Segment;
import org.apache.hadoop.mapred.SortedRanges.SkipRangeIterator;
import org.apache.hadoop.mapreduce.TaskAttemptContext;
import org.apache.hadoop.util.IndexedSortable;
import org.apache.hadoop.util.IndexedSorter;
import org.apache.hadoop.util.Progress;
import org.apache.hadoop.util.QuickSort;
import org.apache.hadoop.util.ReflectionUtils;
import org.apache.hadoop.util.StringUtils;

/** A Map task. */
class MapTask extends Task {
  /**
   * The size of each record in the index file for the map-outputs.
   */
  public static final int MAP_OUTPUT_INDEX_RECORD_LENGTH = 24;
  

  private BytesWritable split = new BytesWritable();
  private String splitClass;
  private final static int APPROX_HEADER_LENGTH = 150;
  
  //Added By TotemTang
  boolean noSort = false;

  private static final Log LOG = LogFactory.getLog(MapTask.class.getName());

  {   // set phase for this task
    setPhase(TaskStatus.Phase.MAP); 
  }

  public MapTask() {
    super();
  }

  public MapTask(String jobFile, TaskAttemptID taskId, 
                 int partition, String splitClass, BytesWritable split
                 ) {
    super(jobFile, taskId, partition);
    this.splitClass = splitClass;
    this.split = split;
  }

  @Override
  public boolean isMapTask() {
    return true;
  }

  @Override
  public void localizeConfiguration(JobConf conf) throws IOException {
    super.localizeConfiguration(conf);
    if (isMapOrReduce()) {
      Path localSplit = new Path(new Path(getJobFile()).getParent(), 
                                 "split.dta");
      LOG.debug("Writing local split to " + localSplit);
      DataOutputStream out = FileSystem.getLocal(conf).create(localSplit);
      Text.writeString(out, splitClass);
      split.write(out);
      out.close();
    }
  }
  
  @Override
  public TaskRunner createRunner(TaskTracker tracker, 
      TaskTracker.TaskInProgress tip) {
    return new MapTaskRunner(tip, tracker, this.conf);
  }

  @Override
  public void write(DataOutput out) throws IOException {
    super.write(out);
    if (isMapOrReduce()) {
      Text.writeString(out, splitClass);
      split.write(out);
      split = null;
    }
  }
  
  @Override
  public void readFields(DataInput in) throws IOException {
    super.readFields(in);
    if (isMapOrReduce()) {
      splitClass = Text.readString(in);
      split.readFields(in);
    }
  }

  /**
   * This class wraps the user's record reader to update the counters and progress
   * as records are read.
   * @param <K>
   * @param <V>
   */
  class TrackedRecordReader<K, V> 
      implements RecordReader<K,V> {
    private RecordReader<K,V> rawIn;
    private Counters.Counter inputByteCounter;
    private Counters.Counter inputRecordCounter;
    private TaskReporter reporter;
    private long beforePos = -1;
    private long afterPos = -1;
    
    TrackedRecordReader(RecordReader<K,V> raw, TaskReporter reporter) 
      throws IOException{
      rawIn = raw;
      inputRecordCounter = reporter.getCounter(MAP_INPUT_RECORDS);
      inputByteCounter = reporter.getCounter(MAP_INPUT_BYTES);
      this.reporter = reporter;
    }

    public K createKey() {
      return rawIn.createKey();
    }
      
    public V createValue() {
      return rawIn.createValue();
    }
     
    public synchronized boolean next(K key, V value)
    throws IOException {
      boolean ret = moveToNext(key, value);
      if (ret) {
        incrCounters();
      }
      return ret;
    }
    
    protected void incrCounters() {
      inputRecordCounter.increment(1);
      inputByteCounter.increment(afterPos - beforePos);
    }
     
    protected synchronized boolean moveToNext(K key, V value)
      throws IOException {
      reporter.setProgress(getProgress());
      beforePos = getPos();
      boolean ret = rawIn.next(key, value);
      afterPos = getPos();
      return ret;
    }
    
    public long getPos() throws IOException { return rawIn.getPos(); }
    public void close() throws IOException { rawIn.close(); }
    public float getProgress() throws IOException {
      return rawIn.getProgress();
    }
    TaskReporter getTaskReporter() {
      return reporter;
    }
  }

  /**
   * This class skips the records based on the failed ranges from previous 
   * attempts.
   */
  class SkippingRecordReader<K, V> extends TrackedRecordReader<K,V> {
    private SkipRangeIterator skipIt;
    private SequenceFile.Writer skipWriter;
    private boolean toWriteSkipRecs;
    private TaskUmbilicalProtocol umbilical;
    private Counters.Counter skipRecCounter;
    private long recIndex = -1;
    
    SkippingRecordReader(RecordReader<K,V> raw, TaskUmbilicalProtocol umbilical,
                         TaskReporter reporter) throws IOException{
      super(raw, reporter);
      this.umbilical = umbilical;
      this.skipRecCounter = reporter.getCounter(Counter.MAP_SKIPPED_RECORDS);
      this.toWriteSkipRecs = toWriteSkipRecs() &&  
        SkipBadRecords.getSkipOutputPath(conf)!=null;
      skipIt = getSkipRanges().skipRangeIterator();
    }
    
    public synchronized boolean next(K key, V value)
    throws IOException {
      if(!skipIt.hasNext()) {
        LOG.warn("Further records got skipped.");
        return false;
      }
      boolean ret = moveToNext(key, value);
      long nextRecIndex = skipIt.next();
      long skip = 0;
      while(recIndex<nextRecIndex && ret) {
        if(toWriteSkipRecs) {
          writeSkippedRec(key, value);
        }
      	ret = moveToNext(key, value);
        skip++;
      }
      //close the skip writer once all the ranges are skipped
      if(skip>0 && skipIt.skippedAllRanges() && skipWriter!=null) {
        skipWriter.close();
      }
      skipRecCounter.increment(skip);
      reportNextRecordRange(umbilical, recIndex);
      if (ret) {
        incrCounters();
      }
      return ret;
    }
    
    protected synchronized boolean moveToNext(K key, V value)
    throws IOException {
	    recIndex++;
      return super.moveToNext(key, value);
    }
    
    @SuppressWarnings("unchecked")
    private void writeSkippedRec(K key, V value) throws IOException{
      if(skipWriter==null) {
        Path skipDir = SkipBadRecords.getSkipOutputPath(conf);
        Path skipFile = new Path(skipDir, getTaskID().toString());
        skipWriter = 
          SequenceFile.createWriter(
              skipFile.getFileSystem(conf), conf, skipFile,
              (Class<K>) createKey().getClass(),
              (Class<V>) createValue().getClass(), 
              CompressionType.BLOCK, getTaskReporter());
      }
      skipWriter.append(key, value);
    }
  }

  @Override
  public void run(final JobConf job, final TaskUmbilicalProtocol umbilical)
    throws IOException, ClassNotFoundException, InterruptedException {
    this.umbilical = umbilical;

    // start thread that will handle communication with parent
    TaskReporter reporter = new TaskReporter(getProgress(), umbilical);
    reporter.startCommunicationThread();
    boolean useNewApi = job.getUseNewMapper();
    initialize(job, getJobID(), reporter, useNewApi);

    // check if it is a cleanupJobTask
    if (jobCleanup) {
      runJobCleanupTask(umbilical, reporter);
      return;
    }
    if (jobSetup) {
      runJobSetupTask(umbilical, reporter);
      return;
    }
    if (taskCleanup) {
      runTaskCleanupTask(umbilical, reporter);
      return;
    }

    if (useNewApi) {
      runNewMapper(job, split, umbilical, reporter);
    } else {
      runOldMapper(job, split, umbilical, reporter);
    }
    done(umbilical, reporter);
  }

  @SuppressWarnings("unchecked")
  private <INKEY,INVALUE,OUTKEY,OUTVALUE>
  void runOldMapper(final JobConf job,
                    final BytesWritable rawSplit,
                    final TaskUmbilicalProtocol umbilical,
                    TaskReporter reporter
                    ) throws IOException, InterruptedException,
                             ClassNotFoundException {
    InputSplit inputSplit = null;
    // reinstantiate the split
    try {
      inputSplit = (InputSplit) 
        ReflectionUtils.newInstance(job.getClassByName(splitClass), job);
    } catch (ClassNotFoundException exp) {
      IOException wrap = new IOException("Split class " + splitClass + 
                                         " not found");
      wrap.initCause(exp);
      throw wrap;
    }
    DataInputBuffer splitBuffer = new DataInputBuffer();
    splitBuffer.reset(split.getBytes(), 0, split.getLength());
    inputSplit.readFields(splitBuffer);
    
    updateJobWithSplit(job, inputSplit);
    reporter.setInputSplit(inputSplit);

    RecordReader<INKEY,INVALUE> rawIn =                  // open input
      job.getInputFormat().getRecordReader(inputSplit, job, reporter);
    RecordReader<INKEY,INVALUE> in = isSkipping() ? 
        new SkippingRecordReader<INKEY,INVALUE>(rawIn, umbilical, reporter) :
        new TrackedRecordReader<INKEY,INVALUE>(rawIn, reporter);
    job.setBoolean("mapred.skip.on", isSkipping());


    int numReduceTasks = conf.getNumReduceTasks();
    
    //Modified by TotemTang
    noSort = job.getBoolean("mapred.map.nosort.on", false);
    LOG.info("mapred.nosort.on " + (noSort ? "On" : "Off"));
    LOG.info("numReduceTasks: " + numReduceTasks);
    MapOutputCollector collector = null;
    if (numReduceTasks > 0) {
      if(noSort){
    	  collector = new MapOutputBufferNoSort(umbilical, job, reporter);
      }else{
    	  collector = new MapOutputBuffer(umbilical, job, reporter);
       }
    } else { 
      collector = new DirectMapOutputCollector(umbilical, job, reporter);
    }
    MapRunnable<INKEY,INVALUE,OUTKEY,OUTVALUE> runner =
      ReflectionUtils.newInstance(job.getMapRunnerClass(), job);

    try {
      runner.run(in, new OldOutputCollector(collector, conf), reporter);
      collector.flush();
    } finally {
      //close
      in.close();                               // close input
      collector.close();
    }
  }

  /**
   * Update the job with details about the file split
   * @param job the job configuration to update
   * @param inputSplit the file split
   */
  private void updateJobWithSplit(final JobConf job, InputSplit inputSplit) {
    if (inputSplit instanceof FileSplit) {
      FileSplit fileSplit = (FileSplit) inputSplit;
      job.set("map.input.file", fileSplit.getPath().toString());
      job.setLong("map.input.start", fileSplit.getStart());
      job.setLong("map.input.length", fileSplit.getLength());
    }
  }

  static class NewTrackingRecordReader<K,V> 
    extends org.apache.hadoop.mapreduce.RecordReader<K,V> {
    private final org.apache.hadoop.mapreduce.RecordReader<K,V> real;
    private final org.apache.hadoop.mapreduce.Counter inputRecordCounter;
    private final TaskReporter reporter;
    
    NewTrackingRecordReader(org.apache.hadoop.mapreduce.RecordReader<K,V> real,
                            TaskReporter reporter) {
      this.real = real;
      this.reporter = reporter;
      this.inputRecordCounter = reporter.getCounter(MAP_INPUT_RECORDS);
    }

    @Override
    public void close() throws IOException {
      real.close();
    }

    @Override
    public K getCurrentKey() throws IOException, InterruptedException {
      return real.getCurrentKey();
    }

    @Override
    public V getCurrentValue() throws IOException, InterruptedException {
      return real.getCurrentValue();
    }

    @Override
    public float getProgress() throws IOException, InterruptedException {
      return real.getProgress();
    }

    @Override
    public void initialize(org.apache.hadoop.mapreduce.InputSplit split,
                           org.apache.hadoop.mapreduce.TaskAttemptContext context
                           ) throws IOException, InterruptedException {
      real.initialize(split, context);
    }

    @Override
    public boolean nextKeyValue() throws IOException, InterruptedException {
      boolean result = real.nextKeyValue();
      if (result) {
        inputRecordCounter.increment(1);
      }
      reporter.setProgress(getProgress());
      return result;
    }
  }

  /**
   * Since the mapred and mapreduce Partitioners don't share a common interface
   * (JobConfigurable is deprecated and a subtype of mapred.Partitioner), the
   * partitioner lives in Old/NewOutputCollector. Note that, for map-only jobs,
   * the configured partitioner should not be called. It's common for
   * partitioners to compute a result mod numReduces, which causes a div0 error
   */
  private static class OldOutputCollector<K,V> implements OutputCollector<K,V> {
    private final Partitioner<K,V> partitioner;
    private final MapOutputCollector<K,V> collector;
    private final int numPartitions;

    @SuppressWarnings("unchecked")
    OldOutputCollector(MapOutputCollector<K,V> collector, JobConf conf) {
      numPartitions = conf.getNumReduceTasks();
      if (numPartitions > 0) {
        partitioner = (Partitioner<K,V>)
          ReflectionUtils.newInstance(conf.getPartitionerClass(), conf);
      } else {
        partitioner = new Partitioner<K,V>() {
          @Override
          public void configure(JobConf job) { }
          @Override
          public int getPartition(K key, V value, int numPartitions) {
            return -1;
          }
        };
      }
      this.collector = collector;
    }

    @Override
    public void collect(K key, V value) throws IOException {
      try {
        collector.collect(key, value,
                          partitioner.getPartition(key, value, numPartitions));
      } catch (InterruptedException ie) {
        Thread.currentThread().interrupt();
        throw new IOException("interrupt exception", ie);
      }
    }
  }

  private class NewDirectOutputCollector<K,V>
  extends org.apache.hadoop.mapreduce.RecordWriter<K,V> {
    private final org.apache.hadoop.mapreduce.RecordWriter out;

    private final TaskReporter reporter;

    private final Counters.Counter mapOutputRecordCounter;
    
    @SuppressWarnings("unchecked")
    NewDirectOutputCollector(org.apache.hadoop.mapreduce.JobContext jobContext,
        JobConf job, TaskUmbilicalProtocol umbilical, TaskReporter reporter) 
    throws IOException, ClassNotFoundException, InterruptedException {
      this.reporter = reporter;
      out = outputFormat.getRecordWriter(taskContext);
      mapOutputRecordCounter = 
        reporter.getCounter(MAP_OUTPUT_RECORDS);
    }

    @Override
    @SuppressWarnings("unchecked")
    public void write(K key, V value) 
    throws IOException, InterruptedException {
      reporter.progress();
      out.write(key, value);
      mapOutputRecordCounter.increment(1);
    }

    @Override
    public void close(TaskAttemptContext context) 
    throws IOException,InterruptedException {
      reporter.progress();
      if (out != null) {
        out.close(context);
      }
    }
  }
  
  private class NewOutputCollector<K,V>
    extends org.apache.hadoop.mapreduce.RecordWriter<K,V> {
    private final MapOutputCollector<K,V> collector;
    private final org.apache.hadoop.mapreduce.Partitioner<K,V> partitioner;
    private final int partitions;

    @SuppressWarnings("unchecked")
    NewOutputCollector(org.apache.hadoop.mapreduce.JobContext jobContext,
                       JobConf job,
                       TaskUmbilicalProtocol umbilical,
                       TaskReporter reporter
                       ) throws IOException, ClassNotFoundException {
      collector = new MapOutputBuffer<K,V>(umbilical, job, reporter);
      partitions = jobContext.getNumReduceTasks();
      if (partitions > 0) {
        partitioner = (org.apache.hadoop.mapreduce.Partitioner<K,V>)
          ReflectionUtils.newInstance(jobContext.getPartitionerClass(), job);
      } else {
        partitioner = new org.apache.hadoop.mapreduce.Partitioner<K,V>() {
          @Override
          public int getPartition(K key, V value, int numPartitions) {
            return -1;
          }
        };
      }
    }

    @Override
    public void write(K key, V value) throws IOException, InterruptedException {
      collector.collect(key, value,
                        partitioner.getPartition(key, value, partitions));
    }

    @Override
    public void close(TaskAttemptContext context
                      ) throws IOException,InterruptedException {
      try {
        collector.flush();
      } catch (ClassNotFoundException cnf) {
        throw new IOException("can't find class ", cnf);
      }
      collector.close();
    }
  }

  @SuppressWarnings("unchecked")
  private <INKEY,INVALUE,OUTKEY,OUTVALUE>
  void runNewMapper(final JobConf job,
                    final BytesWritable rawSplit,
                    final TaskUmbilicalProtocol umbilical,
                    TaskReporter reporter
                    ) throws IOException, ClassNotFoundException,
                             InterruptedException {
    // make a task context so we can get the classes
    org.apache.hadoop.mapreduce.TaskAttemptContext taskContext =
      new org.apache.hadoop.mapreduce.TaskAttemptContext(job, getTaskID());
    // make a mapper
    org.apache.hadoop.mapreduce.Mapper<INKEY,INVALUE,OUTKEY,OUTVALUE> mapper =
      (org.apache.hadoop.mapreduce.Mapper<INKEY,INVALUE,OUTKEY,OUTVALUE>)
        ReflectionUtils.newInstance(taskContext.getMapperClass(), job);
    // make the input format
    org.apache.hadoop.mapreduce.InputFormat<INKEY,INVALUE> inputFormat =
      (org.apache.hadoop.mapreduce.InputFormat<INKEY,INVALUE>)
        ReflectionUtils.newInstance(taskContext.getInputFormatClass(), job);
    // rebuild the input split
    org.apache.hadoop.mapreduce.InputSplit split = null;
    DataInputBuffer splitBuffer = new DataInputBuffer();
    splitBuffer.reset(rawSplit.getBytes(), 0, rawSplit.getLength());
    SerializationFactory factory = new SerializationFactory(job);
    Deserializer<? extends org.apache.hadoop.mapreduce.InputSplit>
      deserializer = 
        (Deserializer<? extends org.apache.hadoop.mapreduce.InputSplit>) 
        factory.getDeserializer(job.getClassByName(splitClass));
    deserializer.open(splitBuffer);
    split = deserializer.deserialize(null);

    org.apache.hadoop.mapreduce.RecordReader<INKEY,INVALUE> input =
      new NewTrackingRecordReader<INKEY,INVALUE>
          (inputFormat.createRecordReader(split, taskContext), reporter);
    
    job.setBoolean("mapred.skip.on", isSkipping());
    org.apache.hadoop.mapreduce.RecordWriter output = null;
    org.apache.hadoop.mapreduce.Mapper<INKEY,INVALUE,OUTKEY,OUTVALUE>.Context 
         mapperContext = null;
    try {
      Constructor<org.apache.hadoop.mapreduce.Mapper.Context> contextConstructor =
        org.apache.hadoop.mapreduce.Mapper.Context.class.getConstructor
        (new Class[]{org.apache.hadoop.mapreduce.Mapper.class,
                     Configuration.class,
                     org.apache.hadoop.mapreduce.TaskAttemptID.class,
                     org.apache.hadoop.mapreduce.RecordReader.class,
                     org.apache.hadoop.mapreduce.RecordWriter.class,
                     org.apache.hadoop.mapreduce.OutputCommitter.class,
                     org.apache.hadoop.mapreduce.StatusReporter.class,
                     org.apache.hadoop.mapreduce.InputSplit.class});

      // get an output object
      if (job.getNumReduceTasks() == 0) {
         output =
           new NewDirectOutputCollector(taskContext, job, umbilical, reporter);
      } else {
        output = new NewOutputCollector(taskContext, job, umbilical, reporter);
      }

      mapperContext = contextConstructor.newInstance(mapper, job, getTaskID(),
                                                     input, output, committer,
                                                     reporter, split);

      input.initialize(split, mapperContext);
      mapper.run(mapperContext);
      input.close();
      output.close(mapperContext);
    } catch (NoSuchMethodException e) {
      throw new IOException("Can't find Context constructor", e);
    } catch (InstantiationException e) {
      throw new IOException("Can't create Context", e);
    } catch (InvocationTargetException e) {
      throw new IOException("Can't invoke Context constructor", e);
    } catch (IllegalAccessException e) {
      throw new IOException("Can't invoke Context constructor", e);
    }
  }

  interface MapOutputCollector<K, V> {

    public void collect(K key, V value, int partition
                        ) throws IOException, InterruptedException;
    public void close() throws IOException, InterruptedException;
    
    public void flush() throws IOException, InterruptedException, 
                               ClassNotFoundException;
        
  }

  class DirectMapOutputCollector<K, V>
    implements MapOutputCollector<K, V> {
 
    private RecordWriter<K, V> out = null;

    private TaskReporter reporter = null;

    private final Counters.Counter mapOutputRecordCounter;

    @SuppressWarnings("unchecked")
    public DirectMapOutputCollector(TaskUmbilicalProtocol umbilical,
        JobConf job, TaskReporter reporter) throws IOException {
      this.reporter = reporter;
      String finalName = getOutputName(getPartition());
      FileSystem fs = FileSystem.get(job);

      out = job.getOutputFormat().getRecordWriter(fs, job, finalName, reporter);

      mapOutputRecordCounter = reporter.getCounter(MAP_OUTPUT_RECORDS);
    }

    public void close() throws IOException {
      if (this.out != null) {
        out.close(this.reporter);
      }

    }

    public void flush() throws IOException, InterruptedException, 
                               ClassNotFoundException {
    }

    public void collect(K key, V value, int partition) throws IOException {
      reporter.progress();
      out.write(key, value);
      mapOutputRecordCounter.increment(1);
    }
    
  }

  class MapOutputBuffer<K extends Object, V extends Object> 
  implements MapOutputCollector<K, V>, IndexedSortable {
    private final int partitions;
    private final JobConf job;
    private final TaskReporter reporter;
    private final Class<K> keyClass;
    private final Class<V> valClass;
    private final RawComparator<K> comparator;
    private final SerializationFactory serializationFactory;
    private final Serializer<K> keySerializer;
    private final Serializer<V> valSerializer;
    private final CombinerRunner<K,V> combinerRunner;
    private final CombineOutputCollector<K, V> combineCollector;
    
    // Compression for map-outputs
    private CompressionCodec codec = null;

    // k/v accounting
    private volatile int kvstart = 0;  // marks beginning of spill
    private volatile int kvend = 0;    // marks beginning of collectable
    private int kvindex = 0;           // marks end of collected
    private final int[] kvoffsets;     // indices into kvindices
    private final int[] kvindices;     // partition, k/v offsets into kvbuffer
    private volatile int bufstart = 0; // marks beginning of spill
    private volatile int bufend = 0;   // marks beginning of collectable
    private volatile int bufvoid = 0;  // marks the point where we should stop
                                       // reading at the end of the buffer
    private int bufindex = 0;          // marks end of collected
    private int bufmark = 0;           // marks end of record
    private byte[] kvbuffer;           // main output buffer
    private static final int PARTITION = 0; // partition offset in acct
    private static final int KEYSTART = 1;  // key offset in acct
    private static final int VALSTART = 2;  // val offset in acct
    private static final int ACCTSIZE = 3;  // total #fields in acct
    private static final int RECSIZE =
                       (ACCTSIZE + 1) * 4;  // acct bytes per record

    // spill accounting
    private volatile int numSpills = 0;
    private volatile Throwable sortSpillException = null;
    private final int softRecordLimit;
    private final int softBufferLimit;
    private final int minSpillsForCombine;
    private final IndexedSorter sorter;
    private final ReentrantLock spillLock = new ReentrantLock();
    private final Condition spillDone = spillLock.newCondition();
    private final Condition spillReady = spillLock.newCondition();
    private final BlockingBuffer bb = new BlockingBuffer();
    private volatile boolean spillThreadRunning = false;
    private final SpillThread spillThread = new SpillThread();

    private final FileSystem localFs;
    private final FileSystem rfs;
   
    private final Counters.Counter mapOutputByteCounter;
    private final Counters.Counter mapOutputRecordCounter;
    private final Counters.Counter combineOutputCounter;
    
    private ArrayList<SpillRecord> indexCacheList;
    private int totalIndexCacheMemory;
    private static final int INDEX_CACHE_MEMORY_LIMIT = 1024 * 1024;
    
    //Added By TotemTang
    int waitTimes = 0;
    long waitPeriod = 0;

    @SuppressWarnings("unchecked")
    public MapOutputBuffer(TaskUmbilicalProtocol umbilical, JobConf job,
                           TaskReporter reporter
                           ) throws IOException, ClassNotFoundException {
      this.job = job;
      this.reporter = reporter;
      localFs = FileSystem.getLocal(job);
      partitions = job.getNumReduceTasks();
       
      rfs = ((LocalFileSystem)localFs).getRaw();

      indexCacheList = new ArrayList<SpillRecord>();
      
      //sanity checks
      final float spillper = job.getFloat("io.sort.spill.percent",(float)0.8);
      final float recper = job.getFloat("io.sort.record.percent",(float)0.05);
      final int sortmb = job.getInt("io.sort.mb", 100);
      if (spillper > (float)1.0 || spillper < (float)0.0) {
        throw new IOException("Invalid \"io.sort.spill.percent\": " + spillper);
      }
      if (recper > (float)1.0 || recper < (float)0.01) {
        throw new IOException("Invalid \"io.sort.record.percent\": " + recper);
      }
      if ((sortmb & 0x7FF) != sortmb) {
        throw new IOException("Invalid \"io.sort.mb\": " + sortmb);
      }
      sorter = ReflectionUtils.newInstance(
            job.getClass("map.sort.class", QuickSort.class, IndexedSorter.class), job);
      LOG.info("io.sort.mb = " + sortmb);
      // buffers and accounting
      int maxMemUsage = sortmb << 20;
      int recordCapacity = (int)(maxMemUsage * recper);
      recordCapacity -= recordCapacity % RECSIZE;
      kvbuffer = new byte[maxMemUsage - recordCapacity];
      bufvoid = kvbuffer.length;
      recordCapacity /= RECSIZE;
      kvoffsets = new int[recordCapacity];
      kvindices = new int[recordCapacity * ACCTSIZE];
      softBufferLimit = (int)(kvbuffer.length * spillper);
      softRecordLimit = (int)(kvoffsets.length * spillper);
      LOG.info("data buffer = " + softBufferLimit + "/" + kvbuffer.length);
      LOG.info("record buffer = " + softRecordLimit + "/" + kvoffsets.length);
      // k/v serialization
      comparator = job.getOutputKeyComparator();
      keyClass = (Class<K>)job.getMapOutputKeyClass();
      valClass = (Class<V>)job.getMapOutputValueClass();
      LOG.debug("comparator " + comparator.getClass().toString());
      LOG.debug("key Class " + keyClass.toString());
      LOG.debug("value class " + valClass.toString());
      serializationFactory = new SerializationFactory(job);
      keySerializer = serializationFactory.getSerializer(keyClass);
      keySerializer.open(bb);
      valSerializer = serializationFactory.getSerializer(valClass);
      valSerializer.open(bb);
      // counters
      mapOutputByteCounter = reporter.getCounter(MAP_OUTPUT_BYTES);
      mapOutputRecordCounter = reporter.getCounter(MAP_OUTPUT_RECORDS);
      Counters.Counter combineInputCounter = 
        reporter.getCounter(COMBINE_INPUT_RECORDS);
      combineOutputCounter = reporter.getCounter(COMBINE_OUTPUT_RECORDS);
      // compression
      if (job.getCompressMapOutput()) {
        Class<? extends CompressionCodec> codecClass =
          job.getMapOutputCompressorClass(DefaultCodec.class);
        codec = ReflectionUtils.newInstance(codecClass, job);
      }
      // combiner
      combinerRunner = CombinerRunner.create(job, getTaskID(), 
                                             combineInputCounter,
                                             reporter, null);
      if (combinerRunner != null) {
        combineCollector= new CombineOutputCollector<K,V>(combineOutputCounter);
      } else {
        combineCollector = null;
      }
      minSpillsForCombine = job.getInt("min.num.spills.for.combine", 3);
      spillThread.setDaemon(true);
      spillThread.setName("SpillThread");
      spillLock.lock();
      try {
        spillThread.start();
        while (!spillThreadRunning) {
          spillDone.await();
        }
      } catch (InterruptedException e) {
        throw (IOException)new IOException("Spill thread failed to initialize"
            ).initCause(sortSpillException);
      } finally {
        spillLock.unlock();
      }
      if (sortSpillException != null) {
        throw (IOException)new IOException("Spill thread failed to initialize"
            ).initCause(sortSpillException);
      }
      
      
    }

    public synchronized void collect(K key, V value, int partition
                                     ) throws IOException {
      reporter.progress();
      if (key.getClass() != keyClass) {
        throw new IOException("Type mismatch in key from map: expected "
                              + keyClass.getName() + ", recieved "
                              + key.getClass().getName());
      }
      if (value.getClass() != valClass) {
        throw new IOException("Type mismatch in value from map: expected "
                              + valClass.getName() + ", recieved "
                              + value.getClass().getName());
      }
      final int kvnext = (kvindex + 1) % kvoffsets.length;
      spillLock.lock();
      try {
        boolean kvfull;
        do {
          if (sortSpillException != null) {
            throw (IOException)new IOException("Spill failed"
                ).initCause(sortSpillException);
          }
          // sufficient acct space
          kvfull = kvnext == kvstart;
          final boolean kvsoftlimit = ((kvnext > kvend)
              ? kvnext - kvend > softRecordLimit
              : kvend - kvnext <= kvoffsets.length - softRecordLimit);
          if (kvstart == kvend && kvsoftlimit) {
            LOG.info("Spilling map output: record full = " + kvsoftlimit);
            startSpill();
          }
          if (kvfull) {
        	  waitTimes++;
        	  long start = System.nanoTime();
            try {
              while (kvstart != kvend) {
                reporter.progress();
                spillDone.await();
              }
            } catch (InterruptedException e) {
              throw (IOException)new IOException(
                  "Collector interrupted while waiting for the writer"
                  ).initCause(e);
            }
            long end = System.nanoTime();
            waitPeriod += (end - start);
          }
        } while (kvfull);
      } finally {
        spillLock.unlock();
      }

      try {
        // serialize key bytes into buffer
        int keystart = bufindex;
        keySerializer.serialize(key);
        if (bufindex < keystart) {
          // wrapped the key; reset required
          bb.reset();
          keystart = 0;
        }
        // serialize value bytes into buffer
        final int valstart = bufindex;
        valSerializer.serialize(value);
        int valend = bb.markRecord();

        if (partition < 0 || partition >= partitions) {
          throw new IOException("Illegal partition for " + key + " (" +
              partition + ")");
        }

        mapOutputRecordCounter.increment(1);
        mapOutputByteCounter.increment(valend >= keystart
            ? valend - keystart
            : (bufvoid - keystart) + valend);

        // update accounting info
        int ind = kvindex * ACCTSIZE;
        kvoffsets[kvindex] = ind;
        kvindices[ind + PARTITION] = partition;
        kvindices[ind + KEYSTART] = keystart;
        kvindices[ind + VALSTART] = valstart;
        kvindex = kvnext;
      } catch (MapBufferTooSmallException e) {
        LOG.info("Record too large for in-memory buffer: " + e.getMessage());
        spillSingleRecord(key, value, partition);
        mapOutputRecordCounter.increment(1);
        return;
      }

    }

    /**
     * Compare logical range, st i, j MOD offset capacity.
     * Compare by partition, then by key.
     * @see IndexedSortable#compare
     */
    public int compare(int i, int j) {
      final int ii = kvoffsets[i % kvoffsets.length];
      final int ij = kvoffsets[j % kvoffsets.length];
      // sort by partition
      if (kvindices[ii + PARTITION] != kvindices[ij + PARTITION]) {
        return kvindices[ii + PARTITION] - kvindices[ij + PARTITION];
      }
      // sort by key
      return comparator.compare(kvbuffer,
          kvindices[ii + KEYSTART],
          kvindices[ii + VALSTART] - kvindices[ii + KEYSTART],
          kvbuffer,
          kvindices[ij + KEYSTART],
          kvindices[ij + VALSTART] - kvindices[ij + KEYSTART]);
    }

    /**
     * Swap logical indices st i, j MOD offset capacity.
     * @see IndexedSortable#swap
     */
    public void swap(int i, int j) {
      i %= kvoffsets.length;
      j %= kvoffsets.length;
      int tmp = kvoffsets[i];
      kvoffsets[i] = kvoffsets[j];
      kvoffsets[j] = tmp;
    }

    /**
     * Inner class managing the spill of serialized records to disk.
     */
    protected class BlockingBuffer extends DataOutputStream {

      public BlockingBuffer() {
        this(new Buffer());
      }

      private BlockingBuffer(OutputStream out) {
        super(out);
      }

      /**
       * Mark end of record. Note that this is required if the buffer is to
       * cut the spill in the proper place.
       */
      public int markRecord() {
        bufmark = bufindex;
        return bufindex;
      }

      /**
       * Set position from last mark to end of writable buffer, then rewrite
       * the data between last mark and kvindex.
       * This handles a special case where the key wraps around the buffer.
       * If the key is to be passed to a RawComparator, then it must be
       * contiguous in the buffer. This recopies the data in the buffer back
       * into itself, but starting at the beginning of the buffer. Note that
       * reset() should <b>only</b> be called immediately after detecting
       * this condition. To call it at any other time is undefined and would
       * likely result in data loss or corruption.
       * @see #markRecord()
       */
      protected synchronized void reset() throws IOException {
        // spillLock unnecessary; If spill wraps, then
        // bufindex < bufstart < bufend so contention is impossible
        // a stale value for bufstart does not affect correctness, since
        // we can only get false negatives that force the more
        // conservative path
        int headbytelen = bufvoid - bufmark;
        bufvoid = bufmark;
        if (bufindex + headbytelen < bufstart) {
          System.arraycopy(kvbuffer, 0, kvbuffer, headbytelen, bufindex);
          System.arraycopy(kvbuffer, bufvoid, kvbuffer, 0, headbytelen);
          bufindex += headbytelen;
        } else {
          byte[] keytmp = new byte[bufindex];
          System.arraycopy(kvbuffer, 0, keytmp, 0, bufindex);
          bufindex = 0;
          out.write(kvbuffer, bufmark, headbytelen);
          out.write(keytmp);
        }
      }
    }

    public class Buffer extends OutputStream {
      private final byte[] scratch = new byte[1];

      @Override
      public synchronized void write(int v)
          throws IOException {
        scratch[0] = (byte)v;
        write(scratch, 0, 1);
      }

      /**
       * Attempt to write a sequence of bytes to the collection buffer.
       * This method will block if the spill thread is running and it
       * cannot write.
       * @throws MapBufferTooSmallException if record is too large to
       *    deserialize into the collection buffer.
       */
      @Override
      public synchronized void write(byte b[], int off, int len)
          throws IOException {
        boolean buffull = false;
        boolean wrap = false;
        spillLock.lock();
        try {
          do {
            if (sortSpillException != null) {
              throw (IOException)new IOException("Spill failed"
                  ).initCause(sortSpillException);
            }

            // sufficient buffer space?
            if (bufstart <= bufend && bufend <= bufindex) {
              buffull = bufindex + len > bufvoid;
              wrap = (bufvoid - bufindex) + bufstart > len;
            } else {
              // bufindex <= bufstart <= bufend
              // bufend <= bufindex <= bufstart
              wrap = false;
              buffull = bufindex + len > bufstart;
            }

            if (kvstart == kvend) {
              // spill thread not running
              if (kvend != kvindex) {
                // we have records we can spill
                final boolean bufsoftlimit = (bufindex > bufend)
                  ? bufindex - bufend > softBufferLimit
                  : bufend - bufindex < bufvoid - softBufferLimit;
                if (bufsoftlimit || (buffull && !wrap)) {
                  LOG.info("Spilling map output: buffer full= " + bufsoftlimit);
                  startSpill();
                }
              } else if (buffull && !wrap) {
                // We have no buffered records, and this record is too large
                // to write into kvbuffer. We must spill it directly from
                // collect
                final int size = ((bufend <= bufindex)
                  ? bufindex - bufend
                  : (bufvoid - bufend) + bufindex) + len;
                bufstart = bufend = bufindex = bufmark = 0;
                kvstart = kvend = kvindex = 0;
                bufvoid = kvbuffer.length;
                throw new MapBufferTooSmallException(size + " bytes");
              }
            }

            if (buffull && !wrap) {
              try {
                while (kvstart != kvend) {
                  reporter.progress();
                  spillDone.await();
                }
              } catch (InterruptedException e) {
                  throw (IOException)new IOException(
                      "Buffer interrupted while waiting for the writer"
                      ).initCause(e);
              }
            }
          } while (buffull && !wrap);
        } finally {
          spillLock.unlock();
        }
        // here, we know that we have sufficient space to write
        if (buffull) {
          final int gaplen = bufvoid - bufindex;
          System.arraycopy(b, off, kvbuffer, bufindex, gaplen);
          len -= gaplen;
          off += gaplen;
          bufindex = 0;
        }
        System.arraycopy(b, off, kvbuffer, bufindex, len);
        bufindex += len;
      }
    }

    public synchronized void flush() throws IOException, ClassNotFoundException,
                                            InterruptedException {
      LOG.info("Wait Times : " + waitTimes);
      LOG.info("wait Period : " + waitPeriod);
      LOG.info("Starting flush of map output");
      spillLock.lock();
      try {
        while (kvstart != kvend) {
          reporter.progress();
          spillDone.await();
        }
        if (sortSpillException != null) {
          throw (IOException)new IOException("Spill failed"
              ).initCause(sortSpillException);
        }
        if (kvend != kvindex) {
          kvend = kvindex;
          bufend = bufmark;
          sortAndSpill();
        }
      } catch (InterruptedException e) {
        throw (IOException)new IOException(
            "Buffer interrupted while waiting for the writer"
            ).initCause(e);
      } finally {
        spillLock.unlock();
      }
      assert !spillLock.isHeldByCurrentThread();
      // shut down spill thread and wait for it to exit. Since the preceding
      // ensures that it is finished with its work (and sortAndSpill did not
      // throw), we elect to use an interrupt instead of setting a flag.
      // Spilling simultaneously from this thread while the spill thread
      // finishes its work might be both a useful way to extend this and also
      // sufficient motivation for the latter approach.
      try {
        spillThread.interrupt();
        spillThread.join();
      } catch (InterruptedException e) {
        throw (IOException)new IOException("Spill failed"
            ).initCause(e);
      }
      // release sort buffer before the merge
      kvbuffer = null;
      mergeParts();
    }

    public void close() { }

    protected class SpillThread extends Thread {

      @Override
      public void run() {
        spillLock.lock();
        spillThreadRunning = true;
        try {
          while (true) {
            spillDone.signal();
            while (kvstart == kvend) {
              spillReady.await();
            }
            try {
              spillLock.unlock();
              sortAndSpill();
            } catch (Exception e) {
              sortSpillException = e;
            } catch (Throwable t) {
              sortSpillException = t;
              String logMsg = "Task " + getTaskID() + " failed : " 
                              + StringUtils.stringifyException(t);
              reportFatalError(getTaskID(), t, logMsg);
            } finally {
              spillLock.lock();
              if (bufend < bufindex && bufindex < bufstart) {
                bufvoid = kvbuffer.length;
              }
              kvstart = kvend;
              bufstart = bufend;
            }
          }
        } catch (InterruptedException e) {
          Thread.currentThread().interrupt();
        } finally {
          spillLock.unlock();
          spillThreadRunning = false;
        }
      }
    }

    private synchronized void startSpill() {
      LOG.info("bufstart = " + bufstart + "; bufend = " + bufmark +
               "; bufvoid = " + bufvoid);
      LOG.info("kvstart = " + kvstart + "; kvend = " + kvindex +
               "; length = " + kvoffsets.length);
      kvend = kvindex;
      bufend = bufmark;
      spillReady.signal();
    }

    private void sortAndSpill() throws IOException, ClassNotFoundException,
                                       InterruptedException {
      //approximate the length of the output file to be the length of the
      //buffer + header lengths for the partitions
      long size = (bufend >= bufstart
          ? bufend - bufstart
          : (bufvoid - bufend) + bufstart) +
                  partitions * APPROX_HEADER_LENGTH;
      FSDataOutputStream out = null;
      try {
        // create spill file
        final SpillRecord spillRec = new SpillRecord(partitions);
        final Path filename = mapOutputFile.getSpillFileForWrite(getTaskID(),
            numSpills, size);
        out = rfs.create(filename);

        final int endPosition = (kvend > kvstart)
          ? kvend
          : kvoffsets.length + kvend;
        sorter.sort(MapOutputBuffer.this, kvstart, endPosition, reporter);
        int spindex = kvstart;
        IndexRecord rec = new IndexRecord();
        InMemValBytes value = new InMemValBytes();
        for (int i = 0; i < partitions; ++i) {
          IFile.Writer<K, V> writer = null;
          try {
            long segmentStart = out.getPos();
            writer = new Writer<K, V>(job, out, keyClass, valClass, codec,
                                      spilledRecordsCounter);
            if (combinerRunner == null) {
              // spill directly
              DataInputBuffer key = new DataInputBuffer();
              while (spindex < endPosition &&
                  kvindices[kvoffsets[spindex % kvoffsets.length]
                            + PARTITION] == i) {
                final int kvoff = kvoffsets[spindex % kvoffsets.length];
                getVBytesForOffset(kvoff, value);
                key.reset(kvbuffer, kvindices[kvoff + KEYSTART],
                          (kvindices[kvoff + VALSTART] - 
                           kvindices[kvoff + KEYSTART]));
                writer.append(key, value);
                ++spindex;
              }
            } else {
              int spstart = spindex;
              while (spindex < endPosition &&
                  kvindices[kvoffsets[spindex % kvoffsets.length]
                            + PARTITION] == i) {
                ++spindex;
              }
              // Note: we would like to avoid the combiner if we've fewer
              // than some threshold of records for a partition
              if (spstart != spindex) {
                combineCollector.setWriter(writer);
                RawKeyValueIterator kvIter =
                  new MRResultIterator(spstart, spindex);
                combinerRunner.combine(kvIter, combineCollector);
              }
            }

            // close the writer
            writer.close();

            // record offsets
            rec.startOffset = segmentStart;
            rec.rawLength = writer.getRawLength();
            rec.partLength = writer.getCompressedLength();
            spillRec.putIndex(rec, i);

            writer = null;
          } finally {
            if (null != writer) writer.close();
          }
        }

        if (totalIndexCacheMemory >= INDEX_CACHE_MEMORY_LIMIT) {
          // create spill index file
          Path indexFilename = mapOutputFile.getSpillIndexFileForWrite(
              getTaskID(), numSpills,
              partitions * MAP_OUTPUT_INDEX_RECORD_LENGTH);
          spillRec.writeToFile(indexFilename, job);
        } else {
          indexCacheList.add(spillRec);
          totalIndexCacheMemory +=
            spillRec.size() * MAP_OUTPUT_INDEX_RECORD_LENGTH;
        }
        LOG.info("Finished spill " + numSpills);
        ++numSpills;
      } finally {
        if (out != null) out.close();
      }
    }

    /**
     * Handles the degenerate case where serialization fails to fit in
     * the in-memory buffer, so we must spill the record from collect
     * directly to a spill file. Consider this "losing".
     */
    private void spillSingleRecord(final K key, final V value,
                                   int partition) throws IOException {
      long size = kvbuffer.length + partitions * APPROX_HEADER_LENGTH;
      FSDataOutputStream out = null;
      try {
        // create spill file
        final SpillRecord spillRec = new SpillRecord(partitions);
        final Path filename = mapOutputFile.getSpillFileForWrite(getTaskID(),
            numSpills, size);
        out = rfs.create(filename);
        
        // we don't run the combiner for a single record
        IndexRecord rec = new IndexRecord();
        for (int i = 0; i < partitions; ++i) {
          IFile.Writer<K, V> writer = null;
          try {
            long segmentStart = out.getPos();
            // Create a new codec, don't care!
            writer = new IFile.Writer<K,V>(job, out, keyClass, valClass, codec,
                                            spilledRecordsCounter);

            if (i == partition) {
              final long recordStart = out.getPos();
              writer.append(key, value);
              // Note that our map byte count will not be accurate with
              // compression
              mapOutputByteCounter.increment(out.getPos() - recordStart);
            }
            writer.close();

            // record offsets
            rec.startOffset = segmentStart;
            rec.rawLength = writer.getRawLength();
            rec.partLength = writer.getCompressedLength();
            spillRec.putIndex(rec, i);

            writer = null;
          } catch (IOException e) {
            if (null != writer) writer.close();
            throw e;
          }
        }
        if (totalIndexCacheMemory >= INDEX_CACHE_MEMORY_LIMIT) {
          // create spill index file
          Path indexFilename = mapOutputFile.getSpillIndexFileForWrite(
              getTaskID(), numSpills,
              partitions * MAP_OUTPUT_INDEX_RECORD_LENGTH);
          spillRec.writeToFile(indexFilename, job);
        } else {
          indexCacheList.add(spillRec);
          totalIndexCacheMemory +=
            spillRec.size() * MAP_OUTPUT_INDEX_RECORD_LENGTH;
        }
        ++numSpills;
      } finally {
        if (out != null) out.close();
      }
    }

    /**
     * Given an offset, populate vbytes with the associated set of
     * deserialized value bytes. Should only be called during a spill.
     */
    private void getVBytesForOffset(int kvoff, InMemValBytes vbytes) {
      final int nextindex = (kvoff / ACCTSIZE ==
                            (kvend - 1 + kvoffsets.length) % kvoffsets.length)
        ? bufend
        : kvindices[(kvoff + ACCTSIZE + KEYSTART) % kvindices.length];
      int vallen = (nextindex >= kvindices[kvoff + VALSTART])
        ? nextindex - kvindices[kvoff + VALSTART]
        : (bufvoid - kvindices[kvoff + VALSTART]) + nextindex;
      vbytes.reset(kvbuffer, kvindices[kvoff + VALSTART], vallen);
    }

    /**
     * Inner class wrapping valuebytes, used for appendRaw.
     */
    protected class InMemValBytes extends DataInputBuffer {
      private byte[] buffer;
      private int start;
      private int length;
            
      public void reset(byte[] buffer, int start, int length) {
        this.buffer = buffer;
        this.start = start;
        this.length = length;
        
        if (start + length > bufvoid) {
          this.buffer = new byte[this.length];
          final int taillen = bufvoid - start;
          System.arraycopy(buffer, start, this.buffer, 0, taillen);
          System.arraycopy(buffer, 0, this.buffer, taillen, length-taillen);
          this.start = 0;
        }
        
        super.reset(this.buffer, this.start, this.length);
      }
    }

    protected class MRResultIterator implements RawKeyValueIterator {
      private final DataInputBuffer keybuf = new DataInputBuffer();
      private final InMemValBytes vbytes = new InMemValBytes();
      private final int end;
      private int current;
      public MRResultIterator(int start, int end) {
        this.end = end;
        current = start - 1;
      }
      public boolean next() throws IOException {
        return ++current < end;
      }
      public DataInputBuffer getKey() throws IOException {
        final int kvoff = kvoffsets[current % kvoffsets.length];
        keybuf.reset(kvbuffer, kvindices[kvoff + KEYSTART],
                     kvindices[kvoff + VALSTART] - kvindices[kvoff + KEYSTART]);
        return keybuf;
      }
      public DataInputBuffer getValue() throws IOException {
        getVBytesForOffset(kvoffsets[current % kvoffsets.length], vbytes);
        return vbytes;
      }
      public Progress getProgress() {
        return null;
      }
      public void close() { }
    }

    private void mergeParts() throws IOException, InterruptedException, 
                                     ClassNotFoundException {
      // get the approximate size of the final output/index files
      long finalOutFileSize = 0;
      long finalIndexFileSize = 0;
      final Path[] filename = new Path[numSpills];
      final TaskAttemptID mapId = getTaskID();

      for(int i = 0; i < numSpills; i++) {
        filename[i] = mapOutputFile.getSpillFile(mapId, i);
        finalOutFileSize += rfs.getFileStatus(filename[i]).getLen();
      }
      if (numSpills == 1) { //the spill is the final output
        rfs.rename(filename[0],
            new Path(filename[0].getParent(), "file.out"));
        if (indexCacheList.size() == 0) {
          rfs.rename(mapOutputFile.getSpillIndexFile(mapId, 0),
              new Path(filename[0].getParent(),"file.out.index"));
        } else {
          indexCacheList.get(0).writeToFile(
                new Path(filename[0].getParent(),"file.out.index"), job);
        }
        return;
      }

      // read in paged indices
      for (int i = indexCacheList.size(); i < numSpills; ++i) {
        Path indexFileName = mapOutputFile.getSpillIndexFile(mapId, i);
        indexCacheList.add(new SpillRecord(indexFileName, job));
      }

      //make correction in the length to include the sequence file header
      //lengths for each partition
      finalOutFileSize += partitions * APPROX_HEADER_LENGTH;
      finalIndexFileSize = partitions * MAP_OUTPUT_INDEX_RECORD_LENGTH;
      Path finalOutputFile = mapOutputFile.getOutputFileForWrite(mapId,
                             finalOutFileSize);
      Path finalIndexFile = mapOutputFile.getOutputIndexFileForWrite(
                            mapId, finalIndexFileSize);

      //The output stream for the final single output file
      FSDataOutputStream finalOut = rfs.create(finalOutputFile, true, 4096);

      if (numSpills == 0) {
        //create dummy files
        IndexRecord rec = new IndexRecord();
        SpillRecord sr = new SpillRecord(partitions);
        try {
          for (int i = 0; i < partitions; i++) {
            long segmentStart = finalOut.getPos();
            Writer<K, V> writer =
              new Writer<K, V>(job, finalOut, keyClass, valClass, codec, null);
            writer.close();
            rec.startOffset = segmentStart;
            rec.rawLength = writer.getRawLength();
            rec.partLength = writer.getCompressedLength();
            sr.putIndex(rec, i);
          }
          sr.writeToFile(finalIndexFile, job);
        } finally {
          finalOut.close();
        }
        return;
      }
      {
        IndexRecord rec = new IndexRecord();
        final SpillRecord spillRec = new SpillRecord(partitions);
        for (int parts = 0; parts < partitions; parts++) {
          //create the segments to be merged
          List<Segment<K,V>> segmentList =
            new ArrayList<Segment<K, V>>(numSpills);
          for(int i = 0; i < numSpills; i++) {
            IndexRecord indexRecord = indexCacheList.get(i).getIndex(parts);

            Segment<K,V> s =
              new Segment<K,V>(job, rfs, filename[i], indexRecord.startOffset,
                               indexRecord.partLength, codec, true);
            segmentList.add(i, s);

            if (LOG.isDebugEnabled()) {
              LOG.debug("MapId=" + mapId + " Reducer=" + parts +
                  "Spill =" + i + "(" + indexRecord.startOffset + "," +
                  indexRecord.rawLength + ", " + indexRecord.partLength + ")");
            }
          }

          //merge
          @SuppressWarnings("unchecked")
          RawKeyValueIterator kvIter = Merger.merge(job, rfs,
                         keyClass, valClass, codec,
                         segmentList, job.getInt("io.sort.factor", 100),
                         new Path(mapId.toString()),
                         job.getOutputKeyComparator(), reporter,
                         null, spilledRecordsCounter);

          //write merged output to disk
          long segmentStart = finalOut.getPos();
          Writer<K, V> writer =
              new Writer<K, V>(job, finalOut, keyClass, valClass, codec,
                               spilledRecordsCounter);
          if (combinerRunner == null || numSpills < minSpillsForCombine) {
            Merger.writeFile(kvIter, writer, reporter, job);
          } else {
            combineCollector.setWriter(writer);
            combinerRunner.combine(kvIter, combineCollector);
          }

          //close
          writer.close();

          // record offsets
          rec.startOffset = segmentStart;
          rec.rawLength = writer.getRawLength();
          rec.partLength = writer.getCompressedLength();
          spillRec.putIndex(rec, parts);
        }
        spillRec.writeToFile(finalIndexFile, job);
        finalOut.close();
        for(int i = 0; i < numSpills; i++) {
          rfs.delete(filename[i],true);
        }
      }
    }

  } // MapOutputBuffer
  
  
  //Added By TotemTang
  
  private static PartRecord EMPTY_RECORD = new PartRecord(0,0,0);
  private static List<List<BlockBuffer>> EMPTY_BLOCKS = Collections.EMPTY_LIST;
  private static final int OUTPUT_NOSORT_INDEX_RECORD_LENGTH = 24;
 
  class MapOutputBufferNoSort<K extends Object, V extends Object>
  implements MapOutputCollector<K, V> {
	private final int partitions;
	private final JobConf job;
	private final TaskReporter reporter;
	private final Class<K> keyClass;
	private final Class<V> valClass;
	private final SerializationFactory serializationFactory;
	
	private List<List<BlockBuffer>> currentBlocks = null;
	private List<List<BlockBuffer>> blocksForSpill = EMPTY_BLOCKS;
	
	
	//elements for spill
	private final int memCapacity;
	private final int memLimit;
	private int memUsed;
	//private final int blockSize = 1024*1024;
	private final int blockSize = 1024*1024;
	private final float recPer ; 
	
	private CompressionCodec codec = null;
	
	private final FileSystem localFS;
	private final FileSystem rfs;
			
	private final SpillNoSortThread spillNoSortThread = new SpillNoSortThread();
	private List<SpillIndex> spillIndexList = null;
	private volatile Throwable spillException = null;
	private volatile int numSpills = 0;
	
	int waitTimes = 0;
	long waitPeriod = 0;
	
	//locks for synchronize
	private final ReentrantLock spillNoSortLock = new ReentrantLock();
	private final Condition spillNoSortDone = spillNoSortLock.newCondition();
	private final Condition spillNoSortReady = spillNoSortLock.newCondition();
	private volatile boolean spillNoSortThreadRunning = false;
	
	//Memory Management
	BlockBufferCache blockBufferCache;
	
	//Counter
    private final Counters.Counter mapOutputByteCounter;
    private final Counters.Counter mapOutputRecordCounter;
	
	
	  
	public MapOutputBufferNoSort(TaskUmbilicalProtocol umbilical, JobConf job,
			TaskReporter reporter) throws IOException, ClassNotFoundException {
		this.job = job;
		this.reporter = reporter;
		partitions = job.getNumReduceTasks();
		keyClass = (Class<K>)job.getMapOutputKeyClass();
		valClass = (Class<V>)job.getMapOutputValueClass();
		serializationFactory = new SerializationFactory(job);
		
		if(job.getCompressMapOutput()){
			Class<? extends CompressionCodec> codecClass = 
					job.getMapOutputCompressorClass(DefaultCodec.class);
			codec = ReflectionUtils.newInstance(codecClass, this.job);
		}
		
		currentBlocks = newTwoDimList();
		
		//For Spill
		final int sortmb = job.getInt("io.sort.mb", 100);
		final float spillper = job.getFloat("io.sort.spill.percent", (float)0.8);
		recPer = job.getFloat("io.sort.record.percent", (float)0.05);
		memCapacity = sortmb << 20;
		memLimit = (int)(memCapacity * spillper);
		memUsed = 0;
		
		spillIndexList = new ArrayList<SpillIndex>();
		
		localFS = FileSystem.getLocal(this.job);
		rfs = ((LocalFileSystem)localFS).getRaw();
		
		spillNoSortThread.setDaemon(true);
		spillNoSortThread.setName("SpillThread");
		spillNoSortLock.lock();
		try{
			spillNoSortThread.start();
			while(!spillNoSortThreadRunning){
				
				spillNoSortDone.await();
			}
		}catch(InterruptedException e){
			throw (IOException)new IOException("Spill thread failed to initialize"
						).initCause(spillException);
		}finally{
			spillNoSortLock.unlock();
		}
		blockBufferCache = new BlockBufferCache();
		
	   mapOutputByteCounter = reporter.getCounter(MAP_OUTPUT_BYTES);
	   mapOutputRecordCounter = reporter.getCounter(MAP_OUTPUT_RECORDS);
	}



	@Override
	public void collect(K key, V value, int partition) throws IOException,
			InterruptedException {
		reporter.progress();
		
		//Check key value Type
		if(key.getClass() != keyClass){
			throw new IOException("Type mismatch in key from map: expected "
									 + keyClass.getName() + ", recieved "
									 + key.getClass().getName());
		}
		if(value.getClass() != valClass){
			throw new IOException("Type mismatch in key from map: expected "
									 + valClass.getName() + ", recieved "
									 + value.getClass().getName());
		}
		
		//Check memory usage and make spills when needed
		spillNoSortLock.lock();
		try{
			boolean kvFull;
			do{
				if(spillException != null){
					throw (IOException)new IOException("Spill failed"
							).initCause(spillException);
				}
				kvFull = memUsed > memCapacity - blockSize;
				final boolean softLimit = memUsed >= memLimit;
				if(blocksForSpill == EMPTY_BLOCKS && softLimit){
					LOG.info("Spilling map output: record full = " + softLimit);
					startSpill();
				}
				if(kvFull){
					waitTimes++;
					long start = System.nanoTime();
					try{
						while(blocksForSpill != EMPTY_BLOCKS){
							reporter.progress();
							spillNoSortDone.await();
						}
					}catch (InterruptedException e){
						throw (IOException)new IOException(
								"Collector interrupted while waitng for the writer").initCause(e);
					}
					long end = System.nanoTime();
					waitPeriod += (end - start);
				}
			}while(kvFull);
		}finally{
			spillNoSortLock.unlock();
		}
		
		//Now we get to make serialization
		//Firstly, allocate corresponding buffer block
		BlockBuffer curBuffer = getBlockBuffer(partition);
		if(curBuffer == null || curBuffer.isFull()){
			curBuffer = blockBufferCache.newBlockBuffer();
		}
		boolean success = false;
		int keyStart = 0, valStart = 0;
		//Serialization
		//1. record key and value offsets
		//2. serialize key-value pair into one block buffer
		do{
			keyStart = curBuffer.getOffset();
			curBuffer.serializeKey(key);
			if(curBuffer.isFull()){
				curBuffer = blockBufferCache.newBlockBuffer();
				continue;
			}
			
			valStart = curBuffer.getOffset();
			curBuffer.serializeValue(value);
			if(curBuffer.isFull()){
				curBuffer.resetRecord();
				curBuffer = blockBufferCache.newBlockBuffer();
				continue;
			}
			success = true;
			
		}while(!success);
		
		int valEnd = curBuffer.getOffset();
		mapOutputRecordCounter.increment(1);
		mapOutputByteCounter.increment(valEnd - keyStart);
		
		//Mark record when key-value pair is serialized successfully
		curBuffer.markRecord(keyStart, valStart);
		
		if(!curBuffer.isOld())
			putBlockBuffer(curBuffer, partition);
	}
	
	
	private List<List<BlockBuffer>> newTwoDimList(){
		List<List<BlockBuffer>> retList = new ArrayList<List<BlockBuffer>>(partitions);
		for(int i = 0; i < partitions; i++){
			List<BlockBuffer> tmpList = new LinkedList<BlockBuffer>();
			retList.add(tmpList);
		}
		return retList;
	}
	
	private BlockBuffer getBlockBuffer(int partition){
		if(currentBlocks == EMPTY_BLOCKS){
			currentBlocks = newTwoDimList();
		}
		List<BlockBuffer> partBuffer = currentBlocks.get(partition);
		if(partBuffer.size() == 0)
			return null;
		return partBuffer.get(0);
	}
	
	private void putBlockBuffer(BlockBuffer bb, int partition){
		bb.setOld();
		if(currentBlocks == EMPTY_BLOCKS){
			currentBlocks = newTwoDimList();
		}
		List<BlockBuffer> partBuffer = currentBlocks.get(partition);
		partBuffer.add(0, bb);
	}
	
	private void startSpill(){
		blocksForSpill = currentBlocks;
		currentBlocks = EMPTY_BLOCKS;
		spillNoSortReady.signal();
	}

	@Override
	public void close() throws IOException, InterruptedException {
	}

	@Override
	public void flush() throws IOException, InterruptedException,
			ClassNotFoundException {
		LOG.info("Wait Times : " + waitTimes);
		LOG.info("wait Period : " + waitPeriod);
		LOG.info("Starting flush of map output");
		spillNoSortLock.lock();
		try{
			while(blocksForSpill != EMPTY_BLOCKS){
				reporter.progress();
				spillNoSortDone.await();
			}
			if(spillException != null) {
				throw (IOException)new IOException("Spill failed"
						).initCause(spillException);
			}
			if(currentBlocks != EMPTY_BLOCKS){
				blocksForSpill = currentBlocks;
				currentBlocks = EMPTY_BLOCKS;
				doSpillNoSort();
			}
		}catch(InterruptedException e){
			throw (IOException)new IOException(
					"Buffer interrupted while waiting for the writer"
					).initCause(e);
		}finally{
			spillNoSortLock.unlock();
		}
		assert !spillNoSortLock.isHeldByCurrentThread();
		
		try{
			spillNoSortThread.interrupt();
			spillNoSortThread.join();
		}catch(InterruptedException e){
			throw (IOException)new IOException("Spill failed"
					).initCause(e);
		}
		
		currentBlocks = blocksForSpill = EMPTY_BLOCKS;
		mergeNoSortParts();
		
	}
	
	private void mergeNoSortParts() throws IOException, InterruptedException,
											    ClassNotFoundException {
		// get the approximate size of the final output/index files
		long finalOutFileSize = 0;
		long finalIndexFileSize = 0;
		final Path[] fileName = new Path[numSpills];
		final TaskAttemptID mapId = getTaskID();
		
		for(int i = 0; i < numSpills; i++){
			fileName[i] = mapOutputFile.getSpillFile(mapId, i);
			finalOutFileSize += rfs.getFileStatus(fileName[i]).getLen();
		}
		
		//handle the only one spill file
		if(numSpills == 1){ //the spill is the final output
			rfs.rename(fileName[0],
					new Path(fileName[0].getParent(), "file.out"));
			spillIndexList.get(0).writeToFile(new Path(fileName[0].getParent(), "file.out.index"), job);
			return;
		}
		
		finalOutFileSize += partitions * APPROX_HEADER_LENGTH;
		finalIndexFileSize = partitions * OUTPUT_NOSORT_INDEX_RECORD_LENGTH;
		Path finalOutputFile = mapOutputFile.getOutputFileForWrite(mapId,
									finalOutFileSize);
		Path finalIndexFile = mapOutputFile.getOutputIndexFileForWrite(
									mapId, finalIndexFileSize);
		
		//The output stream for the final single output file
		FSDataOutputStream finalOut = rfs.create(finalOutputFile, true, 4096);
		
		//No spills, output dummy files
		if(numSpills == 0){
			//create dummy files
			PartRecord partRecord = new PartRecord();
			SpillIndex spillIndex = new SpillIndex(partitions);
			try{
				for(int i = 0;  i < partitions; i++){
					long segmentStart = finalOut.getPos();
					Writer<K, V> writer =
							new Writer<K, V>(job, finalOut, keyClass, valClass, codec, null);
					writer.close();
					partRecord.startOffset = segmentStart;
					partRecord.rawLength = writer.getRawLength();
					partRecord.partLength = writer.getCompressedLength();
					spillIndex.setPartition(partRecord, i);
				}
				spillIndex.writeToFile(finalIndexFile, job);
			}finally{
				finalOut.close();
			}
			return;
		}
		
		//Merge spills into one file
		PartRecord partRecord = new PartRecord(); 
		final SpillIndex spillIndex = new SpillIndex(partitions);
		for(int parts = 0; parts < partitions; parts++){
			long segmentStart = finalOut.getPos();
			Writer<K, V> writer = 
					new Writer<K, V>(job, finalOut, keyClass, valClass, codec,
														spilledRecordsCounter);
			for(int i = 0; i < numSpills; i++){
				PartRecord tmpRec = spillIndexList.get(i).getPartition(parts);
				Segment<K, V> s = 
						new Segment<K, V>(job, rfs, fileName[i], tmpRec.startOffset,
								tmpRec.partLength, codec, true);
				s.init(spilledRecordsCounter);
				while(s.next()){
					writer.append(s.getKey(), s.getValue());
				}
			}
			writer.close();
			partRecord.startOffset = segmentStart;
			partRecord.rawLength = writer.getRawLength();
			partRecord.partLength = writer.getCompressedLength();
			spillIndex.setPartition(partRecord, parts);
		}
		spillIndex.writeToFile(finalIndexFile, job);
		finalOut.close();
		for(int i = 0; i < numSpills; i++){
			rfs.delete(fileName[i], true);
		}
	}
	
	private class SpillNoSortThread extends Thread {
		
		@Override
		public void run(){
			spillNoSortLock.lock();
			spillNoSortThreadRunning = true;
			long mallocSize = 0;
			try{
				while(true){
					spillNoSortDone.signal();
					while(blocksForSpill == EMPTY_BLOCKS)
						spillNoSortReady.await();
					try{
						spillNoSortLock.unlock();
						mallocSize = doSpillNoSort();
					}catch (Exception e) {
						spillException = e;
					}catch (Throwable t){
						spillException = t;
						String logMsg = "Task " + getTaskID() + " failed : "
										+ StringUtils.stringifyException(t);
						reportFatalError(getTaskID(), t, logMsg);
					}finally{
						spillNoSortLock.lock();
						//Clear used memory and Update corresponding meta data
						//Actually, we just synchronize on blocksForSpill and memUsed
						blocksForSpill = EMPTY_BLOCKS;
						memUsed -= mallocSize;
					}
				}
			}catch (InterruptedException e){
				Thread.currentThread().interrupt();
			} finally {
				spillNoSortLock.unlock();
				spillNoSortThreadRunning = false;
			}
		}
	}
	
	private long doSpillNoSort() throws IOException, ClassNotFoundException,
											 InterruptedException {
		long size = calcBytes(blocksForSpill) + partitions * APPROX_HEADER_LENGTH;
		long mallocSize = 0;
		FSDataOutputStream out = null;
		try{
			final SpillIndex spillIndex = new SpillIndex(partitions);
			final Path filename = mapOutputFile.getSpillFileForWrite(getTaskID(), 
					numSpills, size);
			out = rfs.create(filename);
			
			DataInputBuffer key = new DataInputBuffer();
			DataInputBuffer value = new DataInputBuffer();
			PartRecord partRecord = new PartRecord(0,0,0);
			//Iterate blocksForSpill list by turn of partition
			for(int i = 0; i < partitions; i++){
				Iterator<BlockBuffer> bbIter = blocksForSpill.get(i).iterator();
				IFile.Writer<K, V> writer = null;
				try{
					long segmentStart = out.getPos();
					writer = new Writer<K, V>(job, out, keyClass, valClass, codec,
												 spilledRecordsCounter);
					while(bbIter.hasNext()){
						BlockBuffer bb = bbIter.next();
						bb.writeBlockBuffer(key, value, writer);
						mallocSize += blockBufferCache.freeBlockBuffer(bb);
					}
					writer.close();
					
					partRecord.startOffset = segmentStart;
					partRecord.rawLength = writer.getRawLength();
					partRecord.partLength = writer.getCompressedLength();
					spillIndex.setPartition(partRecord, i);
					
					writer = null;
				}finally {
					if(null != writer) writer.close();
				}
			}
			spillIndexList.add(spillIndex);
			LOG.info("Finished spill " + numSpills + " : spilled bytes " + mallocSize);
			++numSpills;
		}finally{
			if(out != null)
				out.close();
		}
		return mallocSize;
	}
	
	private long calcBytes(List<List<BlockBuffer>> blocksForSpill){
		Iterator<List<BlockBuffer>> iterLists = blocksForSpill.iterator();
		long size = 0;
		while(iterLists.hasNext()){
			Iterator<BlockBuffer> iterblocks = iterLists.next().iterator();
			while(iterblocks.hasNext()){
				BlockBuffer bb = iterblocks.next();
				size += bb.getOffset();
			}		
		}
		return size;
	}
	
	private class BlockBufferCache{
		LinkedList<BlockBuffer> blockBufferList = new LinkedList<BlockBuffer>();
		Object listLock = new Object();
		
		public BlockBufferCache() throws IOException, ClassNotFoundException{
			int cacheSize = memCapacity / blockSize;
			for(int i = 0; i < cacheSize; i++){
				blockBufferList.addFirst(
						new BlockBuffer(serializationFactory, keyClass, valClass, blockSize, recPer));
			}
		}
		
		public BlockBuffer newBlockBuffer() throws IOException{
			BlockBuffer bb;
			boolean kvFull;
			spillNoSortLock.lock();
			try{
				do{
					if(spillException != null){
						throw (IOException)new IOException("Spill failed"
								).initCause(spillException);
					}
					kvFull = memUsed > memCapacity - blockSize;
					final boolean softLimit = memUsed >= memLimit;
					if(blocksForSpill == EMPTY_BLOCKS && softLimit){
						LOG.info("Spilling map output: record full = " + softLimit);
						startSpill();
					}
					if(kvFull){
						waitTimes++;
						long start = System.nanoTime();
						try{
							while(blocksForSpill != EMPTY_BLOCKS){
								reporter.progress();
								spillNoSortDone.await();
							}
						}catch (InterruptedException e){
							throw (IOException)new IOException(
									"Collector interrupted while waitng for the writer").initCause(e);
						}
						long end = System.nanoTime();
						waitPeriod += (end - start);
					}
				}while(kvFull);
				synchronized(listLock){
					bb = blockBufferList.removeFirst();
				}
				memUsed += blockSize;
			}finally{
				spillNoSortLock.unlock();
			}
			return bb;
		}
		
		public int freeBlockBuffer(BlockBuffer bb){
			bb.reset();
			synchronized(listLock){
				blockBufferList.addFirst(bb);
			}
			return bb.getBufSize();
		}
	}
	  
  } //MapOutputBufferNoSort
  
  
  private static class BlockBuffer<K extends Object, V extends Object>{
	  private Serializer<K> keySerializer;
	  private Serializer<V> valSerializer;
	  
	  // k/v accounting
	  private int bufUsed = 0;
	  private int bufMark = 0;
	  private byte[] kvBuffer;
	  private int[] kvRec;
	  private int bufSize;
	  private int recSize;
	  private boolean isFull = false;
	  private int recCount = 0;
	  private boolean isOld = false;
	  private static final int KEYSTART = 0;
	  private static final int VALSTART = 1;
	  private static final int ACCTSIZE = 2;
	  private static final int RECSIZE = ACCTSIZE*4;
	  
	  private DataBuffer db = new DataBuffer();
	  
	  public BlockBuffer(SerializationFactory serializationFactory,
			  Class<K> keyClass, Class<V> valClass, int bufferCapacity, float recper)
					  throws IOException, ClassNotFoundException{
		  keySerializer = serializationFactory.getSerializer(keyClass);
		  keySerializer.open(db);
		  valSerializer = serializationFactory.getSerializer(valClass);
		  valSerializer.open(db);
		  
		  //recSize use BYTE as unit first and then use RECSIZE as unit
		  recSize = (int)(bufferCapacity * recper);
		  recSize -= recSize % RECSIZE;
		  recSize /= RECSIZE;
		  kvRec = new int[recSize * ACCTSIZE];
		  bufSize = bufferCapacity - recSize * RECSIZE;
		  kvBuffer = new byte[bufSize];
	  }
	  
	  public void serializeKey(K key) throws IOException{
		  keySerializer.serialize(key);
	  }
	  
	  public void serializeValue(V val) throws IOException{
		  valSerializer.serialize(val);
	  }
	  
	  public int getRecCount(){
		  return recCount;
	  }
	  
	  private void markFull(){
		  isFull = true;
	  }
	  
	  public boolean isFull(){
		  return isFull;
	  }
	  
	  public void markRecord(int keyStart, int valStart){
		  bufMark = bufUsed;
		  kvRec[recCount * ACCTSIZE] = keyStart;
		  kvRec[recCount * ACCTSIZE + 1] = valStart;
		  recCount++;
	  }
	  
	  public void resetRecord(){
		  bufUsed = bufMark;
	  }
	  
	  public boolean isOld(){
		  return isOld;
	  }
	  
	  public void setOld(){
		  isOld = true;
	  }
	  
	  public void reset(){
		  bufUsed = 0;
		  bufMark = 0;
		  isFull = false;
		  recCount = 0;
		  isOld = false;
	  }
	  
	  public int getOffset(){
		  return bufUsed;
	  }
	  
	  public int getBufSize(){
		  return bufSize;
	  }
	  
	  public void writeBlockBuffer(DataInputBuffer key, DataInputBuffer value, Writer writer) throws IOException{
		  int kvoff;
		  for(int i = 0; i < recCount-1; i++){
			  kvoff = i * ACCTSIZE;
			  key.reset(kvBuffer, 
					  kvRec[kvoff + KEYSTART], 
					  kvRec[kvoff + VALSTART] - kvRec[kvoff + KEYSTART]);
			  value.reset(kvBuffer, 
					  kvRec[kvoff + VALSTART], 
					  kvRec[kvoff + ACCTSIZE + KEYSTART] - kvRec[kvoff + VALSTART]);
			  writer.append(key, value);
		  }
		  
		  //handle the last one
		  kvoff = (recCount - 1) * ACCTSIZE;
		  key.reset(kvBuffer, 
				  kvRec[kvoff + KEYSTART], 
				  kvRec[kvoff + VALSTART] - kvRec[kvoff + KEYSTART]);
		  value.reset(kvBuffer, 
				  kvRec[kvoff + VALSTART], 
				  bufUsed - kvRec[kvoff + VALSTART]);
		  writer.append(key, value);
	  }
	  
	  
	  private class DataBuffer extends OutputStream {
		  private final byte[] scratch = new byte[1];
		  
		  @Override
		  public synchronized void write(int v)
		  			throws IOException {
			  scratch[0] = (byte)v;
			  write(scratch, 0, 1);
		  }
		  
		  @Override
		  public synchronized void write(byte b[], int off, int len)
				  	throws IOException {
			  if( (bufSize - bufUsed) < len || (recCount >= recSize)){
				  markFull();
				  return;
			  }
			  System.arraycopy(b, off, kvBuffer, bufUsed, len);
			  bufUsed += len;
		  }
	  }
  }
  
  
  private static class SpillIndex{
	  
	  /** Backing store */
	  private final ByteBuffer buf;
	  /** View of backing storage as longs*/
	  private final LongBuffer entries;
	  
	  public SpillIndex (int Partitions){
		  buf = ByteBuffer.allocate(
				  Partitions * MapTask.OUTPUT_NOSORT_INDEX_RECORD_LENGTH);
		  entries = buf.asLongBuffer();
	  }
	  
	  public SpillIndex (Path indexFileName, JobConf job) throws IOException{
		  this(indexFileName, job, new CRC32());
	  }
	  
	  public SpillIndex (Path indexFileName, JobConf job, Checksum crc)
	  		throws IOException {
		  final FileSystem rfs = FileSystem.getLocal(job).getRaw();
		  final FSDataInputStream in = rfs.open(indexFileName);
		  try{
			  final long length = rfs.getFileStatus(indexFileName).getLen();
			  final int partitions = (int) length / OUTPUT_NOSORT_INDEX_RECORD_LENGTH;
			  final int size = partitions * OUTPUT_NOSORT_INDEX_RECORD_LENGTH;
			  
			  buf  = ByteBuffer.allocate(size);
			  if(crc != null){
				  crc.reset();
				  CheckedInputStream chk = new CheckedInputStream(in, crc);
				  IOUtils.readFully(chk, buf.array(), 0, size);
				  if(chk.getChecksum().getValue() != in.readLong()){
					  throw new ChecksumException("Checksum error reading spill index: " +
							  					indexFileName, -1);
				  }
			  }else{
				  IOUtils.readFully(in, buf.array(), 0, size);
			  }
			  entries = buf.asLongBuffer();
		  }finally{
			  in.close();
		  }
	  }
	  
	  public int size() {
		  return entries.capacity() / (MapTask.OUTPUT_NOSORT_INDEX_RECORD_LENGTH / 8);
	  }
	  
	  public PartRecord getPartition(int partNum){
		  final int pos = partNum * MapTask.OUTPUT_NOSORT_INDEX_RECORD_LENGTH / 8;
		  return new PartRecord(entries.get(pos), entries.get(pos + 1),
				  					entries.get(pos + 2));
	  }
	  
	  public void setPartition(PartRecord partRecord, int partNum){
		  final int pos = partNum * MapTask.OUTPUT_NOSORT_INDEX_RECORD_LENGTH / 8;
		  entries.put(pos, partRecord.startOffset);
		  entries.put(pos + 1, partRecord.rawLength);
		  entries.put(pos + 2, partRecord.partLength);
	  }
	  
	  public void writeToFile(Path loc, JobConf job)
	  		throws IOException {
		  writeToFile(loc, job, new CRC32());
	  }
	  
	  private void writeToFile(Path loc, JobConf job, Checksum crc)
	  			throws IOException {
		  final FileSystem rfs = FileSystem.getLocal(job).getRaw();
		  CheckedOutputStream chk = null;
		  final FSDataOutputStream out = rfs.create(loc);
		  try{
			  if(crc != null){
				  crc.reset();
				  chk = new CheckedOutputStream(out, crc);
				  chk.write(buf.array());
				  out.writeLong(chk.getChecksum().getValue());
			  }else{
				  out.write(buf.array());
			  }
		  }finally{
			  if(chk != null){
				  chk.close();
			  }else{
				  out.close();
			  }
		  }
	  }
  }
  
  
  private static class PartRecord{
	  long startOffset;
	  long rawLength;
	  long partLength;
	  
	  public PartRecord(){
		  
	  }
	  
	  public PartRecord(long startOffset, long rawLength, long partLength){
		  this.startOffset = startOffset;
		  this.rawLength = rawLength;
		  this.partLength = partLength;
	  }
  }
  
  /**
   * Exception indicating that the allocated sort buffer is insufficient
   * to hold the current record.
   */
  @SuppressWarnings("serial")
  private static class MapBufferTooSmallException extends IOException {
    public MapBufferTooSmallException(String s) {
      super(s);
    }
  }

}
