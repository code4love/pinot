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
package com.linkedin.pinot.controller.helix.core;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;

import org.apache.commons.collections.CollectionUtils;
import org.apache.commons.collections.Predicate;
import org.apache.helix.AccessOption;
import org.apache.helix.HelixAdmin;
import org.apache.helix.HelixDataAccessor;
import org.apache.helix.HelixManager;
import org.apache.helix.PropertyKey;
import org.apache.helix.PropertyKey.Builder;
import org.apache.helix.ZNRecord;
import org.apache.helix.model.CurrentState;
import org.apache.helix.model.ExternalView;
import org.apache.helix.model.IdealState;
import org.apache.helix.model.InstanceConfig;
import org.apache.helix.model.LiveInstance;
import org.apache.helix.store.zk.ZkHelixPropertyStore;
import org.codehaus.jackson.JsonGenerationException;
import org.codehaus.jackson.JsonParseException;
import org.codehaus.jackson.JsonProcessingException;
import org.codehaus.jackson.map.JsonMappingException;
import org.json.JSONException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.linkedin.pinot.common.Utils;
import com.linkedin.pinot.common.config.AbstractTableConfig;
import com.linkedin.pinot.common.config.IndexingConfig;
import com.linkedin.pinot.common.config.OfflineTableConfig;
import com.linkedin.pinot.common.config.RealtimeTableConfig;
import com.linkedin.pinot.common.config.SegmentsValidationAndRetentionConfig;
import com.linkedin.pinot.common.config.TableCustomConfig;
import com.linkedin.pinot.common.config.TableNameBuilder;
import com.linkedin.pinot.common.config.Tenant;
import com.linkedin.pinot.common.config.TenantConfig;
import com.linkedin.pinot.common.data.Schema;
import com.linkedin.pinot.common.metadata.ZKMetadataProvider;
import com.linkedin.pinot.common.metadata.instance.InstanceZKMetadata;
import com.linkedin.pinot.common.metadata.segment.OfflineSegmentZKMetadata;
import com.linkedin.pinot.common.metadata.segment.RealtimeSegmentZKMetadata;
import com.linkedin.pinot.common.metadata.segment.SegmentZKMetadata;
import com.linkedin.pinot.common.segment.SegmentMetadata;
import com.linkedin.pinot.common.utils.CommonConstants;
import com.linkedin.pinot.controller.helix.core.sharding.SegmentAssignmentStrategy;
import com.linkedin.pinot.controller.helix.core.sharding.SegmentAssignmentStrategyFactory;
import com.linkedin.pinot.common.utils.CommonConstants.Helix.StateModel.BrokerOnlineOfflineStateModel;
import com.linkedin.pinot.common.utils.CommonConstants.Helix.StateModel.SegmentOnlineOfflineStateModel;
import com.linkedin.pinot.common.utils.CommonConstants.Helix.TableType;
import com.linkedin.pinot.common.utils.ControllerTenantNameBuilder;
import com.linkedin.pinot.common.utils.ServerType;
import com.linkedin.pinot.common.utils.TenantRole;
import com.linkedin.pinot.common.utils.ZkUtils;
import com.linkedin.pinot.common.utils.helix.HelixHelper;
import com.linkedin.pinot.common.utils.helix.PinotHelixPropertyStoreZnRecordProvider;
import com.linkedin.pinot.controller.ControllerConf;
import com.linkedin.pinot.controller.api.pojos.Instance;
import com.linkedin.pinot.controller.helix.core.PinotResourceManagerResponse.ResponseStatus;
import com.linkedin.pinot.controller.helix.core.util.HelixSetupUtils;
import com.linkedin.pinot.controller.helix.core.util.ZKMetadataUtils;
import com.linkedin.pinot.controller.helix.starter.HelixConfig;
import com.linkedin.pinot.core.segment.index.SegmentMetadataImpl;


/**
 * Sep 30, 2014
 */
public class PinotHelixResourceManager {

  private static final Logger LOGGER = LoggerFactory.getLogger(PinotHelixResourceManager.class);

  private String _zkBaseUrl;
  private String _helixClusterName;
  private HelixManager _helixZkManager;
  private HelixAdmin _helixAdmin;
  private String _helixZkURL;
  private String _instanceId;
  private ZkHelixPropertyStore<ZNRecord> _propertyStore;
  private String _localDiskDir;
  private SegmentDeletionManager _segmentDeletionManager = null;
  private long _externalViewOnlineToOfflineTimeout;
  private boolean _isSingleTenantCluster = true;

    private HelixDataAccessor _helixDataAccessor;
    Builder _keyBuilder;

    private static final Map<String, SegmentAssignmentStrategy> SEGMENT_ASSIGNMENT_STRATEGY_MAP =
            new HashMap<String, SegmentAssignmentStrategy>();

    @SuppressWarnings("unused")
    private PinotHelixResourceManager() {

    }

    public PinotHelixResourceManager(String zkURL, String helixClusterName, String controllerInstanceId,
                                     String localDiskDir, long externalViewOnlineToOfflineTimeout, boolean isSingleTenantCluster) {
        _zkBaseUrl = zkURL;
        _helixClusterName = helixClusterName;
        _instanceId = controllerInstanceId;
        _localDiskDir = localDiskDir;
        _externalViewOnlineToOfflineTimeout = externalViewOnlineToOfflineTimeout;
        _isSingleTenantCluster = isSingleTenantCluster;
    }

    public PinotHelixResourceManager(String zkURL, String helixClusterName, String controllerInstanceId,
                                     String localDiskDir) {
        this(zkURL, helixClusterName, controllerInstanceId, localDiskDir, 10000L, false);
    }

    public PinotHelixResourceManager(ControllerConf controllerConf) {
        this(controllerConf.getZkStr(), controllerConf.getHelixClusterName(), controllerConf.getControllerHost() + "_"
                + controllerConf.getControllerPort(), controllerConf.getDataDir(), controllerConf
                .getExternalViewOnlineToOfflineTimeout(), controllerConf.tenantIsolationEnabled());
    }

    public synchronized void start() throws Exception {
        _helixZkURL = HelixConfig.getAbsoluteZkPathForHelix(_zkBaseUrl);
        _helixZkManager = HelixSetupUtils.setup(_helixClusterName, _helixZkURL, _instanceId);
        _helixAdmin = _helixZkManager.getClusterManagmentTool();
        _propertyStore = ZkUtils.getZkPropertyStore(_helixZkManager, _helixClusterName);
        _helixDataAccessor = _helixZkManager.getHelixDataAccessor();
        _keyBuilder = _helixDataAccessor.keyBuilder();
        _segmentDeletionManager = new SegmentDeletionManager(_localDiskDir, _helixAdmin, _helixClusterName, _propertyStore);
        _externalViewOnlineToOfflineTimeout = 10000L;
        ZKMetadataProvider.setClusterTenantIsolationEnabled(_propertyStore, _isSingleTenantCluster);
    }

    public synchronized void stop() {
        _segmentDeletionManager.stop();
        _helixZkManager.disconnect();
    }

    public InstanceConfig getHelixInstanceConfig(String instanceId) {
        return _helixAdmin.getInstanceConfig(_helixClusterName, instanceId);
    }

    public List<String> getBrokerInstancesFor(String tableName) {
        String brokerTenantName = null;
        try {
            if (getAllTableNames().contains(TableNameBuilder.REALTIME_TABLE_NAME_BUILDER.forTable(tableName))) {
                brokerTenantName =
                        ZKMetadataProvider.getRealtimeTableConfig(getPropertyStore(), tableName).getTenantConfig().getBroker();
            }
            if (getAllTableNames().contains(TableNameBuilder.OFFLINE_TABLE_NAME_BUILDER.forTable(tableName))) {
                brokerTenantName =
                        ZKMetadataProvider.getOfflineTableConfig(getPropertyStore(), tableName).getTenantConfig().getBroker();
            }
        } catch (Exception e) {
            LOGGER.error("Caught exception", e);
            Utils.rethrowException(e);
        }
        final List<String> instanceIds = new ArrayList<String>();
        instanceIds.addAll(getAllInstancesForBrokerTenant(brokerTenantName));
        return instanceIds;
    }

    public InstanceZKMetadata getInstanceZKMetadata(String instanceId) {
        return ZKMetadataProvider.getInstanceZKMetadata(getPropertyStore(), instanceId);
    }

    public List<String> getAllRealtimeTables() {
        List<String> ret = _helixAdmin.getResourcesInCluster(_helixClusterName);
        CollectionUtils.filter(ret, new Predicate() {
            @Override
            public boolean evaluate(Object object) {
                if (object == null) {
                    return false;
                }
                if (object.toString().endsWith("_" + ServerType.REALTIME.toString())) {
                    return true;
                }
                return false;
            }
        });
        return ret;
    }

    public List<String> getAllTableNames() {
        return _helixAdmin.getResourcesInCluster(_helixClusterName);
    }

    public List<String> getAllInstanceNames() {
        return _helixAdmin.getInstancesInCluster(_helixClusterName);
    }

    /**
     * Returns all tables, remove brokerResource.
     */
    public List<String> getAllPinotTableNames() {
        List<String> tableNames = getAllTableNames();

        // Filter table names that are known to be non Pinot tables (ie. brokerResource)
        ArrayList<String> pinotTableNames = new ArrayList<String>();
        for (String tableName : tableNames) {
            if (CommonConstants.Helix.NON_PINOT_RESOURCE_RESOURCE_NAMES.contains(tableName)) {
                continue;
            }
            pinotTableNames.add(tableName);
        }

        return pinotTableNames;
    }

