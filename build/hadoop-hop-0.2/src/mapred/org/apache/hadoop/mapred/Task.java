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

import java.io.DataInput;
import java.io.DataOutput;
import java.io.IOException;
import java.text.NumberFormat;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.hadoop.conf.Configurable;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.hdfs.DistributedFileSystem;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.LocalDirAllocator;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.fs.RawLocalFileSystem;
import org.apache.hadoop.fs.kfs.KosmosFileSystem;
import org.apache.hadoop.fs.s3.S3FileSystem;
import org.apache.hadoop.io.RawComparator;
import org.apache.hadoop.io.Text;
import org.apache.hadoop.io.Writable;
import org.apache.hadoop.mapred.IFile.Writer;
import org.apache.hadoop.mapred.buffer.BufferUmbilicalProtocol;
import org.apache.hadoop.mapred.buffer.impl.JSnapshotBuffer;
import org.apache.hadoop.mapred.buffer.impl.ValuesIterator;
import org.apache.hadoop.net.NetUtils;
import org.apache.hadoop.util.Progress;
import org.apache.hadoop.util.ReflectionUtils;
import org.apache.hadoop.util.StringUtils;

/** Base class for tasks. */
public abstract class Task implements Writable, Configurable {
  private static final Log LOG =
    LogFactory.getLog("org.apache.hadoop.mapred.TaskRunner");

  // Counters used by Task subclasses
  public static enum Counter { 
    MAP_INPUT_RECORDS, 
    MAP_OUTPUT_RECORDS,
    MAP_SKIPPED_RECORDS,
    MAP_INPUT_BYTES, 
    MAP_OUTPUT_BYTES,
    COMBINE_INPUT_RECORDS,
    COMBINE_OUTPUT_RECORDS,
    REDUCE_INPUT_GROUPS,
    REDUCE_INPUT_RECORDS,
    REDUCE_OUTPUT_RECORDS,
    REDUCE_SKIPPED_GROUPS,
    REDUCE_SKIPPED_RECORDS,
    SPILLED_RECORDS
  }
  
  /**
   * Counters to measure the usage of the different file systems.
   */
  protected static enum FileSystemCounter {
    LOCAL_READ, LOCAL_WRITE, 
    HDFS_READ, HDFS_WRITE, 
    S3_READ, S3_WRITE,
    KFS_READ, KFSWRITE
  }

  ///////////////////////////////////////////////////////////
  // Helper methods to construct task-output paths
  ///////////////////////////////////////////////////////////
  
  /** Construct output file names so that, when an output directory listing is
   * sorted lexicographically, positions correspond to output partitions.*/
  private static final NumberFormat NUMBER_FORMAT = NumberFormat.getInstance();
  static {
    NUMBER_FORMAT.setMinimumIntegerDigits(5);
    NUMBER_FORMAT.setGroupingUsed(false);
  }

  static synchronized String getOutputName(int partition) {
    return "part-" + NUMBER_FORMAT.format(partition);
  }

  static synchronized String getSnapshotOutputName(int partition, float progress) {
    return "snapshot-" + NUMBER_FORMAT.format(progress) + "-" + NUMBER_FORMAT.format(partition); 
  }
  
  ////////////////////////////////////////////
  // Fields
  ////////////////////////////////////////////

  private String jobFile;                         // job configuration file
  private TaskAttemptID taskId;                          // unique, includes job id
  private int partition;                          // id within job
  TaskStatus taskStatus;                          // current status of the task
  protected boolean jobCleanup = false;
  protected boolean jobSetup = false;
  protected boolean taskCleanup = false;
  private String pidFile = "";
  private Thread pingProgressThread;
  
  //skip ranges based on failed ranges from previous attempts
  private SortedRanges skipRanges = new SortedRanges();
  private boolean skipping = false;
  private boolean writeSkipRecs = true;
  
  //currently processing record start index
  private volatile long currentRecStartIndex; 
  private Iterator<Long> currentRecIndexIterator = 
    skipRanges.skipRangeIterator();
  
