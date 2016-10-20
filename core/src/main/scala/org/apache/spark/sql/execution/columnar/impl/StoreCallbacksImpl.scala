/*
 * Copyright (c) 2016 SnappyData, Inc. All rights reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you
 * may not use this file except in compliance with the License. You
 * may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or
 * implied. See the License for the specific language governing
 * permissions and limitations under the License. See accompanying
 * LICENSE file.
 */
package org.apache.spark.sql.execution.columnar.impl

import java.lang
import java.util.{Collections, UUID}

import scala.collection.concurrent.TrieMap

import com.gemstone.gemfire.internal.cache.{BucketRegion, LocalRegion}
import com.gemstone.gemfire.internal.snappy.{CallbackFactoryProvider, StoreCallbacks}
import com.pivotal.gemfirexd.internal.engine.Misc
import com.pivotal.gemfirexd.internal.engine.distributed.utils.GemFireXDUtils
import com.pivotal.gemfirexd.internal.engine.store.{GemFireStore, AbstractCompactExecRow, GemFireContainer}
import com.pivotal.gemfirexd.internal.iapi.sql.conn.LanguageConnectionContext
import com.pivotal.gemfirexd.internal.iapi.store.access.TransactionController
import com.pivotal.gemfirexd.internal.impl.jdbc.EmbedConnection
import io.snappydata.Constant
import org.apache.spark.SparkException
import org.apache.spark.internal.Logging
import org.apache.spark.sql.{SparkSession, SplitClusterMode, SnappySession, SnappyContext, SQLContext}
import org.apache.spark.sql.execution.ConnectionPool
import org.apache.spark.sql.execution.columnar.{ExternalStoreUtils, CachedBatchCreator, ExternalStore}
import org.apache.spark.sql.execution.joins.HashedRelationCache
import org.apache.spark.sql.hive.SnappyStoreHiveCatalog
import org.apache.spark.sql.store.{StoreUtils, CodeGeneration, StoreHashFunction}
import org.apache.spark.sql.types._

object StoreCallbacksImpl extends StoreCallbacks with Logging with Serializable {

  @transient private var sqlContext = None: Option[SQLContext]
  val stores = new TrieMap[String, (StructType, ExternalStore)]

  val partitioner = new StoreHashFunction

  var useCompression = false
  var cachedBatchSize = 0

  def registerExternalStoreAndSchema(context: SQLContext, tableName: String,
      schema: StructType, externalStore: ExternalStore,
      batchSize: Int, compress: Boolean): Unit = {
    stores.synchronized {
      stores.get(tableName) match {
        case None => stores.put(tableName, (schema, externalStore))
        case Some((previousSchema, _)) =>
          if (previousSchema != schema) {
            stores.put(tableName, (schema, externalStore))
          }
      }
    }
    sqlContext = Some(context)
    useCompression = compress
    cachedBatchSize = batchSize
  }

  override def createCachedBatch(region: BucketRegion, batchID: UUID,
      bucketID: Int): java.util.Set[AnyRef] = {
    val container = region.getPartitionedRegion
        .getUserAttribute.asInstanceOf[GemFireContainer]
    val store = stores.get(container.getQualifiedTableName)

    if (store.isDefined) {
      val (schema, externalStore) = store.get
      // LCC should be available assuming insert is already being done
      // via a proper connection
      var conn: EmbedConnection = null
      var contextSet: Boolean = false
      try {
        var lcc: LanguageConnectionContext = Misc.getLanguageConnectionContext
        if (lcc == null) {
          conn = GemFireXDUtils.getTSSConnection(true, true, false)
          conn.getTR.setupContextStack()
          contextSet = true
          lcc = conn.getLanguageConnectionContext
          if (lcc == null) {
            Misc.getGemFireCache.getCancelCriterion.checkCancelInProgress(null)
          }
        }
        val row: AbstractCompactExecRow = container.newTemplateRow()
            .asInstanceOf[AbstractCompactExecRow]
        lcc.setExecuteLocally(Collections.singleton(bucketID),
          region.getPartitionedRegion, false, null)
        try {
          val sc = lcc.getTransactionExecute.openScan(
            container.getId.getContainerId, false, 0,
            TransactionController.MODE_RECORD,
            TransactionController.ISOLATION_NOLOCK /* not used */ ,
            null, null, 0, null, null, 0, null)

          val batchCreator = new CachedBatchCreator(
            ColumnFormatRelation.cachedBatchTableName(container.getQualifiedTableName),
            container.getQualifiedTableName, schema,
            externalStore, cachedBatchSize, useCompression)
          batchCreator.createAndStoreBatch(sc, row,
            batchID, bucketID)
        } finally {
          lcc.setExecuteLocally(null, null, false, null)
        }
      } catch {
        case e: Throwable => throw e
      } finally {
        if (contextSet) {
          conn.getTR.restoreContextStack()
        }
      }
    } else {
      java.util.Collections.emptySet[AnyRef]()
    }
  }

  def getInternalTableSchemas: java.util.List[String] = {
    val schemas = new java.util.ArrayList[String](2)
    schemas.add(SnappyStoreHiveCatalog.HIVE_METASTORE)
    schemas.add(Constant.INTERNAL_SCHEMA_NAME)
    schemas
  }

  override def getHashCodeSnappy(dvd: scala.Any, numPartitions: Int): Int = {
    partitioner.computeHash(dvd, numPartitions)
  }

  override def getHashCodeSnappy(dvds: scala.Array[Object],
      numPartitions: Int): Int = {
    partitioner.computeHash(dvds, numPartitions)
  }

  override def invalidateReplicatedTableCache(region: LocalRegion): Unit = {
    HashedRelationCache.clear()
  }
  
  override def cachedBatchTableName(table: String): String = {
    ColumnFormatRelation.cachedBatchTableName(table)
  }

  override def snappyInternalSchemaName(): String = {
    io.snappydata.Constant.INTERNAL_SCHEMA_NAME
  }

  override def cleanUpCachedObjects(table: String,
      sentFromExternalCluster: lang.Boolean): Unit = {
    if (sentFromExternalCluster) {
      // cleanup invoked on embedded mode nodes
      // from external cluster (in split mode) driver
      ExternalStoreUtils.removeCachedObjects(table)
      stores.remove(table)
      // clean up cached hive relations on lead node
      if (GemFireXDUtils.getGfxdAdvisor.getMyProfile.hasSparkURL) {
        SnappyStoreHiveCatalog.registerRelationDestroy()
      }
    } else {
      // clean up invoked on external cluster driver (in split mode)
      // from embedded mode lead
      val sc = SnappyContext.globalSparkContext
      val mode = SnappyContext.getClusterMode(sc)
      mode match {
        case SplitClusterMode(_, _) =>
          StoreUtils.removeCachedObjects(
            SnappySession.getOrCreate(sc).sqlContext, table, true
          )

        case _ =>
          throw new SparkException("Clean up expected to be invoked on" +
            " external cluster driver. Current cluster mode is " + mode)
      }
    }
  }

  override def registerRelationDestroyForHiveStore(): Unit = {
    SnappyStoreHiveCatalog.registerRelationDestroy()
  }

}

trait StoreCallback extends Serializable {
  CallbackFactoryProvider.setStoreCallbacks(StoreCallbacksImpl)
}