    public synchronized void restartTable(String tableName) {
        final Set<String> allInstances =
                HelixHelper.getAllInstancesForResource(HelixHelper.getTableIdealState(_helixZkManager, tableName));
        HelixHelper.setStateForInstanceSet(allInstances, _helixClusterName, _helixAdmin, false);
        HelixHelper.setStateForInstanceSet(allInstances, _helixClusterName, _helixAdmin, true);
    }

    private boolean ifExternalViewChangeReflectedForState(String tableName, String segmentName, String targerStates,
                                                          long timeOutInMills) {
        long timeOutTimeStamp = System.currentTimeMillis() + timeOutInMills;
        boolean isSucess = true;
        while (System.currentTimeMillis() < timeOutTimeStamp) {
            // Will try to read data every 2 seconds.
            try {
                Thread.sleep(2000);
            } catch (InterruptedException e) {
            }
            isSucess = true;
            ExternalView externalView = _helixAdmin.getResourceExternalView(_helixClusterName, tableName);
            Map<String, String> segmentStatsMap = externalView.getStateMap(segmentName);
            if (segmentStatsMap != null) {
                for (String instance : segmentStatsMap.keySet()) {
                    if (!segmentStatsMap.get(instance).equalsIgnoreCase(targerStates)) {
                        isSucess = false;
                    }
                }
            } else {
                isSucess = false;
            }
            if (isSucess) {
                break;
            }
        }
        return isSucess;
    }

    private boolean ifSegmentExisted(SegmentMetadata segmentMetadata) {
        if (segmentMetadata == null) {
            return false;
        }
        return _propertyStore.exists(
                ZKMetadataProvider.constructPropertyStorePathForSegment(
                        TableNameBuilder.OFFLINE_TABLE_NAME_BUILDER.forTable(segmentMetadata.getTableName()),
                        segmentMetadata.getName()), AccessOption.PERSISTENT);
    }

    private boolean ifRefreshAnExistedSegment(SegmentMetadata segmentMetadata) {
        OfflineSegmentZKMetadata offlineSegmentZKMetadata =
                ZKMetadataProvider.getOfflineSegmentZKMetadata(_propertyStore, segmentMetadata.getTableName(),
                        segmentMetadata.getName());
        if (offlineSegmentZKMetadata == null) {
            return false;
        }
        final SegmentMetadata existedSegmentMetadata = new SegmentMetadataImpl(offlineSegmentZKMetadata);
        if (segmentMetadata.getIndexCreationTime() <= existedSegmentMetadata.getIndexCreationTime()) {
            return false;
        }
        if (segmentMetadata.getCrc().equals(existedSegmentMetadata.getCrc())) {
            return false;
        }
        return true;
    }

    public synchronized PinotResourceManagerResponse addInstance(Instance instance) {
        final PinotResourceManagerResponse resp = new PinotResourceManagerResponse();
        final List<String> instances = HelixHelper.getAllInstances(_helixAdmin, _helixClusterName);
        if (instances.contains(instance.toInstanceId())) {
            resp.status = ResponseStatus.failure;
            resp.message = "Instance " + instance + " already exists.";
            return resp;
        } else {
            _helixAdmin.addInstance(_helixClusterName, instance.toInstanceConfig());
            resp.status = ResponseStatus.success;
            resp.message = "";
            return resp;
        }
    }

    /**
     * TODO : Due to the issue of helix, if remove the segment from idealState directly, the node won't get transaction,
     * so we first disable the segment, then remove it from idealState, then enable the segment. This will trigger transactions.
     * After the helix patch, we can refine the logic here by directly drop the segment from idealState.
     *
     * @param tableName
     * @param segmentId
     * @return
     */
    public synchronized PinotResourceManagerResponse deleteSegment(final String tableName, final String segmentId) {
        LOGGER.info("Trying to delete segment: {} for table: {} ", segmentId, tableName);
        final PinotResourceManagerResponse res = new PinotResourceManagerResponse();
        try {
            IdealState idealState = _helixAdmin.getResourceIdealState(_helixClusterName, tableName);
            if (idealState.getPartitionSet().contains(segmentId)) {
                LOGGER.info("Trying to delete segment: {} from IdealStates", segmentId);
                HelixHelper.removeSegmentFromIdealState(_helixZkManager, tableName, segmentId);
            } else {
                res.message = "Segment " + segmentId + " not in IDEALSTATE.";
                LOGGER.info("Segment: {} is not in IDEALSTATE", segmentId);
            }
            _segmentDeletionManager.deleteSegment(tableName, segmentId);

            res.message = "Segment successfully deleted.";
            res.status = ResponseStatus.success;
        } catch (final Exception e) {
            LOGGER.error("Caught exception while deleting segment", e);
            res.status = ResponseStatus.failure;
            res.message = e.getMessage();
        }
        return res;
    }

    // **** End Broker level operations ****
    public void startInstances(List<String> instances) {
        HelixHelper.setStateForInstanceList(instances, _helixClusterName, _helixAdmin, false);
        HelixHelper.setStateForInstanceList(instances, _helixClusterName, _helixAdmin, true);
    }

    @Override
    public String toString() {
        return "yay! i am alive and kicking, clusterName is : " + _helixClusterName + " zk url is : " + _zkBaseUrl;
    }

    public ZkHelixPropertyStore<ZNRecord> getPropertyStore() {
        return _propertyStore;
    }

    public HelixAdmin getHelixAdmin() {
        return _helixAdmin;
    }

    public String getHelixClusterName() {
        return _helixClusterName;
    }

    public boolean isLeader() {
        return _helixZkManager.isLeader();
    }

    public HelixManager getHelixZkManager() {
        return _helixZkManager;
    }

    public PinotResourceManagerResponse updateBrokerTenant(Tenant tenant) {
        PinotResourceManagerResponse res = new PinotResourceManagerResponse();
        String brokerTenantTag = ControllerTenantNameBuilder.getBrokerTenantNameForTenant(tenant.getTenantName());
        List<String> instancesInClusterWithTag =
                _helixAdmin.getInstancesInClusterWithTag(_helixClusterName, brokerTenantTag);
        if (instancesInClusterWithTag.size() > tenant.getNumberOfInstances()) {
            return scaleDownBroker(tenant, res, brokerTenantTag, instancesInClusterWithTag);
        }
        if (instancesInClusterWithTag.size() < tenant.getNumberOfInstances()) {
            return scaleUpBroker(tenant, res, brokerTenantTag, instancesInClusterWithTag);
        }
        res.status = ResponseStatus.success;
        return res;
    }

    private PinotResourceManagerResponse scaleUpBroker(Tenant tenant, PinotResourceManagerResponse res,
                                                       String brokerTenantTag, List<String> instancesInClusterWithTag) {
        List<String> unTaggedInstanceList = getOnlineUnTaggedBrokerInstanceList();
        int numberOfInstancesToAdd = tenant.getNumberOfInstances() - instancesInClusterWithTag.size();
        if (unTaggedInstanceList.size() < numberOfInstancesToAdd) {
            res.status = ResponseStatus.failure;
            res.message =
                    "Failed to allocate broker instances to Tag : " + tenant.getTenantName()
                            + ", Current number of untagged broker instances : " + unTaggedInstanceList.size()
                            + ", Current number of tagged broker instances : " + instancesInClusterWithTag.size()
                            + ", Request asked number is : " + tenant.getNumberOfInstances();
            LOGGER.error(res.message);
            return res;
        }
        for (int i = 0; i < numberOfInstancesToAdd; ++i) {
            String instanceName = unTaggedInstanceList.get(i);
            retagInstance(instanceName, CommonConstants.Helix.UNTAGGED_BROKER_INSTANCE, brokerTenantTag);
            // Update idealState by adding new instance to table mapping.
            addInstanceToBrokerIdealState(brokerTenantTag, instanceName);
        }
        res.status = ResponseStatus.success;
        return res;
    }

    private void addInstanceToBrokerIdealState(String brokerTenantTag, String instanceName) {
        IdealState tableIdealState =
                _helixAdmin.getResourceIdealState(_helixClusterName, CommonConstants.Helix.BROKER_RESOURCE_INSTANCE);
        for (String tableName : tableIdealState.getPartitionSet()) {
            if (TableNameBuilder.getTableTypeFromTableName(tableName) == TableType.OFFLINE) {
                String brokerTag =
                        ControllerTenantNameBuilder.getBrokerTenantNameForTenant(ZKMetadataProvider
                                .getOfflineTableConfig(getPropertyStore(), tableName).getTenantConfig().getBroker());
                if (brokerTag.equals(brokerTenantTag)) {
                    tableIdealState.setPartitionState(tableName, instanceName, BrokerOnlineOfflineStateModel.ONLINE);
                }
            } else if (TableNameBuilder.getTableTypeFromTableName(tableName) == TableType.REALTIME) {
                String brokerTag =
                        ControllerTenantNameBuilder.getBrokerTenantNameForTenant(ZKMetadataProvider
                                .getRealtimeTableConfig(getPropertyStore(), tableName).getTenantConfig().getBroker());
                if (brokerTag.equals(brokerTenantTag)) {
                    tableIdealState.setPartitionState(tableName, instanceName, BrokerOnlineOfflineStateModel.ONLINE);
                }
            }
        }
        _helixAdmin.setResourceIdealState(_helixClusterName, CommonConstants.Helix.BROKER_RESOURCE_INSTANCE,
                tableIdealState);
    }

