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
package com.linkedin.pinot.core.data.manager.config;

import org.apache.commons.configuration.Configuration;
import org.apache.commons.configuration.ConfigurationException;
import org.apache.commons.configuration.PropertiesConfiguration;

import com.linkedin.pinot.common.config.AbstractTableConfig;
import com.linkedin.pinot.common.config.TableNameBuilder;
import com.linkedin.pinot.common.metadata.segment.IndexLoadingConfigMetadata;
import com.linkedin.pinot.common.segment.ReadMode;
import com.linkedin.pinot.common.utils.CommonConstants.Helix.TableType;


/**
 * The config used for TableDataManager.
 *
 *
 */
public class TableDataManagerConfig {

  private static final String TABLE_DATA_MANAGER_NUM_QUERY_EXECUTOR_THREADS = "numQueryExecutorThreads";
  private static final String TABLE_DATA_MANAGER_TYPE = "dataManagerType";
  private static final String READ_MODE = "readMode";
  private static final String TABLE_DATA_MANAGER_DATA_DIRECTORY = "directory";
  private static final String TABLE_DATA_MANAGER_NAME = "name";

  private final Configuration _tableDataManagerConfig;

  public TableDataManagerConfig(Configuration tableDataManagerConfig) throws ConfigurationException {
    _tableDataManagerConfig = tableDataManagerConfig;
  }

  public String getReadMode() {
    return _tableDataManagerConfig.getString(READ_MODE);
  }

  public Configuration getConfig() {
    return _tableDataManagerConfig;
  }

  public String getTableDataManagerType() {
    return _tableDataManagerConfig.getString(TABLE_DATA_MANAGER_TYPE);
  }

  public String getDataDir() {
    return _tableDataManagerConfig.getString(TABLE_DATA_MANAGER_DATA_DIRECTORY);
  }

  public String getTableName() {
    return _tableDataManagerConfig.getString(TABLE_DATA_MANAGER_NAME);
  }

  public int getNumberOfTableQueryExecutorThreads() {
    return _tableDataManagerConfig.getInt(TABLE_DATA_MANAGER_NUM_QUERY_EXECUTOR_THREADS, 10);
  }

  public static TableDataManagerConfig getDefaultHelixTableDataManagerConfig(
      InstanceDataManagerConfig _instanceDataManagerConfig, String tableName) throws ConfigurationException {
    TableType tableType = TableNameBuilder.getTableTypeFromTableName(tableName);

    Configuration defaultConfig = new PropertiesConfiguration();
    defaultConfig.addProperty(TABLE_DATA_MANAGER_NAME, tableName);
    String dataDir = _instanceDataManagerConfig.getInstanceDataDir() + "/" + tableName;
    defaultConfig.addProperty(TABLE_DATA_MANAGER_DATA_DIRECTORY, dataDir);
    if (_instanceDataManagerConfig.getReadMode() != null) {
      defaultConfig.addProperty(READ_MODE, _instanceDataManagerConfig.getReadMode().toString());
    } else {
      defaultConfig.addProperty(READ_MODE, ReadMode.heap);
    }
    defaultConfig.addProperty(TABLE_DATA_MANAGER_NUM_QUERY_EXECUTOR_THREADS, 20);
    TableDataManagerConfig tableDataManagerConfig = new TableDataManagerConfig(defaultConfig);

    switch (tableType) {
      case OFFLINE:
        defaultConfig.addProperty(TABLE_DATA_MANAGER_TYPE, "offline");
        break;
      case REALTIME:
        defaultConfig.addProperty(TABLE_DATA_MANAGER_TYPE, "realtime");
        break;

      default:
        throw new UnsupportedOperationException("Not supported table type for - " + tableName);
    }
    return tableDataManagerConfig;
  }

  public void overrideConfigs(AbstractTableConfig tableConfig) {
    _tableDataManagerConfig.setProperty(READ_MODE, tableConfig.getIndexingConfig().getLoadMode().toLowerCase());
    _tableDataManagerConfig.setProperty(TABLE_DATA_MANAGER_NAME, tableConfig.getTableName());
    _tableDataManagerConfig.setProperty("metadata.loading.inverted.index.columns", tableConfig.getIndexingConfig().getInvertedIndexColumns());
  }

  public IndexLoadingConfigMetadata getIndexLoadingConfigMetadata() {
    IndexLoadingConfigMetadata indexLoadingConfigMetadata = new IndexLoadingConfigMetadata(_tableDataManagerConfig);
    return indexLoadingConfigMetadata;
  }
}
