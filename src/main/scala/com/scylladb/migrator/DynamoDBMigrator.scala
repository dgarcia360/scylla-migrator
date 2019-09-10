package com.scylladb.migrator

import com.amazonaws.auth.{ AWSStaticCredentialsProvider, BasicAWSCredentials }
import com.amazonaws.client.builder.AwsClientBuilder
import com.amazonaws.services.dynamodbv2.document.DynamoDB
import org.apache.hadoop.io.Text
import org.apache.hadoop.dynamodb.{ DynamoDBClient, DynamoDBConstants, DynamoDBItemWritable }
import org.apache.spark.SparkContext
import org.apache.hadoop.dynamodb.read.DynamoDBInputFormat
import org.apache.hadoop.mapred.JobConf
import org.apache.spark.sql.SparkSession
import org.slf4j.LoggerFactory
import com.amazonaws.services.dynamodbv2.AmazonDynamoDBClientBuilder
import com.amazonaws.services.dynamodbv2.model.CreateTableRequest
import com.amazonaws.services.dynamodbv2.model.ProvisionedThroughput

object DynamoDBMigrator {
  private val log = LoggerFactory.getLogger(getClass)

  def main(args: Array[String]): Unit = {
    val spark = SparkSession.builder.getOrCreate()
    val migratorConfig =
      DynamoDBMigratorConfig.loadFrom(spark.conf.get("spark.scylla.config"))
    log.info(s"Loaded config: ${migratorConfig}")

    val sc = spark.sparkContext
    val tableToRead = migratorConfig.source.table
    var jobConf = getSourceDynamoDbJobConf(sc, tableToRead)
    val sourceEndpoint = migratorConfig.source.hostURL
      .getOrElse("") + ":" + migratorConfig.source.port.getOrElse("")
    if (":" != sourceEndpoint) { //endpoint not needed if region is there
      jobConf.set(DynamoDBConstants.ENDPOINT, sourceEndpoint)
    }
    jobConf.set(DynamoDBConstants.REGION_ID, migratorConfig.source.region)
    val creds: AWSCredentials =
      migratorConfig.source.credentials.getOrElse(AWSCredentials("empty", "empty"))
    val aws_key = creds.accessKey;
    jobConf.set(DynamoDBConstants.DYNAMODB_ACCESS_KEY_CONF, aws_key)
    val aws_secret = creds.secretKey
    jobConf.set(DynamoDBConstants.DYNAMODB_SECRET_KEY_CONF, aws_secret)

    val tableToWrite = migratorConfig.target.table.getOrElse(tableToRead)

    // replicate the schema to destination table
    val sourceClient = new DynamoDBClient(jobConf)
    val sourceTableDesc = sourceClient.describeTable(tableToRead)

    val destEndpoint = migratorConfig.target.hostURL + ":" + migratorConfig.target.port
    val destRegion = migratorConfig.target.region.getOrElse("empty")
    val destcreds: AWSCredentials =
      migratorConfig.target.credentials.getOrElse(AWSCredentials("empty", "empty"))
    val config = new AwsClientBuilder.EndpointConfiguration(destEndpoint, destRegion)
    val credentials = new BasicAWSCredentials(destcreds.accessKey, destcreds.secretKey)
    val credentialsProvider = new AWSStaticCredentialsProvider(credentials)
    val client = AmazonDynamoDBClientBuilder
      .standard()
      .withEndpointConfiguration(config)
      .withCredentials(credentialsProvider)
      .build();
    val dynamoDB = new DynamoDB(client)
    //https://docs.amazonaws.cn/en_us/amazondynamodb/latest/developerguide/JavaDocumentAPIWorkingWithTables.html
    val request = new CreateTableRequest()
      .withTableName(tableToWrite)
      .withKeySchema(sourceTableDesc.getKeySchema)
      .withAttributeDefinitions(sourceTableDesc.getAttributeDefinitions)
      .withProvisionedThroughput(new ProvisionedThroughput(
        sourceTableDesc.getProvisionedThroughput.getReadCapacityUnits,
        sourceTableDesc.getProvisionedThroughput.getWriteCapacityUnits))
    val table = dynamoDB.createTable(request)

    //start migration
    val rows = sc.hadoopRDD(
      jobConf,
      classOf[DynamoDBInputFormat],
      classOf[Text],
      classOf[DynamoDBItemWritable])

    val dstJobConf = getDestinationDynamoDbJobConf(sc, tableToWrite)
    dstJobConf.set(DynamoDBConstants.ENDPOINT, destEndpoint)
    dstJobConf.set(DynamoDBConstants.REGION_ID, destRegion)
    dstJobConf.set(DynamoDBConstants.DYNAMODB_ACCESS_KEY_CONF, destcreds.accessKey)
    dstJobConf.set(DynamoDBConstants.DYNAMODB_SECRET_KEY_CONF, destcreds.secretKey)

    rows.saveAsHadoopDataset(dstJobConf)

  }

  def getCommonDynamoDbJobConf(sc: SparkContext) = {
    val jobConf = new JobConf(sc.hadoopConfiguration)
//    jobConf.set("dynamodb.servicename", "dynamodb")
//    jobConf.set(DynamoDBConstants.MAX_MAP_TASKS,"1")
//    jobConf.set(DynamoDBConstants.SCAN_SEGMENTS, "3") // control split factor
    jobConf
  }

  def getSourceDynamoDbJobConf(sc: SparkContext, tableNameForRead: String) = {
    val jobConf = getCommonDynamoDbJobConf(sc);
    jobConf.set(DynamoDBConstants.INPUT_TABLE_NAME, tableNameForRead)
    jobConf.set("mapred.input.format.class", "org.apache.hadoop.dynamodb.read.DynamoDBInputFormat")
//    jobConf.set(DynamoDBConstants.READ_THROUGHPUT, "1")
//    jobConf.set(DynamoDBConstants.THROUGHPUT_READ_PERCENT, "1")
    jobConf
  }

  def getDestinationDynamoDbJobConf(sc: SparkContext, tableNameForWrite: String) = {
    val jobConf = getCommonDynamoDbJobConf(sc);
    jobConf.set(DynamoDBConstants.OUTPUT_TABLE_NAME, tableNameForWrite)
    jobConf.set(
      "mapred.output.format.class",
      "org.apache.hadoop.dynamodb.write.DynamoDBOutputFormat")
//    jobConf.set(DynamoDBConstants.WRITE_THROUGHPUT, "1")
//    jobConf.set(DynamoDBConstants.THROUGHPUT_WRITE_PERCENT, "1")
    jobConf
  }

}