    private PinotResourceManagerResponse scaleDownBroker(Tenant tenant, PinotResourceManagerResponse res,
                                                         String brokerTenantTag, List<String> instancesInClusterWithTag) {
        int numberBrokersToUntag = instancesInClusterWithTag.size() - tenant.getNumberOfInstances();
        for (int i = 0; i < numberBrokersToUntag; ++i) {
            retagInstance(instancesInClusterWithTag.get(i), brokerTenantTag, CommonConstants.Helix.UNTAGGED_BROKER_INSTANCE);
            _helixAdmin.dropInstance(_helixClusterName, new InstanceConfig(instancesInClusterWithTag.get(i)));
        }
        res.status = ResponseStatus.success;
        return res;
    }

    private void retagInstance(String instanceName, String oldTag, String newTag) {
        _helixAdmin.removeInstanceTag(_helixClusterName, instanceName, oldTag);
        _helixAdmin.addInstanceTag(_helixClusterName, instanceName, newTag);
    }

    public PinotResourceManagerResponse updateServerTenant(Tenant serverTenant) {
        PinotResourceManagerResponse res = new PinotResourceManagerResponse();
        String realtimeServerTag = ControllerTenantNameBuilder.getRealtimeTenantNameForTenant(serverTenant.getTenantName());
        List<String> taggedRealtimeServers = _helixAdmin.getInstancesInClusterWithTag(_helixClusterName, realtimeServerTag);
        String offlineServerTag = ControllerTenantNameBuilder.getOfflineTenantNameForTenant(serverTenant.getTenantName());
        List<String> taggedOfflineServers = _helixAdmin.getInstancesInClusterWithTag(_helixClusterName, offlineServerTag);
        Set<String> allServingServers = new HashSet<String>();
        allServingServers.addAll(taggedOfflineServers);
        allServingServers.addAll(taggedRealtimeServers);
        boolean isCurrentTenantColocated =
                (allServingServers.size() < taggedOfflineServers.size() + taggedRealtimeServers.size());
        if (isCurrentTenantColocated != serverTenant.isColoated()) {
            res.status = ResponseStatus.failure;
            res.message = "Not support different colocated type request for update request: " + serverTenant;
            LOGGER.error(res.message);
            return res;
        }
        if (serverTenant.getNumberOfInstances() < allServingServers.size()
                || serverTenant.getOfflineInstances() < taggedOfflineServers.size()
                || serverTenant.getRealtimeInstances() < taggedRealtimeServers.size()) {
            return scaleDownServer(serverTenant, res, taggedRealtimeServers, taggedOfflineServers, allServingServers);
        }
        return scaleUpServerTenant(serverTenant, res, realtimeServerTag, taggedRealtimeServers, offlineServerTag,
                taggedOfflineServers, allServingServers);
    }

    private PinotResourceManagerResponse scaleUpServerTenant(Tenant serverTenant, PinotResourceManagerResponse res,
                                                             String realtimeServerTag, List<String> taggedRealtimeServers, String offlineServerTag,
                                                             List<String> taggedOfflineServers, Set<String> allServingServers) {
        int incInstances = serverTenant.getNumberOfInstances() - allServingServers.size();
        List<String> unTaggedInstanceList = getOnlineUnTaggedServerInstanceList();
        if (unTaggedInstanceList.size() < incInstances) {
            res.status = ResponseStatus.failure;
            res.message =
                    "Failed to allocate hardware resouces with tenant info: " + serverTenant
                            + ", Current number of untagged instances : " + unTaggedInstanceList.size()
                            + ", Current number of servering instances : " + allServingServers.size()
                            + ", Current number of tagged offline server instances : " + taggedOfflineServers.size()
                            + ", Current number of tagged realtime server instances : " + taggedRealtimeServers.size();
            LOGGER.error(res.message);
            return res;
        }
        if (serverTenant.isColoated()) {
            return updateColocatedServerTenant(serverTenant, res, realtimeServerTag, taggedRealtimeServers, offlineServerTag,
                    taggedOfflineServers, incInstances, unTaggedInstanceList);
        } else {
            return updateIndependentServerTenant(serverTenant, res, realtimeServerTag, taggedRealtimeServers,
                    offlineServerTag, taggedOfflineServers, incInstances, unTaggedInstanceList);
        }

    }

    private PinotResourceManagerResponse updateIndependentServerTenant(Tenant serverTenant,
                                                                       PinotResourceManagerResponse res, String realtimeServerTag, List<String> taggedRealtimeServers,
                                                                       String offlineServerTag, List<String> taggedOfflineServers, int incInstances, List<String> unTaggedInstanceList) {
        int incOffline = serverTenant.getOfflineInstances() - taggedOfflineServers.size();
        int incRealtime = serverTenant.getRealtimeInstances() - taggedRealtimeServers.size();
        for (int i = 0; i < incOffline; ++i) {
            retagInstance(unTaggedInstanceList.get(i), CommonConstants.Helix.UNTAGGED_SERVER_INSTANCE, offlineServerTag);
        }
        for (int i = incOffline; i < incOffline + incRealtime; ++i) {
            String instanceName = unTaggedInstanceList.get(i);
            retagInstance(instanceName, CommonConstants.Helix.UNTAGGED_SERVER_INSTANCE, realtimeServerTag);
            // TODO: update idealStates & instanceZkMetadata
        }
        res.status = ResponseStatus.success;
        return res;
    }

    private PinotResourceManagerResponse updateColocatedServerTenant(Tenant serverTenant,
                                                                     PinotResourceManagerResponse res, String realtimeServerTag, List<String> taggedRealtimeServers,
                                                                     String offlineServerTag, List<String> taggedOfflineServers, int incInstances, List<String> unTaggedInstanceList) {
        int incOffline = serverTenant.getOfflineInstances() - taggedOfflineServers.size();
        int incRealtime = serverTenant.getRealtimeInstances() - taggedRealtimeServers.size();
        taggedRealtimeServers.removeAll(taggedOfflineServers);
        taggedOfflineServers.removeAll(taggedRealtimeServers);
        for (int i = 0; i < incOffline; ++i) {
            if (i < incInstances) {
                retagInstance(unTaggedInstanceList.get(i), CommonConstants.Helix.UNTAGGED_SERVER_INSTANCE, offlineServerTag);
            } else {
                _helixAdmin.addInstanceTag(_helixClusterName, taggedRealtimeServers.get(i - incInstances), offlineServerTag);
            }
        }
        for (int i = incOffline; i < incOffline + incRealtime; ++i) {
            if (i < incInstances) {
                retagInstance(unTaggedInstanceList.get(i), CommonConstants.Helix.UNTAGGED_SERVER_INSTANCE, realtimeServerTag);
                // TODO: update idealStates & instanceZkMetadata
            } else {
                _helixAdmin.addInstanceTag(_helixClusterName, taggedOfflineServers.get(i - Math.max(incInstances, incOffline)),
                        realtimeServerTag);
                // TODO: update idealStates & instanceZkMetadata
            }
        }
        res.status = ResponseStatus.success;
        return res;
    }

    private PinotResourceManagerResponse scaleDownServer(Tenant serverTenant, PinotResourceManagerResponse res,
                                                         List<String> taggedRealtimeServers, List<String> taggedOfflineServers, Set<String> allServingServers) {
        res.status = ResponseStatus.failure;
        res.message =
                "Not support to size down the current server cluster with tenant info: " + serverTenant
                        + ", Current number of servering instances : " + allServingServers.size()
                        + ", Current number of tagged offline server instances : " + taggedOfflineServers.size()
                        + ", Current number of tagged realtime server instances : " + taggedRealtimeServers.size();
        LOGGER.error(res.message);
        return res;
    }

    public boolean isTenantExisted(String tenantName) {
        if (!_helixAdmin.getInstancesInClusterWithTag(_helixClusterName,
                ControllerTenantNameBuilder.getBrokerTenantNameForTenant(tenantName)).isEmpty()) {
            return true;
        }
        if (!_helixAdmin.getInstancesInClusterWithTag(_helixClusterName,
                ControllerTenantNameBuilder.getOfflineTenantNameForTenant(tenantName)).isEmpty()) {
            return true;
        }
        if (!_helixAdmin.getInstancesInClusterWithTag(_helixClusterName,
                ControllerTenantNameBuilder.getRealtimeTenantNameForTenant(tenantName)).isEmpty()) {
            return true;
        }
        return false;
    }

    public boolean isBrokerTenantDeletable(String tenantName) {
        String brokerTag = ControllerTenantNameBuilder.getBrokerTenantNameForTenant(tenantName);
        Set<String> taggedInstances =
                new HashSet<String>(_helixAdmin.getInstancesInClusterWithTag(_helixClusterName, brokerTag));
        String brokerName = CommonConstants.Helix.BROKER_RESOURCE_INSTANCE;
        IdealState brokerIdealState = _helixAdmin.getResourceIdealState(_helixClusterName, brokerName);
        for (String partition : brokerIdealState.getPartitionSet()) {
            for (String instance : brokerIdealState.getInstanceSet(partition)) {
                if (taggedInstances.contains(instance)) {
                    return false;
                }
            }
        }
        return true;
    }

