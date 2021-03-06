/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.spark.sql.hive.execution

import org.apache.hadoop.hive.common.StatsSetupConst

import org.apache.spark.sql.SparkSession
import org.apache.spark.sql.catalyst.analysis.CastSupport
import org.apache.spark.sql.catalyst.catalog.{CatalogStatistics, CatalogTable, CatalogTablePartition, ExternalCatalogUtils, HiveTableRelation}
import org.apache.spark.sql.catalyst.expressions.{And, AttributeSet, Expression, ExpressionSet, SubqueryExpression}
import org.apache.spark.sql.catalyst.planning.PhysicalOperation
import org.apache.spark.sql.catalyst.plans.logical.{Filter, LogicalPlan, Project}
import org.apache.spark.sql.catalyst.rules.Rule
import org.apache.spark.sql.execution.datasources.DataSourceStrategy
import org.apache.spark.sql.internal.SQLConf

/**
 * TODO: merge this with PruneFileSourcePartitions after we completely make hive as a data source.
 */
private[sql] class PruneHiveTablePartitions(session: SparkSession)
  extends Rule[LogicalPlan] with CastSupport {

  override val conf: SQLConf = session.sessionState.conf

  /**
   * Extract the partition filters from the filters on the table.
   */
  private def getPartitionKeyFilters(
      filters: Seq[Expression],
      relation: HiveTableRelation): ExpressionSet = {
    val normalizedFilters = DataSourceStrategy.normalizeExprs(
      filters.filter(f => f.deterministic && !SubqueryExpression.hasSubquery(f)), relation.output)
    val partitionColumnSet = AttributeSet(relation.partitionCols)
    ExpressionSet(normalizedFilters.filter { f =>
      !f.references.isEmpty && f.references.subsetOf(partitionColumnSet)
    })
  }

  /**
   * Prune the hive table using filters on the partitions of the table.
   */
  private def prunePartitions(
      relation: HiveTableRelation,
      partitionFilters: ExpressionSet): Seq[CatalogTablePartition] = {
    if (conf.metastorePartitionPruning) {
      session.sessionState.catalog.listPartitionsByFilter(
        relation.tableMeta.identifier, partitionFilters.toSeq)
    } else {
      ExternalCatalogUtils.prunePartitionsByFilter(relation.tableMeta,
        session.sessionState.catalog.listPartitions(relation.tableMeta.identifier),
        partitionFilters.toSeq, conf.sessionLocalTimeZone)
    }
  }

  /**
   * Update the statistics of the table.
   */
  private def updateTableMeta(
      tableMeta: CatalogTable,
      prunedPartitions: Seq[CatalogTablePartition]): CatalogTable = {
    val sizeOfPartitions = prunedPartitions.map { partition =>
      val rawDataSize = partition.parameters.get(StatsSetupConst.RAW_DATA_SIZE).map(_.toLong)
      val totalSize = partition.parameters.get(StatsSetupConst.TOTAL_SIZE).map(_.toLong)
      if (rawDataSize.isDefined && rawDataSize.get > 0) {
        rawDataSize.get
      } else if (totalSize.isDefined && totalSize.get > 0L) {
        totalSize.get
      } else {
        0L
      }
    }
    if (sizeOfPartitions.forall(_ > 0)) {
      val sizeInBytes = sizeOfPartitions.sum
      tableMeta.copy(stats = Some(CatalogStatistics(sizeInBytes = BigInt(sizeInBytes))))
    } else {
      tableMeta
    }
  }

  override def apply(plan: LogicalPlan): LogicalPlan = plan resolveOperators {
    case op @ PhysicalOperation(projections, filters, relation: HiveTableRelation)
      if filters.nonEmpty && relation.isPartitioned && relation.prunedPartitions.isEmpty =>
      val partitionKeyFilters = getPartitionKeyFilters(filters, relation)
      if (partitionKeyFilters.nonEmpty) {
        val newPartitions = prunePartitions(relation, partitionKeyFilters)
        val newTableMeta = updateTableMeta(relation.tableMeta, newPartitions)
        val newRelation = relation.copy(
          tableMeta = newTableMeta, prunedPartitions = Some(newPartitions))
        // Keep partition filters so that they are visible in physical planning
        Project(projections, Filter(filters.reduceLeft(And), newRelation))
      } else {
        op
      }
  }
}