  protected JobConf conf;
  protected FileHandle mapOutputFile = new FileHandle();
  protected LocalDirAllocator lDirAlloc;
  private final static int MAX_RETRIES = 10;
  protected JobContext jobContext;
  protected TaskAttemptContext taskContext;

  ////////////////////////////////////////////
  // Constructors
  ////////////////////////////////////////////

  public Task() {
    taskStatus = TaskStatus.createTaskStatus(isMapTask());
  }

  public Task(String jobFile, TaskAttemptID taskId, int partition) {
    this.jobFile = jobFile;
    this.taskId = taskId;
     
    this.partition = partition;
    this.taskStatus = TaskStatus.createTaskStatus(isMapTask(), this.taskId, 
                                                  0.0f, 
                                                  TaskStatus.State.UNASSIGNED, 
                                                  "", "", "", 
                                                  isMapTask() ? 
                                                    TaskStatus.Phase.MAP : 
                                                    TaskStatus.Phase.SHUFFLE, 
                                                  counters);
    this.mapOutputFile.setJobId(taskId.getJobID());
  }

  ////////////////////////////////////////////
  // Accessors
  ////////////////////////////////////////////
  public void setJobFile(String jobFile) { this.jobFile = jobFile; }
  public String getJobFile() { return jobFile; }
  public TaskAttemptID getTaskID() { return taskId; }
  public Counters getCounters() { return counters; }
  public void setPidFile(String pidFile) { 
    this.pidFile = pidFile; 
  }
  public String getPidFile() { 
    return pidFile; 
  }  
  /**
   * Get the job name for this task.
   * @return the job name
   */
  public JobID getJobID() {
    return taskId.getJobID();
  }
  
  /**
   * Get the index of this task within the job.
   * @return the integer part of the task id
   */
  public int getPartition() {
    return partition;
  }
  /**
   * Return current phase of the task. 
   * needs to be synchronized as communication thread sends the phase every second
   * @return
   */
  public synchronized TaskStatus.Phase getPhase(){
    return this.taskStatus.getPhase(); 
  }
  /**
   * Set current phase of the task. 
   * @param p
   */
  protected synchronized void setPhase(TaskStatus.Phase phase){
    this.taskStatus.setPhase(phase); 
  }
  
  /**
   * Get whether to write skip records.
   */
  protected boolean toWriteSkipRecs() {
    return writeSkipRecs;
  }
      
  /**
   * Set whether to write skip records.
   */
  protected void setWriteSkipRecs(boolean writeSkipRecs) {
    this.writeSkipRecs = writeSkipRecs;
  }
  
  /**
   * Get skipRanges.
   */
  public SortedRanges getSkipRanges() {
    return skipRanges;
  }

  /**
   * Set skipRanges.
   */
  public void setSkipRanges(SortedRanges skipRanges) {
    this.skipRanges = skipRanges;
  }

  /**
   * Is Task in skipping mode.
   */
  public boolean isSkipping() {
    return skipping;
  }

  /**
   * Sets whether to run Task in skipping mode.
   * @param skipping
   */
  public void setSkipping(boolean skipping) {
    this.skipping = skipping;
  }

  /**
   * Return current state of the task. 
   * needs to be synchronized as communication thread 
   * sends the state every second
   * @return
   */
  synchronized TaskStatus.State getState(){
    return this.taskStatus.getRunState(); 
  }
  /**
   * Set current state of the task. 
   * @param state
   */
  synchronized void setState(TaskStatus.State state){
    this.taskStatus.setRunState(state); 
  }

  void setTaskCleanupTask() {
    taskCleanup = true;
  }
	   
  boolean isTaskCleanupTask() {
    return taskCleanup;
  }

  boolean isJobCleanupTask() {
    return jobCleanup;
  }

  boolean isJobSetupTask() {
    return jobSetup;
  }

  void setJobSetupTask() {
    jobSetup = true; 
  }

  void setJobCleanupTask() {
    jobCleanup = true; 
  }

