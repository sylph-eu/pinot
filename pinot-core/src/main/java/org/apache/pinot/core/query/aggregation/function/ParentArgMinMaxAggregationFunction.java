/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.pinot.core.query.aggregation.function;

import com.google.common.base.Preconditions;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.apache.pinot.common.request.context.ExpressionContext;
import org.apache.pinot.common.utils.DataSchema;
import org.apache.pinot.core.common.BlockValSet;
import org.apache.pinot.core.query.aggregation.AggregationResultHolder;
import org.apache.pinot.core.query.aggregation.ObjectAggregationResultHolder;
import org.apache.pinot.core.query.aggregation.groupby.GroupByResultHolder;
import org.apache.pinot.core.query.aggregation.groupby.ObjectGroupByResultHolder;
import org.apache.pinot.core.query.aggregation.utils.argminmax.ArgMinMaxMeasuringValSetWrapper;
import org.apache.pinot.core.query.aggregation.utils.argminmax.ArgMinMaxObject;
import org.apache.pinot.core.query.aggregation.utils.argminmax.ArgMinMaxProjectionValSetWrapper;
import org.apache.pinot.segment.spi.AggregationFunctionType;


public class ParentArgMinMaxAggregationFunction extends ParentAggregationFunction<ArgMinMaxObject, ArgMinMaxObject> {

  // list of columns that we do min/max on
  private final List<ExpressionContext> _measuringColumns;
  // list of columns that we project based on the min/max value
  private final List<ExpressionContext> _projectionColumns;
  // true if we are doing argmax, false if we are doing argmin
  private final boolean _isMax;
  // the id of the function, this is to associate the result of the parent aggregation function with the
  // child aggregation functions having the same type(argmin/argmax) and measuring columns
  private final ExpressionContext _functionIdContext;
  private final ExpressionContext _numMeasuringColumnContext;
  // number of columns that we do min/max on
  private final int _numMeasuringColumns;
  // number of columns that we project based on the min/max value
  private final int _numProjectionColumns;

  // The following variable need to be initialized

  // The wrapper classes for the block value sets
  private final ThreadLocal<List<ArgMinMaxMeasuringValSetWrapper>> _argMinMaxWrapperMeasuringColumnSets =
      ThreadLocal.withInitial(ArrayList::new);
  private final ThreadLocal<List<ArgMinMaxProjectionValSetWrapper>> _argMinMaxWrapperProjectionColumnSets =
      ThreadLocal.withInitial(ArrayList::new);
  // The schema for the measuring columns and projection columns
  private final ThreadLocal<DataSchema> _measuringColumnSchema = new ThreadLocal<>();
  private final ThreadLocal<DataSchema> _projectionColumnSchema = new ThreadLocal<>();
  // If the schemas are initialized
  private final ThreadLocal<Boolean> _schemaInitialized = ThreadLocal.withInitial(() -> false);

  public ParentArgMinMaxAggregationFunction(List<ExpressionContext> arguments, boolean isMax) {

    super(arguments);
    _isMax = isMax;
    _functionIdContext = arguments.get(0);

    _numMeasuringColumnContext = arguments.get(1);
    _numMeasuringColumns = _numMeasuringColumnContext.getLiteral().getIntValue();

    _measuringColumns = arguments.subList(2, 2 + _numMeasuringColumns);
    _projectionColumns = arguments.subList(2 + _numMeasuringColumns, arguments.size());
    _numProjectionColumns = _projectionColumns.size();
  }

  @Override
  public AggregationFunctionType getType() {
    return _isMax ? AggregationFunctionType.ARGMAX : AggregationFunctionType.ARGMIN;
  }

  @Override
  public List<ExpressionContext> getInputExpressions() {
    ArrayList<ExpressionContext> expressionContexts = new ArrayList<>();
    expressionContexts.add(_functionIdContext);
    expressionContexts.add(_numMeasuringColumnContext);
    expressionContexts.addAll(_measuringColumns);
    expressionContexts.addAll(_projectionColumns);
    return expressionContexts;
  }

  @Override
  public AggregationResultHolder createAggregationResultHolder() {
    return new ObjectAggregationResultHolder();
  }

  @Override
  public GroupByResultHolder createGroupByResultHolder(int initialCapacity, int maxCapacity) {
    return new ObjectGroupByResultHolder(initialCapacity, maxCapacity);
  }

