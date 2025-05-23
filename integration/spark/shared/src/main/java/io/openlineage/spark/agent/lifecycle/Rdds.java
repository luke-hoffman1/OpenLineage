/*
/* Copyright 2018-2025 contributors to the OpenLineage project
/* SPDX-License-Identifier: Apache-2.0
*/

package io.openlineage.spark.agent.lifecycle;

import io.openlineage.spark.agent.util.ScalaConversionUtils;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.Stack;
import java.util.stream.Collectors;
import org.apache.spark.Dependency;
import org.apache.spark.rdd.HadoopRDD;
import org.apache.spark.rdd.RDD;
import org.apache.spark.scheduler.SparkListenerJobStart;
import org.apache.spark.scheduler.StageInfo;
import org.apache.spark.sql.execution.ShuffledRowRDD;
import org.apache.spark.sql.execution.datasources.FileScanRDD;
import org.apache.spark.storage.RDDInfo;

public class Rdds {

  public static Set<RDD<?>> flattenRDDs(RDD<?> rdd, Set<Integer> visitedRdds) {
    Set<RDD<?>> rdds = new HashSet<>();
    if (visitedRdds.contains(rdd.id())) {
      return rdds;
    } else {
      rdds.add(rdd);
      visitedRdds.add(rdd.id());
      if (rdd instanceof ShuffledRowRDD) {
        rdds.addAll(flattenRDDs(((ShuffledRowRDD) rdd).dependency().rdd(), visitedRdds));
      }

      Collection<Dependency<?>> deps = ScalaConversionUtils.fromSeq(rdd.dependencies());
      for (Dependency<?> dep : deps) {
        rdds.addAll(flattenRDDs(dep.rdd(), visitedRdds));
      }
      return rdds;
    }
  }

  static String toString(SparkListenerJobStart jobStart) {
    StringBuilder sb = new StringBuilder();
    sb.append("start: ").append(jobStart.properties().toString()).append("\n");
    List<StageInfo> stageInfos = ScalaConversionUtils.fromSeq(jobStart.stageInfos());
    for (StageInfo stageInfo : stageInfos) {
      sb.append("  ")
          .append("stageInfo: ")
          .append(stageInfo.stageId())
          .append(" ")
          .append(stageInfo.name())
          .append("\n");
      List<RDDInfo> rddInfos = ScalaConversionUtils.fromSeq(stageInfo.rddInfos());
      for (RDDInfo rddInfo : rddInfos) {
        sb.append("    ").append("rddInfo: ").append(rddInfo).append("\n");
      }
    }
    return sb.toString();
  }

  public static List<RDD<?>> findFileLikeRdds(RDD<?> rdd) {
    List<RDD<?>> ret = new ArrayList<>();
    Stack<RDD<?>> deps = new Stack<>();
    if (rdd != null) deps.add(rdd);
    while (!deps.isEmpty()) {
      RDD<?> cur = deps.pop();
      if (cur.getDependencies() != null) {
        deps.addAll(
            ScalaConversionUtils.fromSeq(cur.getDependencies()).stream()
                .map(Dependency::rdd)
                .collect(Collectors.toList()));
      }
      if (cur instanceof HadoopRDD) {
        ret.add(cur);
      } else if (cur instanceof FileScanRDD) {
        ret.add(cur);
      }
    }
    return ret;
  }

  public static List<RDD<?>> findFileLikeRdds(Set<RDD<?>> rdds) {
    return rdds.stream()
        .filter(r -> r instanceof HadoopRDD || r instanceof FileScanRDD)
        .collect(Collectors.toList());
  }
}
