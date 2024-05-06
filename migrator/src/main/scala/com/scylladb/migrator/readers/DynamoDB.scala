package com.scylladb.migrator.readers

import com.amazonaws.services.dynamodbv2.model.TableDescription
import com.scylladb.migrator.DynamoUtils
import com.scylladb.migrator.DynamoUtils.{ setDynamoDBJobConf, setOptionalConf }
import com.scylladb.migrator.config.{ AWSCredentials, DynamoDBEndpoint, SourceSettings }
import org.apache.hadoop.dynamodb.{ DynamoDBConstants, DynamoDBItemWritable }
import org.apache.hadoop.dynamodb.read.DynamoDBInputFormat
import org.apache.hadoop.io.Text
import org.apache.hadoop.mapred.JobConf
import org.apache.spark.rdd.RDD
import org.apache.spark.sql.SparkSession

object DynamoDB {

  def readRDD(
    spark: SparkSession,
    source: SourceSettings.DynamoDB): (RDD[(Text, DynamoDBItemWritable)], TableDescription) =
    readRDD(
      spark,
      source.endpoint,
      source.credentials,
      source.region,
      source.table,
      source.scanSegments,
      source.maxMapTasks,
      source.readThroughput,
      source.throughputReadPercent
    )

  /**
    * Overload of `readRDD` that does not depend on `SourceSettings.DynamoDB`
    */
  def readRDD(
    spark: SparkSession,
    endpoint: Option[DynamoDBEndpoint],
    credentials: Option[AWSCredentials],
    region: Option[String],
    table: String,
    scanSegments: Option[Int],
    maxMapTasks: Option[Int],
    readThroughput: Option[Int],
    throughputReadPercent: Option[Float]): (RDD[(Text, DynamoDBItemWritable)], TableDescription) = {
    val description = DynamoUtils
      .buildDynamoClient(endpoint, credentials, region)
      .describeTable(table)
      .getTable

    val jobConf = new JobConf(spark.sparkContext.hadoopConfiguration)

    setDynamoDBJobConf(
      jobConf,
      region,
      endpoint,
      scanSegments,
      maxMapTasks,
      credentials
    )
    jobConf.set(DynamoDBConstants.INPUT_TABLE_NAME, table)
    jobConf.set(DynamoDBConstants.ITEM_COUNT, description.getItemCount.toString)
    jobConf.set(
      DynamoDBConstants.AVG_ITEM_SIZE,
      (description.getTableSizeBytes / description.getItemCount).toString)
    jobConf.set(
      DynamoDBConstants.READ_THROUGHPUT,
      readThroughput
        .getOrElse(DynamoUtils.tableReadThroughput(description))
        .toString)
    setOptionalConf(
      jobConf,
      DynamoDBConstants.THROUGHPUT_READ_PERCENT,
      throughputReadPercent.map(_.toString))

    val rdd =
      spark.sparkContext.hadoopRDD(
        jobConf,
        classOf[DynamoDBInputFormat],
        classOf[Text],
        classOf[DynamoDBItemWritable])
    (rdd, description)
  }

}