  boolean isMapOrReduce() {
    return !jobSetup && !jobCleanup && !taskCleanup;
  }
  
  ////////////////////////////////////////////
  // Writable methods
  ////////////////////////////////////////////

  public void write(DataOutput out) throws IOException {
    Text.writeString(out, jobFile);
    taskId.write(out);
    out.writeInt(partition);
    taskStatus.write(out);
    skipRanges.write(out);
    out.writeBoolean(skipping);
    out.writeBoolean(jobCleanup);
    out.writeBoolean(jobSetup);
    out.writeBoolean(writeSkipRecs);
    out.writeBoolean(taskCleanup);  
    Text.writeString(out, pidFile);
  }
  
  public void readFields(DataInput in) throws IOException {
    jobFile = Text.readString(in);
    taskId = TaskAttemptID.read(in);
    partition = in.readInt();
    taskStatus.readFields(in);
    this.mapOutputFile.setJobId(taskId.getJobID()); 
    skipRanges.readFields(in);
    currentRecIndexIterator = skipRanges.skipRangeIterator();
    currentRecStartIndex = currentRecIndexIterator.next();
    skipping = in.readBoolean();
    jobCleanup = in.readBoolean();
    jobSetup = in.readBoolean();
    writeSkipRecs = in.readBoolean();
    taskCleanup = in.readBoolean();
    if (taskCleanup) {
      setPhase(TaskStatus.Phase.CLEANUP);
    }
    pidFile = Text.readString(in);
  }

  @Override
  public String toString() { return taskId.toString(); }

  /**
   * Localize the given JobConf to be specific for this task.
   */
  public void localizeConfiguration(JobConf conf) throws IOException {
    conf.set("mapred.tip.id", taskId.getTaskID().toString()); 
    conf.set("mapred.task.id", taskId.toString());
    conf.setBoolean("mapred.task.is.map", isMapTask());
    conf.setInt("mapred.task.partition", partition);
    conf.set("mapred.job.id", taskId.getJobID().toString());
  }
  
  /** Run this task as a part of the named job.  This method is executed in the
   * child process and is what invokes user-supplied map, reduce, etc. methods.
   * @param umbilical for progress reports
   */
  public abstract void run(JobConf job, TaskUmbilicalProtocol umbilical, final BufferUmbilicalProtocol bufferUmbilical)
    throws IOException;


  /** Return an approprate thread runner for this task. 
   * @param tip TODO*/
  public abstract TaskRunner createRunner(TaskTracker tracker, 
      TaskTracker.TaskInProgress tip) throws IOException;

  /** The number of milliseconds between progress reports. */
  public static final int PROGRESS_INTERVAL = 3000;

  private transient Progress taskProgress = new Progress();

  // Current counters
  private transient Counters counters = new Counters();
  
  /**
   * flag that indicates whether progress update needs to be sent to parent.
   * If true, it has been set. If false, it has been reset. 
   * Using AtomicBoolean since we need an atomic read & reset method. 
   */  
  private AtomicBoolean progressFlag = new AtomicBoolean(false);
  /* flag to track whether task is done */
  private AtomicBoolean taskDone = new AtomicBoolean(false);
  // getters and setters for flag
  protected void setProgressFlag() {
    progressFlag.set(true);
  }
  private boolean resetProgressFlag() {
    return progressFlag.getAndSet(false);
  }
  
  public abstract boolean isMapTask();
  
  public boolean isPipeline() {
	  return false;
  }
  
  public int getNumberOfInputs() {
	  return 0;
  }
  
  public Progress getProgress() { return taskProgress; }
  
  public void setProgress(float progress) {
	    taskProgress.set(progress);
	    // indicate that progress update needs to be sent
	    setProgressFlag();
	  }
  
  InputSplit getInputSplit() throws UnsupportedOperationException {
    throw new UnsupportedOperationException("Input only available on map");
  }

