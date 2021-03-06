/*
 * Copyright 2020 The Closure Compiler Authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.google.javascript.jscomp.instrumentation.reporter;

import com.google.auto.value.AutoValue;
import com.google.common.annotations.GwtIncompatible;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableListMultimap;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Multimaps;
import com.google.javascript.jscomp.instrumentation.reporter.ProductionInstrumentationReporter.InstrumentationType;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;
import java.util.stream.Collectors;

/**
 * A class that maintains all information about the production instrumentation results which will be
 * converted to a JSON.
 */
@GwtIncompatible
final class ProfilingReport {

  final int totalReportsParsed;
  final float percentOfFunctionsExecuted;
  final float percentOfBranchesExecuted;
  final List<FileProfilingResult> result;

  private ProfilingReport(int totalReportsParsed, List<FileProfilingResult> result) {
    this.totalReportsParsed = totalReportsParsed;
    this.result = result;

    // Calculate the percentages for the profiling report
    List<Map<String, List<ProfilingResult>>> listOfProfilingResultMaps = new ArrayList<>();
    for (FileProfilingResult fileProfilingResult : result) {
      listOfProfilingResultMaps.add(fileProfilingResult.profilingResultPerFunction());
    }

    this.percentOfFunctionsExecuted =
        calculatePercentOfInstrumentationExecuted(
            listOfProfilingResultMaps, InstrumentationType.FUNCTION::equals);
    this.percentOfBranchesExecuted =
        calculatePercentOfInstrumentationExecuted(
            listOfProfilingResultMaps,
            (t) ->
                t.equals(InstrumentationType.BRANCH)
                    || t.equals(InstrumentationType.BRANCH_DEFAULT));
  }

  private static float roundFloat2Decimals(float num) {
    return (Math.round(num * 10000) / (float) 100);
  }

  /**
   * This function takes the instrumentationMapping and reports sent by the instrumented production
   * code and creates a ProfilingReport with the aggregated information. It will average the
   * ProfilingData provided by allInstrumentationReports and also calculate a percentage of how
   * often a parameter is encountered.
   *
   * @param instrumentationMapping The instrumentationMapping generated by the Production
   *     Instrumentation pass
   * @param allInstrumentationReports A list off all reports sent by the instrumented production
   *     code
   * @return A ProfilingReport which contains all the aggregated information from the
   *     instrumentationReports with values decoded using the instrumentationMapping.
   */
  public static ProfilingReport createProfilingReport(
      InstrumentationMapping instrumentationMapping,
      ImmutableList<Map<String, ProfilingData>> allInstrumentationReports) {

    List<FileProfilingResult> result = new ArrayList<>();

    // Iterate over all fileNames since that is what we are grouping by.
    for (String fileName : instrumentationMapping.fileNames) {
      FileProfilingResult fileProfilingResult =
          createFileProfilingResult(fileName, instrumentationMapping, allInstrumentationReports);
      result.add(fileProfilingResult);
    }

    return new ProfilingReport(allInstrumentationReports.size(), result);
  }

  private static FileProfilingResult createFileProfilingResult(
      String fileName,
      InstrumentationMapping instrumentationMapping,
      ImmutableList<Map<String, ProfilingData>> allInstrumentationReports) {

    // Get all instrumentation parameters where the source file is fileName.
    List<String> instrumentationPointsPerFile =
        instrumentationMapping.getAllMatchingValues(
            (s) -> fileName.equals(instrumentationMapping.getFileName(s)));

    ImmutableListMultimap.Builder<String, ProfilingResult> profilingResultPerFunctionBuilder =
        ImmutableListMultimap.builder();

    for (String id : instrumentationPointsPerFile) {
      String functionName = instrumentationMapping.getFunctionName(id);

      ProfilingResult profilingResult =
          createProfilingResult(id, instrumentationMapping, allInstrumentationReports);

      profilingResultPerFunctionBuilder.put(functionName, profilingResult);
    }

    ImmutableListMultimap<String, ProfilingResult> profilingResultPerFunction =
        profilingResultPerFunctionBuilder.build();

    float percentOfFunctionsExecuted =
        calculatePercentOfInstrumentationExecuted(
            Arrays.asList(Multimaps.asMap(profilingResultPerFunction)),
            InstrumentationType.FUNCTION::equals);

    float percentOfBranchesExecuted =
        calculatePercentOfInstrumentationExecuted(
            Arrays.asList(Multimaps.asMap(profilingResultPerFunction)),
            (t) ->
                t.equals(InstrumentationType.BRANCH)
                    || t.equals(InstrumentationType.BRANCH_DEFAULT));

    return FileProfilingResult.create(
        fileName,
        percentOfFunctionsExecuted,
        percentOfBranchesExecuted,
        profilingResultPerFunction);
  }

