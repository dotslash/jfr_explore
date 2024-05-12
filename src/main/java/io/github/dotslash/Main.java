package io.github.dotslash;

import jdk.jfr.Configuration;
import jdk.jfr.Recording;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.text.MessageFormat;
import java.time.Duration;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;
import java.util.logging.Level;
import java.util.logging.Logger;

public class Main {
  Lock lock = new ReentrantLock();
  private static final Logger logger = Logger.getLogger("Main");

  public void work() throws InterruptedException {
    Thread.sleep(1000);
  }

  public void useSync() throws InterruptedException {
    synchronized (this) {
      logger.info("useSync: success");
      work();
    }
  }

  public void useLock() throws InterruptedException {
    lock.lock();
    logger.info("useSync: success");
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

  void runProgramWithJFR(String mode) {
    Path recordingPath = Paths.get(MessageFormat.format("jfr/{0}-{1,number,#}.jfr", mode, System.currentTimeMillis()));
    logger.info("Running program with mode: " + mode);
    logger.info("Recording path: " + recordingPath);
    try (Recording recording = new Recording(Configuration.getConfiguration("default"))) {
      // Take a thread dump every second
      recording.enable("jdk.ThreadDump").withPeriod(Duration.ofSeconds(1));
      recording.start();  // Start the recording

      runProgram(mode); // Actual code execution

      recording.stop();  // Stop the recording

      // Save the recording to a file
      recording.dump(recordingPath);
      logger.info("JFR recording saved to " + recordingPath);
    } catch (Exception e) {
      e.printStackTrace();
    }
  }

  public static void main(String[] args) {
    Main main = new Main();
    main.runProgramWithJFR("tryLock");
    main.runProgramWithJFR("lock");
    main.runProgramWithJFR("synchronized");
  }


  public void runProgram(String mode) {
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
}