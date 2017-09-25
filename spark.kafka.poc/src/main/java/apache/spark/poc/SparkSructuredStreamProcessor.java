package apache.spark.poc;

import org.apache.spark.sql.Dataset;
import org.apache.spark.sql.Encoders;
import org.apache.spark.sql.ForeachWriter;
import org.apache.spark.sql.SparkSession;
import org.apache.spark.sql.streaming.StreamingQuery;
import org.apache.spark.sql.streaming.StreamingQueryException;

import com.fasterxml.jackson.databind.ObjectMapper;

import apache.spark.poc.config.Configuration;
import apache.spark.poc.entity.Message;
import apache.spark.poc.utils.FileProcessor;

public class SparkSructuredStreamProcessor {

  public static void main(String[] args) throws StreamingQueryException {

    SparkSession spark = SparkSession.builder().appName("StructuredFileReader")
        .master("local[4]").config("spark.executor.memory", "2g").getOrCreate();

    // Create DataSet representing the stream of input lines from kafka
    Dataset<String> kafkaValues = spark.readStream().format("kafka")
        .option("spark.streaming.receiver.writeAheadLog.enable", true)
        .option("kafka.bootstrap.servers", Configuration.KAFKA_BROKER)
        .option("subscribe", Configuration.KAFKA_TOPIC)
        .option("fetchOffset.retryIntervalMs", 100)
        .option("checkpointLocation", "file:///tmp/checkpoint").load()
        .selectExpr("CAST(value AS STRING)").as(Encoders.STRING());

    Dataset<Message> messages = kafkaValues.map(x -> {
      ObjectMapper mapper = new ObjectMapper();
      Message m = mapper.readValue(x.getBytes(), Message.class);
      return m;
    }, Encoders.bean(Message.class));
    
//    StreamingQuery query =
//        messages.writeStream().outputMode("append").format("console").start();
    
    StreamingQuery query = messages.writeStream().foreach( new ForeachWriter<Message>() {
      
      private static final long serialVersionUID = 1L;

      @Override
      public void process(Message arg0) {
        System.out.println("Entered process method File : " + arg0.getFileName() );
        FileProcessor.process(arg0.getFileName(), arg0.getHdfsLocation());
      }
      
      @Override
      public boolean open(long arg0, long arg1) {
        System.out.println("Entered open method params : " + arg0 + " -- " + arg1 );
        return true;
      }
      
      @Override
      public void close(Throwable arg0) {
        System.out.println("Entered close method");
      }
    } ).start();    
    
//    foreach( message -> {
//      Dataset<String> fileRDD = spark.read().option("header", "false").textFile(message.getFileName());
//      fileRDD.dropDuplicates();
//    });

    query.awaitTermination();
  }
}