    public boolean isServerTenantDeletable(String tenantName) {
        Set<String> taggedInstances =
                new HashSet<String>(_helixAdmin.getInstancesInClusterWithTag(_helixClusterName,
                        ControllerTenantNameBuilder.getOfflineTenantNameForTenant(tenantName)));
        taggedInstances.addAll(_helixAdmin.getInstancesInClusterWithTag(_helixClusterName,
                ControllerTenantNameBuilder.getRealtimeTenantNameForTenant(tenantName)));
        for (String tableName : getAllTableNames()) {
            if (tableName.equals(CommonConstants.Helix.BROKER_RESOURCE_INSTANCE)) {
                continue;
            }
            IdealState tableIdealState = _helixAdmin.getResourceIdealState(_helixClusterName, tableName);
            for (String partition : tableIdealState.getPartitionSet()) {
                for (String instance : tableIdealState.getInstanceSet(partition)) {
                    if (taggedInstances.contains(instance)) {
                        return false;
                    }
                }
            }
        }
        return true;
    }

    public Set<String> getAllBrokerTenantNames() {
        Set<String> tenantSet = new HashSet<String>();
        List<String> instancesInCluster = _helixAdmin.getInstancesInCluster(_helixClusterName);
        for (String instanceName : instancesInCluster) {
            InstanceConfig config = _helixDataAccessor.getProperty(_keyBuilder.instanceConfig(instanceName));
            for (String tag : config.getTags()) {
                if (tag.equals(CommonConstants.Helix.UNTAGGED_BROKER_INSTANCE)
                        || tag.equals(CommonConstants.Helix.UNTAGGED_SERVER_INSTANCE)) {
                    continue;
                }
                if (ControllerTenantNameBuilder.getTenantRoleFromTenantName(tag) == TenantRole.BROKER) {
                    tenantSet.add(ControllerTenantNameBuilder.getExternalTenantName(tag));
                }
            }
        }
        return tenantSet;
    }

    public Set<String> getAllServerTenantNames() {
        Set<String> tenantSet = new HashSet<String>();
        List<String> instancesInCluster = _helixAdmin.getInstancesInCluster(_helixClusterName);
        for (String instanceName : instancesInCluster) {
            InstanceConfig config = _helixDataAccessor.getProperty(_keyBuilder.instanceConfig(instanceName));
            for (String tag : config.getTags()) {
                if (tag.equals(CommonConstants.Helix.UNTAGGED_BROKER_INSTANCE)
                        || tag.equals(CommonConstants.Helix.UNTAGGED_SERVER_INSTANCE)) {
                    continue;
                }
                if (ControllerTenantNameBuilder.getTenantRoleFromTenantName(tag) == TenantRole.SERVER) {
                    tenantSet.add(ControllerTenantNameBuilder.getExternalTenantName(tag));
                }
            }
        }
        return tenantSet;
    }

    private List<String> getTagsForInstance(String instanceName) {
        InstanceConfig config = _helixDataAccessor.getProperty(_keyBuilder.instanceConfig(instanceName));
        return config.getTags();
    }

    public PinotResourceManagerResponse createServerTenant(Tenant serverTenant) {
        PinotResourceManagerResponse res = new PinotResourceManagerResponse();
        int numberOfInstances = serverTenant.getNumberOfInstances();
        List<String> unTaggedInstanceList = getOnlineUnTaggedServerInstanceList();
        if (unTaggedInstanceList.size() < numberOfInstances) {
            res.status = ResponseStatus.failure;
            res.message =
                    "Failed to allocate server instances to Tag : " + serverTenant.getTenantName()
                            + ", Current number of untagged server instances : " + unTaggedInstanceList.size()
                            + ", Request asked number is : " + serverTenant.getNumberOfInstances();
            LOGGER.error(res.message);
            return res;
        } else {
            if (serverTenant.isColoated()) {
                assignColocatedServerTenant(serverTenant, numberOfInstances, unTaggedInstanceList);
            } else {
                assignIndependentServerTenant(serverTenant, numberOfInstances, unTaggedInstanceList);
            }
        }
        res.status = ResponseStatus.success;
        return res;
    }

    private void assignIndependentServerTenant(Tenant serverTenant, int numberOfInstances,
                                               List<String> unTaggedInstanceList) {
        String offlineServerTag = ControllerTenantNameBuilder.getOfflineTenantNameForTenant(serverTenant.getTenantName());
        for (int i = 0; i < serverTenant.getOfflineInstances(); i++) {
            retagInstance(unTaggedInstanceList.get(i), CommonConstants.Helix.UNTAGGED_SERVER_INSTANCE, offlineServerTag);
        }
        String realtimeServerTag = ControllerTenantNameBuilder.getRealtimeTenantNameForTenant(serverTenant.getTenantName());
        for (int i = 0; i < serverTenant.getRealtimeInstances(); i++) {
            retagInstance(unTaggedInstanceList.get(i + serverTenant.getOfflineInstances()),
                    CommonConstants.Helix.UNTAGGED_SERVER_INSTANCE, realtimeServerTag);
        }
    }

    private void assignColocatedServerTenant(Tenant serverTenant, int numberOfInstances, List<String> unTaggedInstanceList) {
        int cnt = 0;
        String offlineServerTag = ControllerTenantNameBuilder.getOfflineTenantNameForTenant(serverTenant.getTenantName());
        for (int i = 0; i < serverTenant.getOfflineInstances(); i++) {
            retagInstance(unTaggedInstanceList.get(cnt++), CommonConstants.Helix.UNTAGGED_SERVER_INSTANCE, offlineServerTag);
        }
        String realtimeServerTag = ControllerTenantNameBuilder.getRealtimeTenantNameForTenant(serverTenant.getTenantName());
        for (int i = 0; i < serverTenant.getRealtimeInstances(); i++) {
            retagInstance(unTaggedInstanceList.get(cnt++), CommonConstants.Helix.UNTAGGED_SERVER_INSTANCE, realtimeServerTag);
            if (cnt == numberOfInstances) {
                cnt = 0;
            }
        }
    }

    public PinotResourceManagerResponse createBrokerTenant(Tenant brokerTenant) {
        PinotResourceManagerResponse res = new PinotResourceManagerResponse();
        List<String> unTaggedInstanceList = getOnlineUnTaggedBrokerInstanceList();
        int numberOfInstances = brokerTenant.getNumberOfInstances();
        if (unTaggedInstanceList.size() < numberOfInstances) {
            res.status = ResponseStatus.failure;
            res.message =
                    "Failed to allocate broker instances to Tag : " + brokerTenant.getTenantName()
                            + ", Current number of untagged server instances : " + unTaggedInstanceList.size()
                            + ", Request asked number is : " + brokerTenant.getNumberOfInstances();
            LOGGER.error(res.message);
            return res;
        }
        String brokerTag = ControllerTenantNameBuilder.getBrokerTenantNameForTenant(brokerTenant.getTenantName());
        for (int i = 0; i < brokerTenant.getNumberOfInstances(); ++i) {
            retagInstance(unTaggedInstanceList.get(i), CommonConstants.Helix.UNTAGGED_BROKER_INSTANCE, brokerTag);
        }
        res.status = ResponseStatus.success;
        return res;

    }

    public PinotResourceManagerResponse deleteOfflineServerTenantFor(String tenantName) {
        PinotResourceManagerResponse response = new PinotResourceManagerResponse();
        String offlineTenantTag = ControllerTenantNameBuilder.getOfflineTenantNameForTenant(tenantName);
        List<String> instancesInClusterWithTag =
                _helixAdmin.getInstancesInClusterWithTag(_helixClusterName, offlineTenantTag);
        for (String instanceName : instancesInClusterWithTag) {
            _helixAdmin.removeInstanceTag(_helixClusterName, instanceName, offlineTenantTag);
            if (getTagsForInstance(instanceName).isEmpty()) {
                _helixAdmin.addInstanceTag(_helixClusterName, instanceName, CommonConstants.Helix.UNTAGGED_SERVER_INSTANCE);
            }
        }
        response.status = ResponseStatus.success;
        return response;
    }

    public PinotResourceManagerResponse deleteRealtimeServerTenantFor(String tenantName) {
        PinotResourceManagerResponse response = new PinotResourceManagerResponse();
        String realtimeTenantTag = ControllerTenantNameBuilder.getRealtimeTenantNameForTenant(tenantName);
        List<String> instancesInClusterWithTag =
                _helixAdmin.getInstancesInClusterWithTag(_helixClusterName, realtimeTenantTag);
        for (String instanceName : instancesInClusterWithTag) {
            _helixAdmin.removeInstanceTag(_helixClusterName, instanceName, realtimeTenantTag);
            if (getTagsForInstance(instanceName).isEmpty()) {
                _helixAdmin.addInstanceTag(_helixClusterName, instanceName, CommonConstants.Helix.UNTAGGED_SERVER_INSTANCE);
            }
        }
        response.status = ResponseStatus.success;
        return response;
    }

    public PinotResourceManagerResponse deleteBrokerTenantFor(String tenantName) {
        PinotResourceManagerResponse response = new PinotResourceManagerResponse();
        String brokerTag = ControllerTenantNameBuilder.getBrokerTenantNameForTenant(tenantName);
        List<String> instancesInClusterWithTag = _helixAdmin.getInstancesInClusterWithTag(_helixClusterName, brokerTag);
        for (String instance : instancesInClusterWithTag) {
            retagInstance(instance, brokerTag, CommonConstants.Helix.UNTAGGED_BROKER_INSTANCE);
        }
        return response;
    }