  @SuppressWarnings("LoopStatementThatDoesntLoop")
  @Override
  public void aggregate(int length, AggregationResultHolder aggregationResultHolder,
      Map<ExpressionContext, BlockValSet> blockValSetMap) {

    ArgMinMaxObject argMinMaxObject = aggregationResultHolder.getResult();

    if (argMinMaxObject == null) {
      initializeWithNewDataBlocks(blockValSetMap);
      argMinMaxObject = new ArgMinMaxObject(_measuringColumnSchema.get(), _projectionColumnSchema.get());
    }

    List<Integer> rowIds = new ArrayList<>();
    for (int i = 0; i < length; i++) {
      int compareResult = argMinMaxObject.compareAndSetKey(_argMinMaxWrapperMeasuringColumnSets.get(), i, _isMax);
      if (compareResult == 0) {
        // same key, add the rowId to the list
        rowIds.add(i);
      } else if (compareResult > 0) {
        // new key is set, clear the list and add the new rowId
        rowIds.clear();
        rowIds.add(i);
      }
    }

    // for all the rows that are associated with the extremum key, add the projection columns
    for (Integer rowId : rowIds) {
      argMinMaxObject.addVal(_argMinMaxWrapperProjectionColumnSets.get(), rowId);
    }

    aggregationResultHolder.setValue(argMinMaxObject);
  }

  // this method is called to initialize the schemas if they are not initialized
  // and to set the new block value sets for the wrapper classes
  private void initializeWithNewDataBlocks(Map<ExpressionContext, BlockValSet> blockValSetMap) {
    if (blockValSetMap == null) {
      initializeForEmptyDocSet();
      return;
    }

    // if the schema is already initialized, just update with the new block value sets
    if (_schemaInitialized.get()) {
      for (int i = 0; i < _numMeasuringColumns; i++) {
        _argMinMaxWrapperMeasuringColumnSets.get().get(i).setNewBlock(blockValSetMap.get(_measuringColumns.get(i)));
      }
      for (int i = 0; i < _numProjectionColumns; i++) {
        _argMinMaxWrapperProjectionColumnSets.get().get(i).setNewBlock(blockValSetMap.get(_projectionColumns.get(i)));
      }
      return;
    }
    // the schema is initialized only once
    _schemaInitialized.set(true);
    // setup measuring column names and types
    initializeMeasuringColumnValSet(blockValSetMap);
    // setup projection column names and types
    initializeProjectionColumnValSet(blockValSetMap);
  }