  /** 
   * The communication thread handles communication with the parent (Task Tracker). 
   * It sends progress updates if progress has been made or if the task needs to 
   * let the parent know that it's alive. It also pings the parent to see if it's alive. 
   */
  protected void startCommunicationThread(final TaskUmbilicalProtocol umbilical) {
    pingProgressThread = new Thread(new Runnable() {
        public void run() {
          final int MAX_RETRIES = 3;
          int remainingRetries = MAX_RETRIES;
          // get current flag value and reset it as well
          boolean sendProgress = resetProgressFlag();
          while (!taskDone.get()) {
            try {
              boolean taskFound = true; // whether TT knows about this task
              // sleep for a bit
              try {
                Thread.sleep(PROGRESS_INTERVAL);
              } 
              catch (InterruptedException e) {
                LOG.debug(getTaskID() + " Progress/ping thread exiting " +
                                        "since it got interrupted");
                break;
              }
              
              if (sendProgress) {
                // we need to send progress update
                updateCounters();
                taskStatus.statusUpdate(taskProgress.get(),
                                        taskProgress.toString(), 
                                        counters);
                long begin = System.currentTimeMillis();
                taskFound = umbilical.statusUpdate(taskId, taskStatus);
                long duration = System.currentTimeMillis() - begin;
                if (duration > 1000) {
                	LOG.debug("Status update took " + duration + " ms!");
                }
                taskStatus.clearStatus();
              }
              else {
                // send ping 
            	  long begin = System.currentTimeMillis();
                taskFound = umbilical.ping(taskId);
                long duration = System.currentTimeMillis() - begin;
                if (duration > 1000) {
                	LOG.debug("ping took " + duration + " ms!");
                }
              }
              
              // if Task Tracker is not aware of our task ID (probably because it died and 
              // came back up), kill ourselves
              if (!taskFound) {
                LOG.warn("Parent died.  Exiting "+taskId);
                System.exit(66);
              }
              
              sendProgress = resetProgressFlag(); 
              remainingRetries = MAX_RETRIES;
            } 
            catch (Throwable t) {
              LOG.info("Communication exception: " + StringUtils.stringifyException(t));
              remainingRetries -=1;
              if (remainingRetries == 0) {
                ReflectionUtils.logThreadInfo(LOG, "Communication exception", 0);
                LOG.warn("Last retry, killing "+taskId);
                System.exit(65);
              }
            }
          }
        }
      }, "Comm thread for "+taskId);
    pingProgressThread.setDaemon(true);
    pingProgressThread.start();
    LOG.debug(getTaskID() + " Progress/ping thread started");
  }

  public void initialize(JobConf job, Reporter reporter) 
  throws IOException {
    jobContext = new JobContext(job, reporter);
    taskContext = new TaskAttemptContext(job, taskId, reporter);
    if (getState() == TaskStatus.State.UNASSIGNED) {
      setState(TaskStatus.State.RUNNING);
    }
    OutputCommitter committer = conf.getOutputCommitter();
    Path outputPath = FileOutputFormat.getOutputPath(conf);
    if (outputPath != null) {
      if ((committer instanceof FileOutputCommitter)) {
        FileOutputFormat.setWorkOutputPath(conf, 
          ((FileOutputCommitter)committer).getTempTaskOutputPath(taskContext));
      } else {
        FileOutputFormat.setWorkOutputPath(conf, outputPath);
      }
    }
    committer.setupTask(taskContext);
  }
  