    public Set<String> getAllInstancesForServerTenant(String tenantName) {
        Set<String> instancesSet = new HashSet<String>();
        instancesSet.addAll(_helixAdmin.getInstancesInClusterWithTag(_helixClusterName,
                ControllerTenantNameBuilder.getOfflineTenantNameForTenant(tenantName)));
        instancesSet.addAll(_helixAdmin.getInstancesInClusterWithTag(_helixClusterName,
                ControllerTenantNameBuilder.getRealtimeTenantNameForTenant(tenantName)));
        return instancesSet;
    }

    public Set<String> getAllInstancesForBrokerTenant(String tenantName) {
        Set<String> instancesSet = new HashSet<String>();
        instancesSet.addAll(_helixAdmin.getInstancesInClusterWithTag(_helixClusterName,
                ControllerTenantNameBuilder.getBrokerTenantNameForTenant(tenantName)));
        return instancesSet;
    }

    /**
     * API 2.0
     */

    /**
     * Schema APIs
     */
    /**
     *
     * @param schema
     * @throws IllegalArgumentException
     * @throws IllegalAccessException
     */
    public void addSchema(Schema schema) throws IllegalArgumentException, IllegalAccessException {
        ZNRecord record = Schema.toZNRecord(schema);
        String name = schema.getSchemaName();
        PinotHelixPropertyStoreZnRecordProvider propertyStoreHelper =
                PinotHelixPropertyStoreZnRecordProvider.forSchema(_propertyStore);
        propertyStoreHelper.set(name, record);
    }

    /**
   * Delete the given schema.
   * @param schema The schema to be deleted.
   * @return True on success, false otherwise.
   */
  public boolean deleteSchema(Schema schema) {
    if (schema != null) {
      String propertyStorePath = ZKMetadataProvider.constructPropertyStorePathForSchema(schema.getSchemaName());
      if (_propertyStore.exists(propertyStorePath, AccessOption.PERSISTENT)) {
        _propertyStore.remove(propertyStorePath, AccessOption.PERSISTENT);
        return true;
      }
    }
    return false;
  }
  /**
     *
     * @param schemaName
     * @return
     * @throws JsonParseException
     * @throws JsonMappingException
     * @throws IOException
     */
    public Schema getSchema(String schemaName) throws JsonParseException, JsonMappingException, IOException {
        PinotHelixPropertyStoreZnRecordProvider propertyStoreHelper =
                PinotHelixPropertyStoreZnRecordProvider.forSchema(_propertyStore);
        LOGGER.info("found schema {} ", schemaName);
        ZNRecord record = propertyStoreHelper.get(schemaName);
        return record != null ? Schema.fromZNRecord(record) : null;
    }

    /**
     *
     * @return
     */
    public List<String> getSchemaNames() {
        return _propertyStore.getChildNames(PinotHelixPropertyStoreZnRecordProvider.forSchema(_propertyStore)
                .getRelativePath(), AccessOption.PERSISTENT);
    }

    /**
     * Table APIs
     */

    public void addTable(AbstractTableConfig config) throws JsonGenerationException, JsonMappingException, IOException {
        TenantConfig tenantConfig = null;
        TableType type = TableType.valueOf(config.getTableType().toUpperCase());
        if (isSingleTenantCluster()) {
            tenantConfig = new TenantConfig();
            tenantConfig.setBroker(ControllerTenantNameBuilder
                    .getBrokerTenantNameForTenant(ControllerTenantNameBuilder.DEFAULT_TENANT_NAME));
            switch (type) {
                case OFFLINE:
                    tenantConfig.setServer(ControllerTenantNameBuilder
                            .getOfflineTenantNameForTenant(ControllerTenantNameBuilder.DEFAULT_TENANT_NAME));
                    break;
                case REALTIME:
                    tenantConfig.setServer(ControllerTenantNameBuilder
                            .getRealtimeTenantNameForTenant(ControllerTenantNameBuilder.DEFAULT_TENANT_NAME));
                    break;
                default:
                    throw new RuntimeException("UnSupported table type");
            }
            config.setTenantConfig(tenantConfig);
        } else {
            tenantConfig = config.getTenantConfig();
            if (tenantConfig.getBroker() == null || tenantConfig.getServer() == null) {
                throw new RuntimeException("missing tenant configs");
            }
        }

        SegmentsValidationAndRetentionConfig segmentsConfig = config.getValidationConfig();
        switch (type) {
            case OFFLINE:
                final String offlineTableName = config.getTableName();

                // now lets build an ideal state
                LOGGER.info("building empty ideal state for table : " + offlineTableName);
                final IdealState offlineIdealState =
                        PinotTableIdealStateBuilder.buildEmptyIdealStateFor(offlineTableName,
                                Integer.parseInt(segmentsConfig.getReplication()), _helixAdmin, _helixClusterName);
                LOGGER.info("adding table via the admin");
                _helixAdmin.addResource(_helixClusterName, offlineTableName, offlineIdealState);
                LOGGER.info("successfully added the table : " + offlineTableName + " to the cluster");

                // lets add table configs
                ZKMetadataProvider.setOfflineTableConfig(_propertyStore, offlineTableName,
                        AbstractTableConfig.toZnRecord(config));

                _propertyStore.create(ZKMetadataProvider.constructPropertyStorePathForResource(offlineTableName), new ZNRecord(
                        offlineTableName), AccessOption.PERSISTENT);
                break;
            case REALTIME:
                final String realtimeTableName = config.getTableName();
                // lets add table configs

                ZKMetadataProvider.setRealtimeTableConfig(_propertyStore, realtimeTableName,
                        AbstractTableConfig.toZnRecord(config));
                // now lets build an ideal state
                LOGGER.info("building empty ideal state for table : " + realtimeTableName);
                final IdealState realtimeIdealState =
                        PinotTableIdealStateBuilder.buildInitialRealtimeIdealStateFor(realtimeTableName, config, _helixAdmin,
                                _helixClusterName, _propertyStore);
                LOGGER.info("adding table via the admin");
                _helixAdmin.addResource(_helixClusterName, realtimeTableName, realtimeIdealState);
                LOGGER.info("successfully added the table : " + realtimeTableName + " to the cluster");
                // Create Empty PropertyStore path
                _propertyStore.create(ZKMetadataProvider.constructPropertyStorePathForResource(realtimeTableName),
                        new ZNRecord(realtimeTableName), AccessOption.PERSISTENT);
                break;
            default:
                throw new RuntimeException("UnSupported table type");
        }
        handleBrokerResource(config);
    }

    private void setTableConfig(AbstractTableConfig config, String tableNameWithSuffix, TableType type)
            throws JsonGenerationException, JsonMappingException, IOException {
        if (type == TableType.REALTIME) {
            ZKMetadataProvider.setRealtimeTableConfig(_propertyStore, tableNameWithSuffix,
                    AbstractTableConfig.toZnRecord(config));
        } else {
            ZKMetadataProvider.setOfflineTableConfig(_propertyStore, tableNameWithSuffix,
                    AbstractTableConfig.toZnRecord(config));
        }
    }

    public void updateMetadataConfigFor(String tableName, TableType type, TableCustomConfig newConfigs) throws Exception {
        String actualTableName = new TableNameBuilder(type).forTable(tableName);
        AbstractTableConfig config;
        if (type == TableType.REALTIME) {
            config = ZKMetadataProvider.getRealtimeTableConfig(getPropertyStore(), actualTableName);
        } else {
            config = ZKMetadataProvider.getOfflineTableConfig(getPropertyStore(), actualTableName);
        }
        if (config == null) {
            throw new RuntimeException("tableName : " + tableName + " of type : " + type + " not found");
        }
        config.setCustomConfigs(newConfigs);
        setTableConfig(config, actualTableName, type);
    }

    public void updateSegmentsValidationAndRetentionConfigFor(String tableName, TableType type,
                                                              SegmentsValidationAndRetentionConfig newConfigs) throws Exception {
        String actualTableName = new TableNameBuilder(type).forTable(tableName);
        AbstractTableConfig config;
        if (type == TableType.REALTIME) {
            config = ZKMetadataProvider.getRealtimeTableConfig(getPropertyStore(), actualTableName);
        } else {
            config = ZKMetadataProvider.getOfflineTableConfig(getPropertyStore(), actualTableName);
        }
        if (config == null) {
            throw new RuntimeException("tableName : " + tableName + " of type : " + type + " not found");
        }
        config.setValidationConfig(newConfigs);

        setTableConfig(config, actualTableName, type);
    }

    public void updateIndexingConfigFor(String tableName, TableType type, IndexingConfig newConfigs) throws Exception {
        String actualTableName = new TableNameBuilder(type).forTable(tableName);
        AbstractTableConfig config;
        if (type == TableType.REALTIME) {
            config = ZKMetadataProvider.getRealtimeTableConfig(getPropertyStore(), actualTableName);
            if (config != null) {
                ((RealtimeTableConfig) config).setIndexConfig(newConfigs);
            }
        } else {
            config = ZKMetadataProvider.getOfflineTableConfig(getPropertyStore(), actualTableName);
            if (config != null) {
                ((OfflineTableConfig) config).setIndexConfig(newConfigs);
            }
        }
        if (config == null) {
            throw new RuntimeException("tableName : " + tableName + " of type : " + type + " not found");
        }

        setTableConfig(config, actualTableName, type);
    }

