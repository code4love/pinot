/**
 * Copyright (C) 2014-2015 LinkedIn Corp. (pinot-core@linkedin.com)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *         http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.linkedin.pinot.core.segment.creator.impl;

import com.linkedin.pinot.common.data.FieldSpec;


/**
 * Jun 30, 2014
 *
 */
public class V1Constants {
  public static final String QUERY_RHS_DELIMITER = "\t\t";
  public static final String SEGMENT_CREATION_META = "creation.meta";
  public static final String VERSIONS_FILE = "versions.vr";
  public static final String VERSION = "segment,index.version";
  public static final String SEGMENT_DOWNLOAD_URL = "segment.download.url";
  public static final String SEGMENT_PUSH_TIME = "segment.push.time";
  public static final String SEGMENT_REFRESH_TIME = "segment.refresh.time";

  public static final String STARTREE_DIR = "startree";
  public static final String STARTREE_FILE = "startree.ser";
  public static final String STARTREE_ALL = "__ALL__";
  public static final Number STARTREE_ALL_NUMBER = 0;

  public static class Numbers {
    // null representatives
    public static final Integer NULL_INT = Integer.MIN_VALUE;
    public static final Long NULL_LONG = Long.MIN_VALUE;
    public static final Float NULL_FLOAT = Float.MIN_VALUE;
    public static final Double NULL_DOUBLE = Double.MIN_VALUE;
  }

  public static class Str {
    public static final char STRING_PAD_CHAR = '%';
    public static final java.lang.String CHAR_SET = "UTF-8";
    public static final String NULL_STRING = "nil";
    public static final String NULL_BOOLEAN = Boolean.toString(false);
  }

  public static class Idx {
    public static final int[] SORTED_INDEX_COLUMN_SIZE = new int[] { 4, 4 };
  }

  public static class Dict {
    public static final int[] INT_DICTIONARY_COL_SIZE = new int[] { 4 };
    public static final int[] LONG_DICTIONARY_COL_SIZE = new int[] { 8 };
    public static final int[] FLOAT_DICTIONARY_COL_SIZE = new int[] { 4 };
    public static final int[] DOUBLE_DICTIONARY_COL_SIZE = new int[] { 8 };
    public static final String FILE_EXTENTION = ".dict";

    public static int[] getSingleValueColumnSizeFor(FieldSpec spec) {
      switch (spec.getDataType()) {
        case INT:
          return INT_DICTIONARY_COL_SIZE;
        case FLOAT:
          return FLOAT_DICTIONARY_COL_SIZE;
        case DOUBLE:
          return DOUBLE_DICTIONARY_COL_SIZE;
        case LONG:
          return LONG_DICTIONARY_COL_SIZE;
        default:
          return new int[] {};
      }
    }

  }

  public static class Indexes {
    public static final String UN_SORTED_SV_FWD_IDX_FILE_EXTENTION = ".sv.unsorted.fwd";
    public static final String SORTED_FWD_IDX_FILE_EXTENTION = ".sv.sorted.fwd";
    public static final String UN_SORTED_MV_FWD_IDX_FILE_EXTENTION = ".mv.fwd";
    public static final String BITMAP_INVERTED_INDEX_FILE_EXTENSION = ".bitmap.inv";
    public static final String SORTED_INVERTED_INDEX_FILE_EXTENSION = ".sorted.inv";
    public static final String INTARRAY_INVERTED_INDEX_FILE_EXTENSION = ".intArray.inv";
  }

  public static class MetadataKeys {
    public static final String METADATA_FILE_NAME = "metadata.properties";

    public static class StarTree {
      public static final String SPLIT_ORDER = "startree.split.order";
      public static final String MAX_LEAF_RECORDS = "startree.max.leaf.records";
      public static final String SPLIT_EXCLUDES = "startree.split.excludes";
      public static final String EXCLUDED_DIMENSIONS = "startree.excluded.dimensions";
    }

    public static class Segment {
      public static final String SEGMENT_NAME = "segment.name";
      public static final String TABLE_NAME = "segment.table.name";
      public static final String DIMENSIONS = "segment.dimension.column.names";
      public static final String METRICS = "segment.metric.column.names";
      public static final String UNKNOWN_COLUMNS = "segment.unknown.column.names";
      public static final String TIME_COLUMN_NAME = "segment.time.column.name";
      public static final String TIME_UNIT = "segment.time.unit";
      public static final String TIME_INTERVAL = "segment.time.interval";
      public static final String CUSTOM_PROPERTIES_PREFIX = "segment.custom";
      public static final String SEGMENT_TOTAL_DOCS = "segment.total.docs";
      public static final String SEGMENT_CRC = "segment.crc";
      public static final String SEGMENT_CREATION_TIME = "segment.creation.time";

      // not using currently
      public static final String SEGMENT_INDEX_TYPE = "segment.index.type";
      public static final String SEGMENT_START_TIME = "segment.start.time";
      public static final String SEGMENT_END_TIME = "segment.end.time";
      public static final String SEGMENT_TIME_GRANULARITY = "segment.time.granularity";
    }

    public static class Column {
      public static final String CARDINALITY = "cardinality";
      public static final String TOTAL_DOCS = "totalDocs";
      public static final String DATA_TYPE = "dataType";
      public static final String BITS_PER_ELEMENT = "bitsPerElement";
      public static final String DICTIONARY_ELEMENT_SIZE = "lengthOfEachEntry";
      public static final String COLUMN_TYPE = "columnType";
      public static final String HAS_INVERTED_INDEX = "hasInvertedIndex";
      public static final String HAS_NULL_VALUE = "hasNullValue";
      public static final String HAS_DICTIONARY = "hasDictionary";

      public static final String IS_SORTED = "isSorted";
      public static final String IS_SINGLE_VALUED = "isSingleValues";
      public static final String MAX_MULTI_VALUE_ELEMTS = "maxNumberOfMultiValues";

      public static final String TOTAL_NUMBER_OF_ENTRIES = "totalNumberOfEntries";
      public static final String COLUMN_PROPS_KEY_PREFIX = "column.";

      public static String getKeyFor(String column, String key) {
        return COLUMN_PROPS_KEY_PREFIX + column + "." + key;
      }
    }
  }
}