  private void initializeProjectionColumnValSet(Map<ExpressionContext, BlockValSet> blockValSetMap) {
    List<ArgMinMaxProjectionValSetWrapper> argMinMaxWrapperProjectionColumnSets =
        _argMinMaxWrapperProjectionColumnSets.get();
    String[] projectionColNames = new String[_projectionColumns.size()];
    DataSchema.ColumnDataType[] projectionColTypes = new DataSchema.ColumnDataType[_projectionColumns.size()];
    for (int i = 0; i < _projectionColumns.size(); i++) {
      projectionColNames[i] = _projectionColumns.get(i).toString();
      ExpressionContext projectionColumn = _projectionColumns.get(i);
      BlockValSet blockValSet = blockValSetMap.get(projectionColumn);
      if (blockValSet.isSingleValue()) {
        switch (blockValSet.getValueType()) {
          case INT:
            argMinMaxWrapperProjectionColumnSets.add(
                new ArgMinMaxProjectionValSetWrapper(true, DataSchema.ColumnDataType.INT, blockValSet));
            projectionColTypes[i] = DataSchema.ColumnDataType.INT;
            break;
          case BOOLEAN:
            argMinMaxWrapperProjectionColumnSets.add(
                new ArgMinMaxProjectionValSetWrapper(true, DataSchema.ColumnDataType.BOOLEAN, blockValSet));
            projectionColTypes[i] = DataSchema.ColumnDataType.INT;
            break;
          case LONG:
            argMinMaxWrapperProjectionColumnSets.add(
                new ArgMinMaxProjectionValSetWrapper(true, DataSchema.ColumnDataType.LONG, blockValSet));
            projectionColTypes[i] = DataSchema.ColumnDataType.LONG;
            break;
          case TIMESTAMP:
            argMinMaxWrapperProjectionColumnSets.add(
                new ArgMinMaxProjectionValSetWrapper(true, DataSchema.ColumnDataType.TIMESTAMP, blockValSet));
            projectionColTypes[i] = DataSchema.ColumnDataType.LONG;
            break;
          case FLOAT:
            argMinMaxWrapperProjectionColumnSets.add(
                new ArgMinMaxProjectionValSetWrapper(true, DataSchema.ColumnDataType.FLOAT, blockValSet));
            projectionColTypes[i] = DataSchema.ColumnDataType.FLOAT;
            break;
          case DOUBLE:
            argMinMaxWrapperProjectionColumnSets.add(
                new ArgMinMaxProjectionValSetWrapper(true, DataSchema.ColumnDataType.DOUBLE, blockValSet));
            projectionColTypes[i] = DataSchema.ColumnDataType.DOUBLE;
            break;
          case STRING:
            argMinMaxWrapperProjectionColumnSets.add(
                new ArgMinMaxProjectionValSetWrapper(true, DataSchema.ColumnDataType.STRING, blockValSet));
            projectionColTypes[i] = DataSchema.ColumnDataType.STRING;
            break;
          case JSON:
            argMinMaxWrapperProjectionColumnSets.add(
                new ArgMinMaxProjectionValSetWrapper(true, DataSchema.ColumnDataType.JSON, blockValSet));
            projectionColTypes[i] = DataSchema.ColumnDataType.STRING;
            break;
          case BYTES:
            argMinMaxWrapperProjectionColumnSets.add(
                new ArgMinMaxProjectionValSetWrapper(true, DataSchema.ColumnDataType.BYTES, blockValSet));
            projectionColTypes[i] = DataSchema.ColumnDataType.BYTES;
            break;
          case BIG_DECIMAL:
            argMinMaxWrapperProjectionColumnSets.add(
                new ArgMinMaxProjectionValSetWrapper(true, DataSchema.ColumnDataType.BIG_DECIMAL, blockValSet));
            projectionColTypes[i] = DataSchema.ColumnDataType.BIG_DECIMAL;
            break;
          default:
            throw new IllegalStateException(
                "Cannot compute ArgMinMax projection on non-comparable type: " + blockValSet.getValueType());
        }
      } else {
        switch (blockValSet.getValueType()) {
          case INT:
            argMinMaxWrapperProjectionColumnSets.add(
                new ArgMinMaxProjectionValSetWrapper(false, DataSchema.ColumnDataType.INT_ARRAY, blockValSet));
            projectionColTypes[i] = DataSchema.ColumnDataType.INT_ARRAY;
            break;
          case BOOLEAN:
            argMinMaxWrapperProjectionColumnSets.add(
                new ArgMinMaxProjectionValSetWrapper(false, DataSchema.ColumnDataType.BOOLEAN_ARRAY, blockValSet));
            projectionColTypes[i] = DataSchema.ColumnDataType.INT_ARRAY;
            break;
          case LONG:
            argMinMaxWrapperProjectionColumnSets.add(
                new ArgMinMaxProjectionValSetWrapper(false, DataSchema.ColumnDataType.LONG_ARRAY, blockValSet));
            projectionColTypes[i] = DataSchema.ColumnDataType.LONG_ARRAY;
            break;
          case TIMESTAMP:
            argMinMaxWrapperProjectionColumnSets.add(
                new ArgMinMaxProjectionValSetWrapper(false, DataSchema.ColumnDataType.TIMESTAMP_ARRAY, blockValSet));
            projectionColTypes[i] = DataSchema.ColumnDataType.LONG_ARRAY;
            break;
          case FLOAT:
            argMinMaxWrapperProjectionColumnSets.add(
                new ArgMinMaxProjectionValSetWrapper(false, DataSchema.ColumnDataType.FLOAT_ARRAY, blockValSet));
            projectionColTypes[i] = DataSchema.ColumnDataType.FLOAT_ARRAY;
            break;
          case DOUBLE:
            argMinMaxWrapperProjectionColumnSets.add(
                new ArgMinMaxProjectionValSetWrapper(false, DataSchema.ColumnDataType.DOUBLE_ARRAY, blockValSet));
            projectionColTypes[i] = DataSchema.ColumnDataType.DOUBLE_ARRAY;
            break;
          case STRING:
            argMinMaxWrapperProjectionColumnSets.add(
                new ArgMinMaxProjectionValSetWrapper(false, DataSchema.ColumnDataType.STRING_ARRAY, blockValSet));
            projectionColTypes[i] = DataSchema.ColumnDataType.STRING_ARRAY;
            break;
          case BYTES:
            argMinMaxWrapperProjectionColumnSets.add(
                new ArgMinMaxProjectionValSetWrapper(false, DataSchema.ColumnDataType.BYTES_ARRAY, blockValSet));
            projectionColTypes[i] = DataSchema.ColumnDataType.BYTES_ARRAY;
            break;
          default:
            throw new IllegalStateException(
                "Cannot compute ArgMinMax projection on non-comparable type: " + blockValSet.getValueType());
        }
      }
    }
    // setup measuring column schema
    _projectionColumnSchema.set(new DataSchema(projectionColNames, projectionColTypes));
  }

