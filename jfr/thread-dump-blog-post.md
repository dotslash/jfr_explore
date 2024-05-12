In this blog post, we will discuss how lock contention can be observed in java thread dumps. As part of the process, we will
also describe one mechanism to emit JFRs for the java process.

# TL;DR

We can use thread dumps to understand observe lock contention in java. `Lock` objects make it possible to understand
which threads are waiting for lock. `synchronized` blocks go one step further and also tell the thread that is holding
the lock.

# Setup

### Options for locking in Java

In java there are 2 options to do locking 1) synchronized blocks and 2) Locks. 

Locks are more powerful
1. they allow us to diffrentiate between shared and exclusive locks
2. they allow us to try acquiring the lock with a
timeout. This can be crucial in production environments to sensibly handle contentions.

The following code snippet shows how to use synchronized blocks and locks in Java.

```java
public void useSync() throws InterruptedException {
  synchronized (this) {
    logger.info("useSync: success");
    work();
  }
}

public void useLock() throws InterruptedException {
  lock.lock();
  logger.info("useLock: success");
  try {
    work();
  } finally {
    lock.unlock();
  }
}

public void useTryLock() throws InterruptedException {
  if (!lock.tryLock(100, TimeUnit.SECONDS)) {
    logger.info("useTryLock: failed to acquire lock");
    return;
  }
  logger.info("useTryLock: success");
  try {
    work();
  } finally {
    lock.unlock();
  }
}
```

### Thread dumps

A thread dump is snapshot of the state of all threads in a java process. A simple way to get a thread dump is to use
the `jstack` command. In this blog post we will not use `jstack` and instead leverage the Java Flight Recorder (jfr) to get a thread dump.

JFR is built into the JVM and can be used collect various metrics about the JVM. The event of interest for us right now is the thread dump.

```shell
$ jfr summary jfr/lock-1715525551030.jfr

 Version: 2.0
 Chunks: 1
 Start: 2024-05-12 14:52:31 (UTC)
 Duration: 10 s

 Event Type                                   Count  Size (bytes)
==================================================================
 jdk.ModuleExport                               797         10963
 jdk.SystemProcess                              767         78819
 jdk.NativeLibrary                              660         57900
 jdk.BooleanFlag                                640         21507
 jdk.NativeMethodSample                         420          5880
 jdk.JavaMonitorWait                            412         11124
 jdk.ActiveSetting                              301          9801
 jdk.LongFlag                                   229          8441
 jdk.UnsignedLongFlag                           182          6811
 ...
 jdk.ThreadDump                                  10        150329
 ...
```

The reason we pick JFRs over `jstack` 1) JFRs are more detailed 2) JFRs can be collected automatically from the JVM.
The following code snippet shows how to emit JFRs from your java process. `runProgramWithJFR` is just a wrapper around
`runProgram` that emits a JFR file for the duration of the `runProgram` method.


```java
void runProgramWithJFR(String mode) {
  String jfrPathString = MessageFormat.format(
      "jfr/{0}-{1,number,#}.jfr", 
      mode, 
      System.currentTimeMillis()
  )
  Path recordingPath = Paths.get(jfrPathString);
  logger.info("Running program with mode: " + mode);
  logger.info("Recording path: " + recordingPath);
  try (Recording recording = new Recording(Configuration.getConfiguration("default"))) {
    // Take a thread dump every second
    recording.enable("jdk.ThreadDump").withPeriod(Duration.ofSeconds(1));
    recording.start();  // Start the recording

    runProgram(mode); // === Actual code execution ===

    recording.stop();  // Stop the recording

    // Save the recording to a file
    recording.dump(recordingPath);
    logger.info("JFR recording saved to " + recordingPath);
  } catch (Exception e) {
    e.printStackTrace();
  }
```

### More boiler plate

The missing pieces in our program so far are
1. The `work` method that is invoked by the `useTryLock`, `useLock` and `useSync` methods. We implement it here as a
   simple 1sec sleep
2. The `runProgram` method that runs the program with the specified mode. It creates an ExecutorService with 10 threads
   and all the threads invoke the `useTryLock`, `useLock` or `useSync` method based on the `mode`.