  protected Reporter getReporter(final TaskUmbilicalProtocol umbilical) 
    throws IOException 
  {
    return new Reporter() {
        public void setStatus(String status) {
          taskProgress.setStatus(status);
          // indicate that progress update needs to be sent
          setProgressFlag();
        }
        public void progress() {
          // indicate that progress update needs to be sent
          setProgressFlag();
        }
        public Counters.Counter getCounter(String group, String name) {
          Counters.Counter counter = null;
          if (counters != null) {
            counter = counters.findCounter(group, name);
          }
          return counter;
        }
        public void incrCounter(Enum key, long amount) {
          if (counters != null) {
            counters.incrCounter(key, amount);
          }
          setProgressFlag();
        }
        public void incrCounter(String group, String counter, long amount) {
          if (counters != null) {
            counters.incrCounter(group, counter, amount);
          }
          if(skipping && SkipBadRecords.COUNTER_GROUP.equals(group) && (
              SkipBadRecords.COUNTER_MAP_PROCESSED_RECORDS.equals(counter) ||
              SkipBadRecords.COUNTER_REDUCE_PROCESSED_GROUPS.equals(counter))) {
            //if application reports the processed records, move the 
            //currentRecStartIndex to the next.
            //currentRecStartIndex is the start index which has not yet been 
            //finished and is still in task's stomach.
            for(int i=0;i<amount;i++) {
              currentRecStartIndex = currentRecIndexIterator.next();
            }
          }
          setProgressFlag();
        }
        public InputSplit getInputSplit() throws UnsupportedOperationException {
          return Task.this.getInputSplit();
        }
      };
  }
  
  /**
   *  Reports the next executing record range to TaskTracker.
   *  
   * @param umbilical
   * @param nextRecIndex the record index which would be fed next.
   * @throws IOException
   */
  protected void reportNextRecordRange(final TaskUmbilicalProtocol umbilical, 
      long nextRecIndex) throws IOException{
    //currentRecStartIndex is the start index which has not yet been finished 
    //and is still in task's stomach.
    long len = nextRecIndex - currentRecStartIndex +1;
    SortedRanges.Range range = 
      new SortedRanges.Range(currentRecStartIndex, len);
    taskStatus.setNextRecordRange(range);
    LOG.debug("sending reportNextRecordRange " + range);
    long begin = System.currentTimeMillis();
    umbilical.reportNextRecordRange(taskId, range);
    long duration = System.currentTimeMillis() - begin;
    if (duration > 1000) {
    	LOG.debug("report next record range took " + duration + " ms.");
    }
  }



  /**
   * An updater that tracks the last number reported for a given file
   * system and only creates the counters when they are needed.
   */
  class FileSystemStatisticUpdater {
    private long prevReadBytes = 0;
    private long prevWriteBytes = 0;
    private FileSystem.Statistics stats;
    private Counters.Counter readCounter = null;
    private Counters.Counter writeCounter = null;
    private FileSystemCounter read;
    private FileSystemCounter write;

    FileSystemStatisticUpdater(FileSystemCounter read,
                               FileSystemCounter write,
                               Class<? extends FileSystem> cls) {
      stats = FileSystem.getStatistics(cls);
      this.read = read;
      this.write = write;
    }

    void updateCounters() {
      long newReadBytes = stats.getBytesRead();
      long newWriteBytes = stats.getBytesWritten();
      if (prevReadBytes != newReadBytes) {
        if (readCounter == null) {
          readCounter = counters.findCounter(read);
        }
        readCounter.increment(newReadBytes - prevReadBytes);
        prevReadBytes = newReadBytes;
      }
      if (prevWriteBytes != newWriteBytes) {
        if (writeCounter == null) {
          writeCounter = counters.findCounter(write);
        }
        writeCounter.increment(newWriteBytes - prevWriteBytes);
        prevWriteBytes = newWriteBytes;
      }
    }
  }
  
  /**
   * A list of all of the file systems that we want to report on.
   */
  private List<FileSystemStatisticUpdater> statisticUpdaters =
     new ArrayList<FileSystemStatisticUpdater>();
  {
    statisticUpdaters.add
      (new FileSystemStatisticUpdater(FileSystemCounter.LOCAL_READ,
                                      FileSystemCounter.LOCAL_WRITE,
                                      RawLocalFileSystem.class));
    statisticUpdaters.add
      (new FileSystemStatisticUpdater(FileSystemCounter.HDFS_READ,
                                      FileSystemCounter.HDFS_WRITE,
                                      DistributedFileSystem.class));
    statisticUpdaters.add
    (new FileSystemStatisticUpdater(FileSystemCounter.KFS_READ,
                                    FileSystemCounter.KFSWRITE,
                                    KosmosFileSystem.class));
    statisticUpdaters.add
    (new FileSystemStatisticUpdater(FileSystemCounter.S3_READ,
                                    FileSystemCounter.S3_WRITE,
                                    S3FileSystem.class));
  }