    private void handleBrokerResource(AbstractTableConfig tableConfig) {
        try {
            String brokerTenant =
                    ControllerTenantNameBuilder.getBrokerTenantNameForTenant(tableConfig.getTenantConfig().getBroker());
            if (_helixAdmin.getInstancesInClusterWithTag(_helixClusterName, brokerTenant).isEmpty()) {
                throw new RuntimeException("broker tenant : " + tableConfig.getTenantConfig().getBroker() + " is not existed!");
            }
            LOGGER.info("Trying to update BrokerDataResource IdealState!");
            final IdealState idealState =
                    _helixAdmin.getResourceIdealState(_helixClusterName, CommonConstants.Helix.BROKER_RESOURCE_INSTANCE);
            String tableName = tableConfig.getTableName();
            for (String instanceName : _helixAdmin.getInstancesInClusterWithTag(_helixClusterName, brokerTenant)) {
                idealState.setPartitionState(tableName, instanceName, BrokerOnlineOfflineStateModel.ONLINE);
            }
            if (idealState != null) {
                _helixAdmin
                        .setResourceIdealState(_helixClusterName, CommonConstants.Helix.BROKER_RESOURCE_INSTANCE, idealState);
            }
        } catch (final Exception e) {
            LOGGER.warn("Caught exception while creating broker", e);
        }
    }

    public void deleteOfflineTable(String tableName) {
        final String offlineTableName = TableNameBuilder.OFFLINE_TABLE_NAME_BUILDER.forTable(tableName);
        // Remove from brokerResource
        HelixHelper.removeResourceFromBrokerIdealState(_helixZkManager, offlineTableName);
        // Delete data table
        if (!_helixAdmin.getResourcesInCluster(_helixClusterName).contains(offlineTableName)) {
            return;
        }
        // remove from property store
        ZKMetadataProvider.removeResourceSegmentsFromPropertyStore(getPropertyStore(), offlineTableName);
        ZKMetadataProvider.removeResourceConfigFromPropertyStore(getPropertyStore(), offlineTableName);

        // dropping table
        _helixAdmin.dropResource(_helixClusterName, offlineTableName);
    }

    public void deleteRealtimeTable(String tableName) {
        final String realtimeTableName = TableNameBuilder.REALTIME_TABLE_NAME_BUILDER.forTable(tableName);
        // Remove from brokerResource
        HelixHelper.removeResourceFromBrokerIdealState(_helixZkManager, realtimeTableName);
        // Delete data table
        if (!_helixAdmin.getResourcesInCluster(_helixClusterName).contains(realtimeTableName)) {
            return;
        }
        // remove from property store
        ZKMetadataProvider.removeResourceSegmentsFromPropertyStore(getPropertyStore(), realtimeTableName);
        ZKMetadataProvider.removeResourceConfigFromPropertyStore(getPropertyStore(), realtimeTableName);
        // Remove groupId/PartitionId mapping for realtime table type.
        for (String instance : getAllInstancesForTable(realtimeTableName)) {
            InstanceZKMetadata instanceZKMetadata = ZKMetadataProvider.getInstanceZKMetadata(getPropertyStore(), instance);
            instanceZKMetadata.removeResource(realtimeTableName);
            ZKMetadataProvider.setInstanceZKMetadata(getPropertyStore(), instanceZKMetadata);
        }

        // dropping table
        _helixAdmin.dropResource(_helixClusterName, realtimeTableName);
    }

    /**
     * Toggle the status of the table between OFFLINE and ONLINE.
     *
     * @param tableName: Name of the table for which to toggle the status.
     * @param status: True for ONLINE and False for OFFLINE.
     * @return
     */
    public PinotResourceManagerResponse toggleTableState(String tableName, boolean status) {
        if (!_helixAdmin.getResourcesInCluster(_helixClusterName).contains(tableName)) {
            return new PinotResourceManagerResponse("Error: Table " + tableName + " not found.", false);
        }
        _helixAdmin.enableResource(_helixClusterName, tableName, status);
        return (status) ? new PinotResourceManagerResponse("Table " + tableName + " successfully enabled.", true)
                : new PinotResourceManagerResponse("Table " + tableName + " successfully disabled.", true);
    }

    /**
     * Drop the table from helix cluster.
     *
     * @param tableName: Name of table to be dropped.
     * @return
     */
    public PinotResourceManagerResponse dropTable(String tableName) {
        if (!_helixAdmin.getResourcesInCluster(_helixClusterName).contains(tableName)) {
            return new PinotResourceManagerResponse("Error: Table " + tableName + " not found.", false);
        }

        if (getAllSegmentsForResource(tableName).size() != 0) {
            return new PinotResourceManagerResponse("Error: Table " + tableName + " has segments, drop them first.", false);
        }

        _helixAdmin.dropResource(_helixClusterName, tableName);

        // remove from property store
        ZKMetadataProvider.removeResourceSegmentsFromPropertyStore(getPropertyStore(), tableName);
        ZKMetadataProvider.removeResourceConfigFromPropertyStore(getPropertyStore(), tableName);

        return new PinotResourceManagerResponse("Table " + tableName + " successfully dropped.", true);
    }

  public PinotResourceManagerResponse addSegment(final SegmentMetadata segmentMetadata, String downloadUrl) {
    final PinotResourceManagerResponse res = new PinotResourceManagerResponse();
    try {
      if (!matchTableName(segmentMetadata)) {
        throw new RuntimeException("Reject segment: table name is not registered." + " table name: "
            + segmentMetadata.getTableName() + "\n");
      }
      if (ifSegmentExisted(segmentMetadata)) {
        if (ifRefreshAnExistedSegment(segmentMetadata)) {
          OfflineSegmentZKMetadata offlineSegmentZKMetadata =
              ZKMetadataProvider.getOfflineSegmentZKMetadata(_propertyStore, segmentMetadata.getTableName(),
                  segmentMetadata.getName());

          offlineSegmentZKMetadata = ZKMetadataUtils.updateSegmentMetadata(offlineSegmentZKMetadata, segmentMetadata);
          offlineSegmentZKMetadata.setDownloadUrl(downloadUrl);
          offlineSegmentZKMetadata.setRefreshTime(System.currentTimeMillis());
          ZKMetadataProvider.setOfflineSegmentZKMetadata(_propertyStore, offlineSegmentZKMetadata);
          LOGGER.info("Refresh segment : " + offlineSegmentZKMetadata.getSegmentName() + " to Property store");
          if (updateExistedSegment(offlineSegmentZKMetadata)) {
            res.status = ResponseStatus.success;
          } else {
            LOGGER.error("Failed to refresh segment {}, marking crc and creation time as invalid",
                offlineSegmentZKMetadata.getSegmentName());
            offlineSegmentZKMetadata.setCrc(-1L);
            offlineSegmentZKMetadata.setCreationTime(-1L);
            ZKMetadataProvider.setOfflineSegmentZKMetadata(_propertyStore, offlineSegmentZKMetadata);
          }
        } else {
          String msg =
              "Not refreshing identical segment " + segmentMetadata.getName() + " with creation time "
                  + segmentMetadata.getIndexCreationTime() + " and crc " + segmentMetadata.getCrc();
          LOGGER.info(msg);
          res.status = ResponseStatus.success;
          res.message = msg;
        }
      } else {
        OfflineSegmentZKMetadata offlineSegmentZKMetadata = new OfflineSegmentZKMetadata();
        offlineSegmentZKMetadata = ZKMetadataUtils.updateSegmentMetadata(offlineSegmentZKMetadata, segmentMetadata);
        offlineSegmentZKMetadata.setDownloadUrl(downloadUrl);
        offlineSegmentZKMetadata.setPushTime(System.currentTimeMillis());
        ZKMetadataProvider.setOfflineSegmentZKMetadata(_propertyStore, offlineSegmentZKMetadata);
        LOGGER.info("Added segment : " + offlineSegmentZKMetadata.getSegmentName() + " to Property store");

        addNewOfflineSegment(segmentMetadata);
        res.status = ResponseStatus.success;
      }
    } catch (final Exception e) {
      LOGGER.error("Caught exception while adding segment", e);
      res.status = ResponseStatus.failure;
      res.message = e.getMessage();
    }

    return res;
  }

    private Set<String> getAllInstancesForTable(String tableName) {
        Set<String> instanceSet = new HashSet<String>();
        IdealState tableIdealState = _helixAdmin.getResourceIdealState(_helixClusterName, tableName);
        for (String partition : tableIdealState.getPartitionSet()) {
            instanceSet.addAll(tableIdealState.getInstanceSet(partition));
        }
        return instanceSet;
    }