  private static ProfilingResult createProfilingResult(
      String id,
      InstrumentationMapping instrumentationMapping,
      ImmutableList<Map<String, ProfilingData>> allInstrumentationReports) {

    float totalInstrumentationPointsExecuted = 0;
    int totalFrequency = 0;
    int instrumentationPointsCounter = 0;

    // For each id, iterate over allInstrumentationReports and check if the id is present.
    // If it is, we will average the data, otherwise we will add to the average as if it is 0.
    for (Map<String, ProfilingData> instrumentationData : allInstrumentationReports) {
      ProfilingData profilingData = instrumentationData.get(id);
      instrumentationPointsCounter++;
      if (profilingData != null) {
        // if executed, add 1 to executionAverage, and 0 otherwise.
        totalFrequency += profilingData.frequency;
        totalInstrumentationPointsExecuted++;
      }
    }

    // Round the executedAverage to 2 decimal places for simplicity.
    float executedAverage =
        roundFloat2Decimals(totalInstrumentationPointsExecuted / instrumentationPointsCounter);

    ProfilingData data =
        new ProfilingData(Math.round((float) totalFrequency / instrumentationPointsCounter));

    return ProfilingResult.create(instrumentationMapping, id, executedAverage, data);
  }

  /**
   * Iterates over the result property and calculates what percent out of all functions and branches
   * were executed to get aggregated instrumentation data.
   */
  private static float calculatePercentOfInstrumentationExecuted(
      List<Map<String, List<ProfilingResult>>> result,
      Predicate<InstrumentationType> instrumentationTypeCheck) {
    int totalInstrumentationPoints = 0;
    int totalInstrumentationPointsExecuted = 0;

    List<ProfilingResult> allProfilingResults =
        result.stream()
            .flatMap(fileProfilingResult -> fileProfilingResult.values().stream())
            .flatMap(Collection::stream)
            .collect(Collectors.toList());

    for (ProfilingResult profilingResult : allProfilingResults) {
      if (instrumentationTypeCheck.test(profilingResult.type())) {
        totalInstrumentationPoints++;
        if (profilingResult.executed() != 0) {
          totalInstrumentationPointsExecuted++;
        }
      }
    }
    return totalInstrumentationPoints != 0
        ? roundFloat2Decimals(
            (float) totalInstrumentationPointsExecuted / totalInstrumentationPoints)
        : 100;
  }

  /**
   * A class which groups profiling results by source file and contains a mapping of them to each
   * function which is present in that source file. Used also for Json conversion.
   */
  @AutoValue
  abstract static class FileProfilingResult {

    static FileProfilingResult create(
        String fileName,
        float percentOfFunctionsExecuted,
        float percentOfBranchesExecuted,
        ImmutableListMultimap<String, ProfilingResult> profilingResultPerFunction) {
      return new AutoValue_ProfilingReport_FileProfilingResult(
          fileName,
          percentOfFunctionsExecuted,
          percentOfBranchesExecuted,
          ImmutableMap.copyOf(Multimaps.asMap(profilingResultPerFunction)));
    }

    abstract String fileName();

    abstract float percentOfFunctionsExecuted();

    abstract float percentOfBranchesExecuted();

    /**
     * A map of function names to a list of all associated instrumentation points associated with
     * that function name. Any given function will typically have a FUNCTION instrumentation point
     * and several BRANCH and BRANCH_DEFAULT instrumentation points. Note that this property cannot
     * be a Multimap as it is not supported by GSON deserialization.
     */
    abstract ImmutableMap<String, List<ProfilingResult>> profilingResultPerFunction();
  }

  /**
   * This class contains a detailed breakdown of each instrumentation point. For any encoded param,
   * this class will contain its details. This class is also used for Json serialization.
   */
  @AutoValue
  abstract static class ProfilingResult {

    static ProfilingResult create(
        InstrumentationMapping instrumentationMapping,
        String param,
        float executed,
        ProfilingData data) {
      return new AutoValue_ProfilingReport_ProfilingResult(
          param,
          instrumentationMapping.getType(param),
          instrumentationMapping.getLineNo(param),
          instrumentationMapping.getColNo(param),
          executed,
          data);
    }

    abstract String param();

    abstract InstrumentationType type();

    abstract int lineNo();

    abstract int colNo();

    /**
     * The `executed` property will be a percent of total times the instrumentation point was called
     * across all provided instrumentation reports. e.g if this.executed = 100, then every report
     * would have this point present.
     */
    abstract float executed();

    /**
     * Similar to executed, ProfilingData takes the average of all ProfilingData's for this
     * instrumentation point in all reports.
     */
    abstract ProfilingData data();
  }

  /**
   * The instrumentation will send the report in a JSON format were the JSON is a dictionary of the
   * encoded params to an object of the data collected. Initially this will just be the frequency.
   * This class is a great candidate for AutoValue, however GSON deserialization does not seem to
   * support it.
   */
  static class ProfilingData {

    final int frequency;

    public ProfilingData(int frequency) {
      this.frequency = frequency;
    }
  }
}