```java
void work() throws InterruptedException {
  Thread.sleep(1000);
}

void runProgram(String mode) {
  ExecutorService executor = Executors.newFixedThreadPool(10);
  for (int i = 0; i < 10; i++) {
    executor.execute(() -> {
      try {
        if (mode.equals("tryLock")) {
          useTryLock();
        } else if (mode.equals("lock")) {
          useLock();
        } else {
          useSync();
        }
      } catch (InterruptedException e) {
        e.printStackTrace();
      }
    });
  }
  executor.shutdown();
  try {
    // Wait for all tasks to finish
    if (!executor.awaitTermination(60, TimeUnit.SECONDS)) {
      executor.shutdownNow();  // Force shutdown if tasks exceed timeout
    }
  } catch (InterruptedException e) {
    executor.shutdownNow();
    Thread.currentThread().interrupt();
  }
}
```

Overall when runProgramWithJFR is invoked, the following happens
1. We spwan 10 tasks competing for the same lock. Each task holds the lock for 1sec. `runProgram` method ends when all
   the tasks have finished. So `runProgram` method runs for ~10 secs
2. We will have timestamped JFR file for this ~10 secs.
3. And we setup JFRs to take a thread dump every second => We will have 10 thread dumps in each JFR file.

# Analyzing thread dumps

Extracting thread dumps from JFRs

```shell
function jfr_thread_dump() {
  inp_file=$1
  jfr print --events jdk.ThreadDump $inp_file > ${inp_file}_thread_dump.txt 
}

# Extract thread dump into lock-1715525551030.jfr_thread_dump.txt
$ jfr_thread_dump lock-1715525551030.jfr
```


The summary is that
1. All the 3 mechanisms will show light on the threads that are blocked on a given lock
2. Synchronzed though goes one step further and also tells the thread that is holding the lock

The thread entries in the thread dump will look like this. For more details checkout 
[lock-1715525551030.jfr_thread_dump.txt](lock-1715525551030.jfr_thread_dump.txt), 
[tryLock-1715525540531.jfr_thread_dump.txt](tryLock-1715525540531.jfr_thread_dump.txt) and 
[synchronized-1715525561096.jfr_thread_dump.txt](synchronized-1715525561096.jfr_thread_dump.txt)

```
---------------------------lock-----------------------------------
# Blocked thread looks like this for lock
java.lang.Thread.State: WAITING (parking)
at jdk.internal.misc.Unsafe.park(java.base@11.0.12/Native Method)
- parking to wait for  <0x00000003ff83d670> (a java.util.concurrent.locks.ReentrantLock$NonfairSync)

---------------------------tryLock-----------------------------------
# Blocked thread looks like this for tryLock
java.lang.Thread.State: TIMED_WAITING (parking)
at jdk.internal.misc.Unsafe.park(java.base@11.0.12/Native Method)
- parking to wait for  <0x00000003ff83d670> (a java.util.concurrent.locks.ReentrantLock$NonfairSync)

---------------------------synchronized-----------------------------------
# Blocked thread looks like this for synchronized
java.lang.Thread.State: BLOCKED (on object monitor)
at io.github.dotslash.Main.useSync(Main.java:36)
- waiting to lock <0x00000003ff83d4e8> (a io.github.dotslash.Main)

# Thread holding the lock will look like this for 
java.lang.Thread.State: TIMED_WAITING (sleeping)
at java.lang.Thread.sleep(java.base@11.0.12/Native Method)
at io.github.dotslash.Main.work(Main.java:21)
at io.github.dotslash.Main.useSync(Main.java:37)
- locked <0x00000003ff83d4e8> (a io.github.dotslash.Main)
```

You can use grep to estimate the extent of the lock contention

```
$ grep 0x00000003ff83d670 lock-1715525551030.jfr_thread_dump.txt  | sort | uniq -c
  45 	- parking to wait for  <0x00000003ff83d670> (a java.util.concurrent.locks.ReentrantLock$NonfairSync)
$ grep 0x00000003ff83d670 tryLock-1715525540531.jfr_thread_dump.txt  | sort | uniq -c
  45 	- parking to wait for  <0x00000003ff83d670> (a java.util.concurrent.locks.ReentrantLock$NonfairSync)
$ grep 0x00000003ff83d4e8 synchronized-1715525561096.jfr_thread_dump.txt  | sort | uniq -c
   4 	- locked <0x00000003ff83d4e8> (a io.github.dotslash.Main)
  30 	- waiting to lock <0x00000003ff83d4e8> (a io.github.dotslash.Main)
```