    /**
     * Helper method to add the passed in offline segment to the helix cluster.
     * - Gets the segment name and the table name from the passed in segment meta-data.
     * - Identifies the instance set onto which the segment needs to be added, based on
     *   segment assignment strategy and replicas in the table config in the property-store.
     * - Updates ideal state such that the new segment is assigned to required set of instances as per
     *    the segment assignment strategy and replicas.
     *
     * @param segmentMetadata Meta-data for the segment, used to access segmentName and tableName.
     * @throws JsonParseException
     * @throws JsonMappingException
     * @throws JsonProcessingException
     * @throws JSONException
     * @throws IOException
     */
    private void addNewOfflineSegment(final SegmentMetadata segmentMetadata) throws JsonParseException,
            JsonMappingException, JsonProcessingException, JSONException, IOException {
        final AbstractTableConfig offlineTableConfig =
                ZKMetadataProvider.getOfflineTableConfig(_propertyStore, segmentMetadata.getTableName());

        final String segmentName = segmentMetadata.getName();
        final String offlineTableName =
                TableNameBuilder.OFFLINE_TABLE_NAME_BUILDER.forTable(segmentMetadata.getTableName());

        if (!SEGMENT_ASSIGNMENT_STRATEGY_MAP.containsKey(offlineTableName)) {
            SEGMENT_ASSIGNMENT_STRATEGY_MAP.put(offlineTableName, SegmentAssignmentStrategyFactory
                    .getSegmentAssignmentStrategy(offlineTableConfig.getValidationConfig().getSegmentAssignmentStrategy()));
        }
        final SegmentAssignmentStrategy segmentAssignmentStrategy = SEGMENT_ASSIGNMENT_STRATEGY_MAP.get(offlineTableName);

        // Passing a callable to this api to avoid helixHelper having which is in pinot-common having to
        // depend upon pinot-controller.
        Callable<List<String>> getInstancesForSegment = new Callable<List<String>>() {
            @Override
            public List<String> call() throws Exception {
                final IdealState currentIdealState = _helixAdmin.getResourceIdealState(_helixClusterName, offlineTableName);
                final Set<String> currentInstanceSet = currentIdealState.getInstanceSet(segmentName);

                if (currentInstanceSet.isEmpty()) {
                    final String serverTenant =
                            ControllerTenantNameBuilder.getOfflineTenantNameForTenant(offlineTableConfig.getTenantConfig()
                                    .getServer());
                    final int replicas = Integer.parseInt(offlineTableConfig.getValidationConfig().getReplication());
                    return segmentAssignmentStrategy.getAssignedInstances(_helixAdmin, _helixClusterName, segmentMetadata,
                            replicas, serverTenant);
                } else {
                    return new ArrayList<String>(currentIdealState.getInstanceSet(segmentName));
                }
            }
        };

        HelixHelper.addSegmentToIdealState(_helixZkManager, offlineTableName, segmentName, getInstancesForSegment);
    }

    /**
     * Returns true if the table name specified in the segment meta data has a corresponding
     * realtime or offline table in the helix cluster
     *
     * @param segmentMetadata Meta-data for the segment
     * @return
     */
    private boolean matchTableName(SegmentMetadata segmentMetadata) {
        if (segmentMetadata == null || segmentMetadata.getTableName() == null) {
            LOGGER.error("SegmentMetadata or table name is null");
            return false;
        }
        if ("realtime".equalsIgnoreCase(segmentMetadata.getIndexType())) {
            if (getAllTableNames().contains(
                    TableNameBuilder.REALTIME_TABLE_NAME_BUILDER.forTable(segmentMetadata.getTableName()))) {
                return true;
            }
        } else {
            if (getAllTableNames().contains(
                    TableNameBuilder.OFFLINE_TABLE_NAME_BUILDER.forTable(segmentMetadata.getTableName()))) {
                return true;
            }
        }
        LOGGER.error("Table {} is not registered", segmentMetadata.getTableName());
        return false;
    }

    private boolean updateExistedSegment(SegmentZKMetadata segmentZKMetadata) {
        final String tableName;
        if (segmentZKMetadata instanceof RealtimeSegmentZKMetadata) {
            tableName = TableNameBuilder.REALTIME_TABLE_NAME_BUILDER.forTable(segmentZKMetadata.getTableName());
        } else {
            tableName = TableNameBuilder.OFFLINE_TABLE_NAME_BUILDER.forTable(segmentZKMetadata.getTableName());
        }
        final String segmentName = segmentZKMetadata.getSegmentName();

        HelixDataAccessor helixDataAccessor = _helixZkManager.getHelixDataAccessor();
        PropertyKey idealStatePropertyKey = _keyBuilder.idealStates(tableName);

        // Set all partitions to offline to unload them from the servers
        boolean updateSuccessful;
        do {
            final IdealState idealState = _helixAdmin.getResourceIdealState(_helixClusterName, tableName);
            final Set<String> instanceSet = idealState.getInstanceSet(segmentName);
            for (final String instance : instanceSet) {
                idealState.setPartitionState(segmentName, instance, "OFFLINE");
            }
            updateSuccessful = helixDataAccessor.updateProperty(idealStatePropertyKey, idealState);
        } while (!updateSuccessful);

        // Check that the ideal state has been written to ZK
        IdealState updatedIdealState = _helixAdmin.getResourceIdealState(_helixClusterName, tableName);
        Map<String, String> instanceStateMap = updatedIdealState.getInstanceStateMap(segmentName);
        for (String state : instanceStateMap.values()) {
            if (!"OFFLINE".equals(state)) {
                LOGGER.error("Failed to write OFFLINE ideal state!");
                return false;
            }
        }

        // Wait until the partitions are offline in the external view
        LOGGER.info("Wait until segment - " + segmentName + " to be OFFLINE in ExternalView");
        if (!ifExternalViewChangeReflectedForState(tableName, segmentName, "OFFLINE", _externalViewOnlineToOfflineTimeout)) {
            LOGGER.error("Cannot get OFFLINE state to be reflected on ExternalView changed for segment: " + segmentName);
            return false;
        }

        // Set all partitions to online so that they load the new segment data
        do {
            final IdealState idealState = _helixAdmin.getResourceIdealState(_helixClusterName, tableName);
            final Set<String> instanceSet = idealState.getInstanceSet(segmentName);
            for (final String instance : instanceSet) {
                idealState.setPartitionState(segmentName, instance, "ONLINE");
            }
            updateSuccessful = helixDataAccessor.updateProperty(idealStatePropertyKey, idealState);
        } while (!updateSuccessful);

        // Check that the ideal state has been written to ZK
        updatedIdealState = _helixAdmin.getResourceIdealState(_helixClusterName, tableName);
        instanceStateMap = updatedIdealState.getInstanceStateMap(segmentName);
        for (String state : instanceStateMap.values()) {
            if (!"ONLINE".equals(state)) {
                LOGGER.error("Failed to write ONLINE ideal state!");
                return false;
            }
        }

        LOGGER.info("Refresh is done for segment - " + segmentName);
        return true;
    }

    /*
     *  fetch list of segments assigned to a give table from ideal state
     */
    public List<String> getAllSegmentsForResource(String tableName) {
        List<String> segmentsInResource = new ArrayList<String>();
        switch (TableNameBuilder.getTableTypeFromTableName(tableName)) {
            case REALTIME:
                for (RealtimeSegmentZKMetadata segmentZKMetadata : ZKMetadataProvider.getRealtimeSegmentZKMetadataListForTable(
                        getPropertyStore(), tableName)) {
                    segmentsInResource.add(segmentZKMetadata.getSegmentName());
                }

                break;
            case OFFLINE:
                for (OfflineSegmentZKMetadata segmentZKMetadata : ZKMetadataProvider.getOfflineSegmentZKMetadataListForTable(
                        getPropertyStore(), tableName)) {
                    segmentsInResource.add(segmentZKMetadata.getSegmentName());
                }
                break;
            default:
                break;
        }
        return segmentsInResource;
    }

    public Map<String, List<String>> getInstanceToSegmentsInATableMap(String tableName) {
        Map<String, List<String>> instancesToSegmentsMap = new HashMap<String, List<String>>();
        IdealState is = _helixAdmin.getResourceIdealState(_helixClusterName, tableName);
        Set<String> segments = is.getPartitionSet();

        for (String segment : segments) {
            Set<String> instances = is.getInstanceSet(segment);
            for (String instance : instances) {
                if (instancesToSegmentsMap.containsKey(instance)) {
                    instancesToSegmentsMap.get(instance).add(segment);
                } else {
                    List<String> a = new ArrayList<String>();
                    a.add(segment);
                    instancesToSegmentsMap.put(instance, a);
                }
            }
        }

        return instancesToSegmentsMap;
    }

    /**
     * Toggle the status of segment between ONLINE (enable = true) and OFFLINE (enable = FALSE).
     *
     * @param tableName: Name of table to which the segment belongs.
     * @param segmentName: Name of segment for which to toggle the status.
     * @param enable: True for ONLINE, False for OFFLINE.
     * @return
     */
    public PinotResourceManagerResponse toggleSegmentState(String tableName, String segmentName, boolean enable,
                                                           long timeOutInSeconds) {
        String status = (enable) ? "ONLINE" : "OFFLINE";

        HelixDataAccessor helixDataAccessor = _helixZkManager.getHelixDataAccessor();
        PropertyKey idealStatePropertyKey = _keyBuilder.idealStates(tableName);

        boolean updateSuccessful;
        long deadline = System.currentTimeMillis() + 1000 * timeOutInSeconds;

        // Set all partitions to offline to unload them from the servers
        do {
            final IdealState idealState = _helixAdmin.getResourceIdealState(_helixClusterName, tableName);
            final Set<String> instanceSet = idealState.getInstanceSet(segmentName);
            if (instanceSet == null || instanceSet.isEmpty()) {
                return new PinotResourceManagerResponse("Segment " + segmentName + " not found.", false);
            }
            for (final String instance : instanceSet) {
                idealState.setPartitionState(segmentName, instance, status);
            }
            updateSuccessful = helixDataAccessor.updateProperty(idealStatePropertyKey, idealState);
        } while (!updateSuccessful && (System.currentTimeMillis() <= deadline));

        // Check that the ideal state has been updated.
        IdealState updatedIdealState = _helixAdmin.getResourceIdealState(_helixClusterName, tableName);
        Map<String, String> instanceStateMap = updatedIdealState.getInstanceStateMap(segmentName);
        for (String state : instanceStateMap.values()) {
            if (!status.equals(state)) {
                return new PinotResourceManagerResponse("Error: External View does not reflect ideal State, timed out (10s).",
                        false);
            }
        }

        // Wait until the partitions are offline in the external view
        if (!ifExternalViewChangeReflectedForState(tableName, segmentName, status, _externalViewOnlineToOfflineTimeout)) {
            return new PinotResourceManagerResponse("Error: Failed to update external view, timeout", false);
        }

        return new PinotResourceManagerResponse(("Success: Segment " + segmentName + " is now " + status), true);
    }