  private void initializeMeasuringColumnValSet(Map<ExpressionContext, BlockValSet> blockValSetMap) {
    List<ArgMinMaxMeasuringValSetWrapper> argMinMaxWrapperMeasuringColumnSets =
        _argMinMaxWrapperMeasuringColumnSets.get();
    String[] measuringColNames = new String[_numMeasuringColumns];
    DataSchema.ColumnDataType[] measuringColTypes = new DataSchema.ColumnDataType[_numMeasuringColumns];
    for (int i = 0; i < _numMeasuringColumns; i++) {
      measuringColNames[i] = _measuringColumns.get(i).toString();
      ExpressionContext measuringColumn = _measuringColumns.get(i);
      BlockValSet blockValSet = blockValSetMap.get(measuringColumn);
      Preconditions.checkState(blockValSet.isSingleValue(), "ArgMinMax only supports single-valued"
          + " measuring columns");
      switch (blockValSet.getValueType()) {
        case INT:
          argMinMaxWrapperMeasuringColumnSets.add(
              new ArgMinMaxMeasuringValSetWrapper(true, DataSchema.ColumnDataType.INT, blockValSet));
          measuringColTypes[i] = DataSchema.ColumnDataType.INT;
          break;
        case BOOLEAN:
          argMinMaxWrapperMeasuringColumnSets.add(
              new ArgMinMaxMeasuringValSetWrapper(true, DataSchema.ColumnDataType.BOOLEAN, blockValSet));
          measuringColTypes[i] = DataSchema.ColumnDataType.INT;
          break;
        case LONG:
          argMinMaxWrapperMeasuringColumnSets.add(
              new ArgMinMaxMeasuringValSetWrapper(true, DataSchema.ColumnDataType.LONG, blockValSet));
          measuringColTypes[i] = DataSchema.ColumnDataType.LONG;
          break;
        case TIMESTAMP:
          argMinMaxWrapperMeasuringColumnSets.add(
              new ArgMinMaxMeasuringValSetWrapper(true, DataSchema.ColumnDataType.TIMESTAMP, blockValSet));
          measuringColTypes[i] = DataSchema.ColumnDataType.LONG;
          break;
        case FLOAT:
          argMinMaxWrapperMeasuringColumnSets.add(
              new ArgMinMaxMeasuringValSetWrapper(true, DataSchema.ColumnDataType.FLOAT, blockValSet));
          measuringColTypes[i] = DataSchema.ColumnDataType.FLOAT;
          break;
        case DOUBLE:
          argMinMaxWrapperMeasuringColumnSets.add(
              new ArgMinMaxMeasuringValSetWrapper(true, DataSchema.ColumnDataType.DOUBLE, blockValSet));
          measuringColTypes[i] = DataSchema.ColumnDataType.DOUBLE;
          break;
        case STRING:
          argMinMaxWrapperMeasuringColumnSets.add(
              new ArgMinMaxMeasuringValSetWrapper(true, DataSchema.ColumnDataType.STRING, blockValSet));
          measuringColTypes[i] = DataSchema.ColumnDataType.STRING;
          break;
        case BIG_DECIMAL:
          argMinMaxWrapperMeasuringColumnSets.add(
              new ArgMinMaxMeasuringValSetWrapper(true, DataSchema.ColumnDataType.BIG_DECIMAL, blockValSet));
          measuringColTypes[i] = DataSchema.ColumnDataType.BIG_DECIMAL;
          break;
        default:
          throw new IllegalStateException(
              "Cannot compute ArgMinMax measuring on non-comparable type: " + blockValSet.getValueType());
      }
    }
    // setup measuring column schema
    _measuringColumnSchema.set(new DataSchema(measuringColNames, measuringColTypes));
  }

