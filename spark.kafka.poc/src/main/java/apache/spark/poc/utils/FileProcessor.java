package apache.spark.poc.utils;

import java.io.BufferedInputStream;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.Closeable;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.stream.Stream;
import java.util.zip.GZIPInputStream;

import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.util.Progressable;
import org.apache.log4j.Logger;

/**
 * This class will be processing the files Primarily the following operations
 * will be supported 1. Read the file from NFS 2. Process the file for
 * deduplicated ( TBD ) 3. Dump the file into HDFS
 * 
 * @author abhay
 *
 */
public class FileProcessor {

  private static boolean isDebug = false;
  
  private static Logger logger = Logger.getLogger(FileProcessor.class);

  // TODO : Extract this HDFS specific items to a separate class

  private static FileSystem hdfs;

  static {

    try {

      String HDFS_INSTALL_LOCATION =
          apache.spark.poc.config.Configuration.HDFS_INSTALL_LOCATION;

      Configuration hdfsConfig = new Configuration();
      hdfsConfig.addResource(
          new Path(HDFS_INSTALL_LOCATION + "/etc/hadoop/core-site.xml"));
      hdfsConfig.addResource(
          new Path(HDFS_INSTALL_LOCATION + "/etc/hadoop/hdfs-site.xml"));
      hdfsConfig.addResource(
          new Path(HDFS_INSTALL_LOCATION + "/etc/hadoop/mapred-site.xml"));

      if (isDebug) {
        logger.info("Hadoop conf path : " + hdfsConfig.get("fs.default.name"));
        logger.info("Hadoop defaultFs path : " + hdfsConfig.get("fs.defaultFS"));
      }
      hdfs = FileSystem.get(hdfsConfig);
      logger.info("HDFS initialized successfully");

    } catch (IOException e) {
      logger.error("Could not init HDFS : " + e.getMessage());
      e.printStackTrace();
    }
  }

  /**
   * Get a lazily loaded stream of lines from a gzipped file, similar to
   * {@link Files#lines(java.nio.file.Path)}.
   * 
   * @param path The path to the gzipped file.
   * @return stream with lines.
   */
  public static Stream<String> lines(java.nio.file.Path path) {
    InputStream fileIs = null;
    BufferedInputStream bufferedIs = null;
    GZIPInputStream gzipIs = null;
    try {
      // List<OpenOption> options =
      // Arrays.asList(OpenOption){StandardOpenOption.READ};
      fileIs = Files.newInputStream(path, StandardOpenOption.READ);
      // fileIs = Files.
      // Even though GZIPInputStream has a buffer it reads individual bytes
      // when processing the header, better add a buffer in-between
      bufferedIs = new BufferedInputStream(fileIs, 65535);
      gzipIs = new GZIPInputStream(bufferedIs);
    } catch (IOException e) {
      closeSafely(gzipIs);
      closeSafely(bufferedIs);
      closeSafely(fileIs);
      throw new UncheckedIOException(e);
    }
    BufferedReader reader = new BufferedReader(new InputStreamReader(gzipIs));
    return reader.lines().onClose(() -> closeSafely(reader));
  }

  private static void closeSafely(Closeable closeable) {
    if (closeable != null) {
      try {
        closeable.close();
      } catch (IOException e) {
        // Ignore
      }
    }
  }

  /*
   * Reads the location, deduplicates it, dumps it to HDFS
   */
  public static int process(String fileLocation, String hdfsDumpLocation) {

    // Special handle, need to remove this
    if (fileLocation.startsWith("file:")) {
      fileLocation = fileLocation.substring(7);
    }

    File file = new File(fileLocation);
    if (isDebug) {
      logger.info("Path : " + file.getPath());
    }

    // Primary validations
    if (!file.getAbsoluteFile().exists()) {
      logger.info("File " + fileLocation + " does not exist");
      return -1;
    } else if (file.isDirectory()) {
      logger.info("File " + fileLocation + " is a directory");
      return -2;
    } else if (file.isFile()) {
      if (isDebug) {
        logger.info("Processing File " + fileLocation);
      }
    }

    try {

      Path hdfsFile = new Path(hdfsDumpLocation);
      OutputStream output = hdfs.create(hdfsFile, new Progressable() {
        @Override
        public void progress() {
          // System.out.print(".");
        }
      });

      BufferedWriter br =
          new BufferedWriter(new OutputStreamWriter(output, "UTF-8"));

      Stream<String> lines;
      if (fileLocation.endsWith(".gz")) {
        // using custom gzipped lines extractor
        lines = lines(Paths.get(fileLocation));
      } else {
        lines = Files.lines(Paths.get(fileLocation));
      }

      Stream<String> dedupedLines = lines.skip(1).distinct();

      if (isParallel) {
        dedupedLines = lines.parallel();
      }

      long startTime = System.currentTimeMillis();

      dedupedLines.forEach(line -> {
        try {
          br.write(line);
          br.newLine();
        } catch (IOException e) {
          logger.error("IO Exception while writing to HDFS");
        }
      });

      lines.close();
      br.flush();
      br.close();
      if (isDebug) {
        logger.info("Successfully processed file : " + fileLocation);
        logger.info("Total Time : " + (System.currentTimeMillis() - startTime) + " ms");
      }
      return 0;

    } catch (IOException e) {
      e.printStackTrace();
    }
    return -3;

  }

  static boolean isParallel = false;

  public static void main(String[] args) {

    String fileLocation =
        "file:///home/abhay/MyHome/WorkArea/CodeHome/Projects/Xoriant/Aera/Data/FOPS_AUFM_20170915_033821061.gz";
    // String fileLocation =
    // "file:///home/abhay/MyHome/WorkArea/CodeHome/Projects/Xoriant/Aera/Data/FOPS_VBAK_VBAP_VBEP_20170920_081713115.gz";
    String hdfsDumpLocation =
        "hdfs://localhost:9000/user/data_csv/1GB_fromGz.csv";

    // String fileLocation = args[0];
    // String hdfsDumpLocation = args[1];
    // isParallel = Boolean.parseBoolean(args[2]);
    process(fileLocation, hdfsDumpLocation);
  }
}