    public PinotResourceManagerResponse dropSegment(String tableName, String segmentName) {
        long timeOutInSeconds = 10;

        // Put the segment in offline state first, and then delete the segment.
        PinotResourceManagerResponse disableResponse = toggleSegmentState(tableName, segmentName, false, timeOutInSeconds);
        if (!disableResponse.isSuccessfull()) {
            return disableResponse;
        }
        return deleteSegment(tableName, segmentName);
    }

    public boolean hasRealtimeTable(String tableName) {
        String actualTableName = tableName + "_REALTIME";
        return getAllPinotTableNames().contains(actualTableName);
    }

    public boolean hasOfflineTable(String tableName) {
        String actualTableName = tableName + "_OFFLINE";
        return getAllPinotTableNames().contains(actualTableName);
    }

    public AbstractTableConfig getTableConfig(String tableName, TableType type) throws JsonParseException,
            JsonMappingException, JsonProcessingException, JSONException, IOException {
        String actualTableName = new TableNameBuilder(type).forTable(tableName);
        AbstractTableConfig config = null;
        if (type == TableType.REALTIME) {
            config = ZKMetadataProvider.getRealtimeTableConfig(getPropertyStore(), actualTableName);
        } else {
            config = ZKMetadataProvider.getOfflineTableConfig(getPropertyStore(), actualTableName);
        }
        return config;
    }

    public List<String> getServerInstancesForTable(String tableName, TableType type) throws JsonParseException,
            JsonMappingException, JsonProcessingException, JSONException, IOException {
        String actualTableName = new TableNameBuilder(type).forTable(tableName);
        AbstractTableConfig config = null;
        if (type == TableType.REALTIME) {
            config = ZKMetadataProvider.getRealtimeTableConfig(getPropertyStore(), actualTableName);
        } else {
            config = ZKMetadataProvider.getOfflineTableConfig(getPropertyStore(), actualTableName);
        }
        String serverTenantName =
                ControllerTenantNameBuilder.getTenantName(config.getTenantConfig().getServer(), type.getServerType());
        List<String> serverInstances = _helixAdmin.getInstancesInClusterWithTag(_helixClusterName, serverTenantName);
        return serverInstances;
    }

    public List<String> getBrokerInstancesForTable(String tableName, TableType type) throws JsonParseException,
            JsonMappingException, JsonProcessingException, JSONException, IOException {
        String actualTableName = new TableNameBuilder(type).forTable(tableName);
        AbstractTableConfig config = null;
        if (type == TableType.REALTIME) {
            config = ZKMetadataProvider.getRealtimeTableConfig(getPropertyStore(), actualTableName);
        } else {
            config = ZKMetadataProvider.getOfflineTableConfig(getPropertyStore(), actualTableName);
        }
        String brokerTenantName =
                ControllerTenantNameBuilder.getBrokerTenantNameForTenant(config.getTenantConfig().getBroker());
        List<String> serverInstances = _helixAdmin.getInstancesInClusterWithTag(_helixClusterName, brokerTenantName);
        return serverInstances;
    }

    public PinotResourceManagerResponse enableInstance(String instanceName) {
        return toogleInstance(instanceName, true, 10);
    }

    public PinotResourceManagerResponse disableInstance(String instanceName) {
        return toogleInstance(instanceName, false, 10);
    }

    /**
     * Drop the instance from helix cluster. Instance will not be dropped if:
     * - It is a live instance.
     * - Has at least one ONLINE segment.
     *
     * @param instanceName: Name of the instance to be dropped.
     * @return
     */
    public PinotResourceManagerResponse dropInstance(String instanceName) {
        if (!instanceExists(instanceName)) {
            return new PinotResourceManagerResponse("Instance " + instanceName + " does not exist.", false);
        }

        HelixDataAccessor helixDataAccessor = _helixZkManager.getHelixDataAccessor();
        LiveInstance liveInstance = helixDataAccessor.getProperty(_keyBuilder.liveInstance(instanceName));

        if (liveInstance != null) {
            PropertyKey currentStatesKey =
                    _keyBuilder.currentStates(instanceName, liveInstance.getSessionId());
            List<CurrentState> currentStates = _helixDataAccessor.getChildValues(currentStatesKey);

            if (currentStates != null) {
                for (CurrentState currentState : currentStates) {
                    for (String state : currentState.getPartitionStateMap().values()) {
                        if (state.equalsIgnoreCase(SegmentOnlineOfflineStateModel.ONLINE)) {
                            return new PinotResourceManagerResponse(("Instance " + instanceName + " has online partitions"), false);
                        }
                    }
                }
            } else {
                return new PinotResourceManagerResponse("Cannot drop live instance " + instanceName
                        + " please stop the instance first.", false);
            }
        }

        // Disable the instance first.
        toogleInstance(instanceName, false, 10);
        _helixAdmin.dropInstance(_helixClusterName, getHelixInstanceConfig(instanceName));

        return new PinotResourceManagerResponse("Instance " + instanceName + " dropped.", true);
    }

    /**
     * Toggle the status of an Instance between OFFLINE and ONLINE.
     * Keeps checking until ideal-state is successfully updated or times out.
     *
     * @param instanceName: Name of Instance for which the status needs to be toggled.
     * @param toggle: 'True' for ONLINE 'False' for OFFLINE.
     * @param timeOutInSeconds: Time-out for setting ideal-state.
     * @return
     */
    public PinotResourceManagerResponse toogleInstance(String instanceName, boolean toggle, int timeOutInSeconds) {
        if (!instanceExists(instanceName)) {
            return new PinotResourceManagerResponse("Instance " + instanceName + " does not exist.", false);
        }

        _helixAdmin.enableInstance(_helixClusterName, instanceName, toggle);
        long deadline = System.currentTimeMillis() + 1000 * timeOutInSeconds;
        boolean toggleSucceed = false;
        String beforeToggleStates =
                (toggle) ? SegmentOnlineOfflineStateModel.OFFLINE : SegmentOnlineOfflineStateModel.ONLINE;

        while (System.currentTimeMillis() < deadline) {
            toggleSucceed = true;
            PropertyKey liveInstanceKey = _keyBuilder.liveInstance(instanceName);
            LiveInstance liveInstance = _helixDataAccessor.getProperty(liveInstanceKey);
            if (liveInstance == null) {
                if (toggle) {
                    return PinotResourceManagerResponse.FAILURE_RESPONSE;
                } else {
                    return PinotResourceManagerResponse.SUCCESS_RESPONSE;
                }
            }
            PropertyKey instanceCurrentStatesKey =
                    _keyBuilder.currentStates(instanceName, liveInstance.getSessionId());
            List<CurrentState> instanceCurrentStates = _helixDataAccessor.getChildValues(instanceCurrentStatesKey);
            if (instanceCurrentStates == null) {
                return PinotResourceManagerResponse.SUCCESS_RESPONSE;
            } else {
                for (CurrentState currentState : instanceCurrentStates) {
                    for (String state : currentState.getPartitionStateMap().values()) {
                        if (beforeToggleStates.equals(state)) {
                            toggleSucceed = false;
                        }
                    }
                }
            }
            if (toggleSucceed) {
                return (toggle) ? new PinotResourceManagerResponse("Instance " + instanceName + " enabled.", true)
                        : new PinotResourceManagerResponse("Instance " + instanceName + " disabled.", true);
            } else {
                try {
                    Thread.sleep(500);
                } catch (InterruptedException e) {
                }
            }
        }
        return new PinotResourceManagerResponse("Instance enable/disable failed, timeout.", false);
    }

    /**
     * Check if an Instance exists in the Helix cluster.
     *
     * @param instanceName: Name of instance to check.
     * @return True if instance exists in the Helix cluster, False otherwise.
     */
    public boolean instanceExists(String instanceName) {
        HelixDataAccessor helixDataAccessor = _helixZkManager.getHelixDataAccessor();
        InstanceConfig config = helixDataAccessor.getProperty(_keyBuilder.instanceConfig(instanceName));
        return (config != null);
    }

    public boolean isSingleTenantCluster() {
        return _isSingleTenantCluster;
    }

    /**
     * Computes the broker nodes that are untagged and free to be used.
     * @return List of online untagged broker instances.
     */
    public List<String> getOnlineUnTaggedBrokerInstanceList() {

        final List<String> instanceList =
                _helixAdmin.getInstancesInClusterWithTag(_helixClusterName, CommonConstants.Helix.UNTAGGED_BROKER_INSTANCE);
        final List<String> liveInstances = _helixDataAccessor.getChildNames(_keyBuilder.liveInstances());
        instanceList.retainAll(liveInstances);
        return instanceList;
    }

    /**
     * Computes the server nodes that are untagged and free to be used.
     * @return List of untagged online server instances.
     */
    public List<String> getOnlineUnTaggedServerInstanceList() {
        final List<String> instanceList =
                _helixAdmin.getInstancesInClusterWithTag(_helixClusterName, CommonConstants.Helix.UNTAGGED_SERVER_INSTANCE);
        final List<String> liveInstances = _helixDataAccessor.getChildNames(_keyBuilder.liveInstances());
        instanceList.retainAll(liveInstances);
        return instanceList;
    }
}
