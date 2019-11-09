/*
 * Copyright 2019 Databricks, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.spark.sql.delta.sources

import org.apache.spark.sql.delta._
import org.apache.spark.sql.delta.actions.SetTransaction
import org.apache.spark.sql.delta.metering.DeltaLogging
import org.apache.spark.sql.delta.schema.{ImplicitMetadataOperation, SchemaUtils}
import org.apache.hadoop.fs.Path

import org.apache.spark.sql._
import org.apache.spark.sql.execution.streaming.{Sink, StreamExecution}
import org.apache.spark.sql.streaming.OutputMode
import org.apache.spark.sql.types.NullType

/**
 * A streaming sink that writes data into a Delta Table.
 */
class DeltaSink(
    sqlContext: SQLContext,
    path: Path,
    partitionColumns: Seq[String],
    outputMode: OutputMode,
    options: DeltaOptions)
  extends Sink with ImplicitMetadataOperation with DeltaLogging {

  private val deltaLog = DeltaLog.forTable(sqlContext.sparkSession, path)

  private val sqlConf = sqlContext.sparkSession.sessionState.conf

  override protected val canOverwriteSchema: Boolean =
    outputMode == OutputMode.Complete() && options.canOverwriteSchema

  override protected val canMergeSchema: Boolean = options.canMergeSchema

  override def addBatch(batchId: Long, data: DataFrame): Unit = deltaLog.withNewTransaction { txn =>
    val queryId = sqlContext.sparkContext.getLocalProperty(StreamExecution.QUERY_ID_KEY)
    assert(queryId != null)

    if (SchemaUtils.typeExistsRecursively(data.schema)(_.isInstanceOf[NullType])) {
      throw DeltaErrors.streamWriteNullTypeException
    }

    // If the batch reads the same Delta table as this sink is going to write to, then this
    // write has dependencies. Then make sure that this commit set hasDependencies to true
    // by injecting a read on the whole table. This needs to be done explicitly because
    // MicroBatchExecution has already enforced all the data skipping (by forcing the generation
    // of the executed plan) even before the transaction was started.
    val selfScan = data.queryExecution.analyzed.collectFirst {
      case DeltaTable(index) if index.deltaLog.isSameLogAs(txn.deltaLog) => true
    }.nonEmpty
    if (selfScan) {
      txn.readWholeTable()
    }

    // Streaming sinks can't blindly overwrite schema. See Schema Management design doc for details
    updateMetadata(
      txn,
      data,
      partitionColumns,
      configuration = Map.empty,
      outputMode == OutputMode.Complete())

    val currentVersion = txn.txnVersion(queryId)
    if (currentVersion >= batchId) {
      logInfo(s"Skipping already complete epoch $batchId, in query $queryId")
      return
    }

    val deletedFiles = outputMode match {
      case o if o == OutputMode.Complete() =>
        deltaLog.assertRemovable()
        txn.filterFiles().map(_.remove)
      case _ => Nil
    }
    val newFiles = txn.writeFiles(data, Some(options))
    val setTxn = SetTransaction(queryId, batchId, Some(deltaLog.clock.getTimeMillis())) :: Nil
    val info = DeltaOperations.StreamingUpdate(outputMode, queryId, batchId)
    txn.commit(setTxn ++ newFiles ++ deletedFiles, info)
  }

  override def toString(): String = s"DeltaSink[$path]"
}