  // This method is called when the docIdSet is empty meaning that there are no rows that match the filter.
  private void initializeForEmptyDocSet() {
    if (_schemaInitialized.get()) {
      return;
    }
    _schemaInitialized.set(true);
    String[] measuringColNames = new String[_numMeasuringColumns];
    DataSchema.ColumnDataType[] measuringColTypes = new DataSchema.ColumnDataType[_numMeasuringColumns];
    for (int i = 0; i < _numMeasuringColumns; i++) {
      measuringColNames[i] = _measuringColumns.get(i).toString();
      measuringColTypes[i] = DataSchema.ColumnDataType.STRING;
    }

    String[] projectionColNames = new String[_numProjectionColumns];
    DataSchema.ColumnDataType[] projectionColTypes = new DataSchema.ColumnDataType[_numProjectionColumns];
    for (int i = 0; i < _numProjectionColumns; i++) {
      projectionColNames[i] = _projectionColumns.get(i).toString();
      projectionColTypes[i] = DataSchema.ColumnDataType.STRING;
    }
    _measuringColumnSchema.set(new DataSchema(measuringColNames, measuringColTypes));
    _projectionColumnSchema.set(new DataSchema(projectionColNames, projectionColTypes));
  }

  @Override
  public void aggregateGroupBySV(int length, int[] groupKeyArray, GroupByResultHolder groupByResultHolder,
      Map<ExpressionContext, BlockValSet> blockValSetMap) {
    initializeWithNewDataBlocks(blockValSetMap);
    for (int i = 0; i < length; i++) {
      int groupKey = groupKeyArray[i];
      updateGroupByResult(groupByResultHolder, i, groupKey);
    }
  }

  private void updateGroupByResult(GroupByResultHolder groupByResultHolder, int i, int groupKey) {
    ArgMinMaxObject argMinMaxObject = groupByResultHolder.getResult(groupKey);
    if (argMinMaxObject == null) {
      argMinMaxObject = new ArgMinMaxObject(_measuringColumnSchema.get(), _projectionColumnSchema.get());
      groupByResultHolder.setValueForKey(groupKey, argMinMaxObject);
    }
    int compareResult = argMinMaxObject.compareAndSetKey(_argMinMaxWrapperMeasuringColumnSets.get(), i, _isMax);
    if (compareResult == 0) {
      argMinMaxObject.addVal(_argMinMaxWrapperProjectionColumnSets.get(), i);
    } else if (compareResult > 0) {
      argMinMaxObject.setToNewVal(_argMinMaxWrapperProjectionColumnSets.get(), i);
    }
  }

  @Override
  public void aggregateGroupByMV(int length, int[][] groupKeysArray, GroupByResultHolder groupByResultHolder,
      Map<ExpressionContext, BlockValSet> blockValSetMap) {
    initializeWithNewDataBlocks(blockValSetMap);
    for (int i = 0; i < length; i++) {
      for (int groupKey : groupKeysArray[i]) {
        updateGroupByResult(groupByResultHolder, i, groupKey);
      }
    }
  }

  @Override
  public ArgMinMaxObject extractAggregationResult(AggregationResultHolder aggregationResultHolder) {
    ArgMinMaxObject argMinMaxObject = aggregationResultHolder.getResult();
    if (argMinMaxObject == null) {
      initializeWithNewDataBlocks(null);
      return new ArgMinMaxObject(_measuringColumnSchema.get(), _projectionColumnSchema.get());
    } else {
      return argMinMaxObject;
    }
  }

  @Override
  public ArgMinMaxObject extractGroupByResult(GroupByResultHolder groupByResultHolder, int groupKey) {
    return groupByResultHolder.getResult(groupKey);
  }

  @Override
  public ArgMinMaxObject merge(ArgMinMaxObject intermediateResult1, ArgMinMaxObject intermediateResult2) {
    return intermediateResult1.merge(intermediateResult2, _isMax);
  }

  @Override
  public DataSchema.ColumnDataType getIntermediateResultColumnType() {
    return DataSchema.ColumnDataType.OBJECT;
  }

  @Override
  public ArgMinMaxObject extractFinalResult(ArgMinMaxObject argMinMaxObject) {
    return argMinMaxObject;
  }
}
