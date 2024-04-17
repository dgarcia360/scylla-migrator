package com.scylladb.migrator.alternator

import com.amazonaws.services.dynamodbv2.model.GetItemRequest
import com.scylladb.migrator.AttributeValueUtils.stringValue
import com.scylladb.migrator.SparkUtils.successfullyPerformMigration

import scala.collection.JavaConverters._
import scala.util.chaining._

class BasicMigrationTest extends MigratorSuite {

  withTable("BasicTest").test("Read from source and write to target") { tableName =>
    val keys = Map("id"   -> stringValue("12345"))
    val attrs = Map("foo" -> stringValue("bar"))
    val itemData = keys ++ attrs

    // Insert some items
    sourceDDb.putItem(tableName, itemData.asJava)

    // Perform the migration
    successfullyPerformMigration("dynamodb-to-alternator-basic.yaml")

    // Check that the schema has been replicated to the target table
    val sourceTableDesc = sourceDDb.describeTable(tableName).getTable
    targetAlternator
      .describeTable(tableName)
      .getTable
      .tap { targetTableDesc =>
        assertEquals(targetTableDesc.getKeySchema, sourceTableDesc.getKeySchema)
        assertEquals(
          targetTableDesc.getAttributeDefinitions,
          sourceTableDesc.getAttributeDefinitions)
      }

    // Check that the items have been migrated to the target table
    targetAlternator
      .getItem(new GetItemRequest(tableName, keys.asJava))
      .tap { itemResult =>
        assertEquals(itemResult.getItem.asScala.toMap, itemData)
      }
  }

}