  private synchronized void updateCounters() {
    for(FileSystemStatisticUpdater updater: statisticUpdaters) {
      updater.updateCounters();
    }
  }

  public void done(TaskUmbilicalProtocol umbilical) throws IOException {
    LOG.info("Task:" + taskId + " is done."
             + " And is in the process of commiting");
    long begin = System.currentTimeMillis();
    updateCounters();

    OutputCommitter outputCommitter = conf.getOutputCommitter();
    // check whether the commit is required.
    boolean commitRequired = outputCommitter.needsTaskCommit(taskContext);
    if (!isPipeline() && commitRequired) {
      int retries = MAX_RETRIES;
      setState(TaskStatus.State.COMMIT_PENDING);
      // say the task tracker that task is commit pending
      while (true) {
        try {
          umbilical.commitPending(taskId, taskStatus);
          break;
        } catch (InterruptedException ie) {
          // ignore
        } catch (IOException ie) {
          LOG.warn("Failure sending commit pending: " + 
                    StringUtils.stringifyException(ie));
          if (--retries == 0) {
            System.exit(67);
          }
        }
      }
      //wait for commit approval and commit
      commit(umbilical, outputCommitter);
    }
    taskDone.set(true);
    pingProgressThread.interrupt();
    try {
      pingProgressThread.join();
    } catch (InterruptedException ie) {}
    sendLastUpdate(umbilical);
    //signal the tasktracker that we are done
    sendDone(umbilical);
  }

  protected void statusUpdate(TaskUmbilicalProtocol umbilical) 
  throws IOException {
    int retries = MAX_RETRIES;
    long begin = System.currentTimeMillis();
    while (true) {
      try {
        if (!umbilical.statusUpdate(getTaskID(), taskStatus)) {
          LOG.warn("Parent died.  Exiting "+taskId);
          System.exit(66);
        }
        long duration = System.currentTimeMillis() - begin;
        if (duration > 1000) {
        	LOG.debug("It took " + duration + " ms to update status!");
        }
        
        taskStatus.clearStatus();
        return;
      } catch (InterruptedException ie) {
        Thread.currentThread().interrupt(); // interrupt ourself
      } catch (IOException ie) {
        LOG.warn("Failure sending status update: " + 
                  StringUtils.stringifyException(ie));
        if (--retries == 0) {
          throw ie;
        }
      }
    }
  }
  
  private void sendLastUpdate(TaskUmbilicalProtocol umbilical) 
  throws IOException {
    // send a final status report
    taskStatus.statusUpdate(taskProgress.get(),
                            taskProgress.toString(), 
                            counters);
    statusUpdate(umbilical);
  }

  private void sendDone(TaskUmbilicalProtocol umbilical) throws IOException {
    int retries = MAX_RETRIES;
    while (true) {
      try {
    	  long begin = System.currentTimeMillis();
        umbilical.done(getTaskID());
        long duration = System.currentTimeMillis() - begin;
        if (duration > 1000) {
        	LOG.debug("It took " + duration + " ms just to call done!");
        }
        LOG.info("Task '" + taskId + "' done.");
        return;
      } catch (IOException ie) {
        LOG.warn("Failure signalling completion: " + 
                 StringUtils.stringifyException(ie));
        if (--retries == 0) {
          throw ie;
        }
      }
    }
  }

  private void commit(TaskUmbilicalProtocol umbilical,
                      OutputCommitter committer) throws IOException {
    int retries = MAX_RETRIES;
    while (true) {
      try {
        while (!umbilical.canCommit(taskId)) {
          try {
            Thread.sleep(1000);
          } catch(InterruptedException ie) {
            //ignore
          }
          setProgressFlag();
        }
        // task can Commit now  
        try {
          LOG.info("Task " + taskId + " is allowed to commit now");
          committer.commitTask(taskContext);
          return;
        } catch (IOException iee) {
          LOG.warn("Failure committing: " + 
                    StringUtils.stringifyException(iee));
          discardOutput(taskContext, committer);
          throw iee;
        }
      } catch (IOException ie) {
        LOG.warn("Failure asking whether task can commit: " + 
            StringUtils.stringifyException(ie));
        if (--retries == 0) {
          //if it couldn't commit a successfully then delete the output
          discardOutput(taskContext, committer);
          System.exit(68);
        }
      }
    }
  }

  private void discardOutput(TaskAttemptContext taskContext,
                             OutputCommitter committer) {
    try {
      committer.abortTask(taskContext);
    } catch (IOException ioe)  {
      LOG.warn("Failure cleaning up: " + 
               StringUtils.stringifyException(ioe));
    }
  }

  protected void runTaskCleanupTask(TaskUmbilicalProtocol umbilical) 
  throws IOException {
    taskCleanup(umbilical);
    done(umbilical);
  }

  void taskCleanup(TaskUmbilicalProtocol umbilical) 
  throws IOException {
    // set phase for this task
    setPhase(TaskStatus.Phase.CLEANUP);
    getProgress().setStatus("cleanup");
    statusUpdate(umbilical);
    LOG.info("Runnning cleanup for the task");
    // do the cleanup
    discardOutput(taskContext, conf.getOutputCommitter());
  }

  protected void runJobCleanupTask(TaskUmbilicalProtocol umbilical) 
  throws IOException {
    // set phase for this task
    setPhase(TaskStatus.Phase.CLEANUP);
    getProgress().setStatus("cleanup");
    // do the cleanup
    conf.getOutputCommitter().cleanupJob(jobContext);
    done(umbilical);
  }

  protected void runJobSetupTask(TaskUmbilicalProtocol umbilical) 
  throws IOException {
    // do the setup
    getProgress().setStatus("setup");
    conf.getOutputCommitter().setupJob(jobContext);
    done(umbilical);
  }
  
  public void setConf(Configuration conf) {
    if (conf instanceof JobConf) {
      this.conf = (JobConf) conf;
    } else {
      this.conf = new JobConf(conf);
    }
    this.mapOutputFile.setConf(this.conf);
    this.lDirAlloc = new LocalDirAllocator("mapred.local.dir");
    // add the static resolutions (this is required for the junit to
    // work on testcases that simulate multiple nodes on a single physical
    // node.
    String hostToResolved[] = conf.getStrings("hadoop.net.static.resolutions");
    if (hostToResolved != null) {
      for (String str : hostToResolved) {
        String name = str.substring(0, str.indexOf('='));
        String resolvedName = str.substring(str.indexOf('=') + 1);
        NetUtils.addStaticResolution(name, resolvedName);
      }
    }
  }

  public Configuration getConf() {
    return this.conf;
  }

  /**
   * OutputCollector for the combiner.
   */
  protected static class CombineOutputCollector<K extends Object, V extends Object> 
  implements OutputCollector<K, V> {
    private Writer<K, V> writer;
    private Counters.Counter outCounter;
    public CombineOutputCollector(Counters.Counter outCounter) {
      this.outCounter = outCounter;
    }
    public synchronized void setWriter(Writer<K, V> writer) {
      this.writer = writer;
    }
    public synchronized void collect(K key, V value)
        throws IOException {
      outCounter.increment(1);
  }
  }

  protected static class CombineValuesIterator<KEY,VALUE>
      extends ValuesIterator<KEY,VALUE> {

    private final Counters.Counter combineInputCounter;

    public CombineValuesIterator(RawKeyValueIterator in,
        RawComparator<KEY> comparator, Class<KEY> keyClass,
        Class<VALUE> valClass, Configuration conf, Reporter reporter,
        Counters.Counter combineInputCounter) throws IOException {
      super(in, comparator, keyClass, valClass, conf, reporter);
      this.combineInputCounter = combineInputCounter;
    }

    public VALUE next() {
      combineInputCounter.increment(1);
      return super.next();
    }
  }

}
