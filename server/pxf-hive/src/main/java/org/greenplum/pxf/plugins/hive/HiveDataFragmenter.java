package org.greenplum.pxf.plugins.hive;

/*
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

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.hive.common.JavaUtils;
import org.apache.hadoop.hive.conf.HiveConf;
import org.apache.hadoop.hive.metastore.HiveMetaStoreClient;
import org.apache.hadoop.hive.metastore.MetaStoreUtils;
import org.apache.hadoop.hive.metastore.api.FieldSchema;
import org.apache.hadoop.hive.metastore.api.Partition;
import org.apache.hadoop.hive.metastore.api.StorageDescriptor;
import org.apache.hadoop.hive.metastore.api.Table;
import org.apache.hadoop.hive.serde.serdeConstants;
import org.apache.hadoop.mapred.FileInputFormat;
import org.apache.hadoop.mapred.FileSplit;
import org.apache.hadoop.mapred.InputFormat;
import org.apache.hadoop.mapred.InputSplit;
import org.apache.hadoop.mapred.JobConf;
import org.apache.hadoop.mapred.TextInputFormat;
import org.greenplum.pxf.api.BasicFilter;
import org.greenplum.pxf.api.FilterParser;
import org.greenplum.pxf.api.LogicalFilter;
import org.greenplum.pxf.api.model.BaseConfigurationFactory;
import org.greenplum.pxf.api.model.ConfigurationFactory;
import org.greenplum.pxf.api.model.Fragment;
import org.greenplum.pxf.api.model.FragmentStats;
import org.greenplum.pxf.api.model.Metadata;
import org.greenplum.pxf.api.model.RequestContext;
import org.greenplum.pxf.api.utilities.ColumnDescriptor;
import org.greenplum.pxf.plugins.hdfs.HdfsDataFragmenter;
import org.greenplum.pxf.plugins.hdfs.utilities.HdfsUtilities;
import org.greenplum.pxf.plugins.hive.utilities.HiveUtilities;
import org.greenplum.pxf.plugins.hive.utilities.ProfileFactory;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.TreeSet;

/**
 * Fragmenter class for HIVE tables. <br>
 * Given a Hive table and its partitions divide the data into fragments (here a
 * data fragment is actually a HDFS file block) and return a list of them. Each
 * data fragment will contain the following information:
 * <ol>
 * <li>sourceName: full HDFS path to the data file that this data fragment is
 * part of</li>
 * <li>hosts: a list of the datanode machines that hold a replica of this block</li>
 * <li>userData:
 * file_input_format_name_DELIM_serde_name_DELIM_serialization_properties</li>
 * </ol>
 */
public class HiveDataFragmenter extends HdfsDataFragmenter {
    private static final Log LOG = LogFactory.getLog(HiveDataFragmenter.class);
    private static final short ALL_PARTS = -1;

    public static final String HIVE_1_PART_DELIM = "!H1PD!";
    public static final String HIVE_PARTITIONS_DELIM = "!HPAD!";
    public static final String HIVE_NO_PART_TBL = "!HNPT!";

    private static final String HIVE_API_EQ = " = ";
    private static final String HIVE_API_LT = " < ";
    private static final String HIVE_API_GT = " > ";
    private static final String HIVE_API_LTE = " <= ";
    private static final String HIVE_API_GTE = " >= ";
    private static final String HIVE_API_NE = " != ";
    private static final String HIVE_API_DQUOTE = "\"";

    private HiveMetaStoreClient client;

    protected boolean filterInFragmenter = false;

    // Data structure to hold hive partition names if exist, to be used by
    // partition filtering
    private Set<String> setPartitions = new TreeSet<String>(
            String.CASE_INSENSITIVE_ORDER);
    private Map<String, String> partitionkeyTypes = new HashMap<>();
    private boolean canPushDownIntegral;

    public HiveDataFragmenter() {
        this(BaseConfigurationFactory.getInstance());
    }

    HiveDataFragmenter(ConfigurationFactory configurationFactory) {
        this.configurationFactory = configurationFactory;
    }

    @Override
    public void initialize(RequestContext requestContext) {
        super.initialize(requestContext);
        client = HiveUtilities.initHiveClient(configuration);
        // canPushDownIntegral represents hive.metastore.integral.jdo.pushdown property in hive-site.xml
        canPushDownIntegral = HiveConf.getBoolVar(
                new HiveConf(configuration, HiveConf.class),
                HiveConf.ConfVars.METASTORE_INTEGER_JDO_PUSHDOWN);
    }

    @Override
    public List<Fragment> getFragments() throws Exception {
        Metadata.Item tblDesc = HiveUtilities.extractTableFromName(context.getDataSource());

        fetchTableMetaData(tblDesc);

        return fragments;
    }

    /**
     * Creates the partition InputFormat.
     *
     * @param inputFormatName input format class name
     * @param jobConf         configuration data for the Hadoop framework
     * @return a {@link org.apache.hadoop.mapred.InputFormat} derived object
     * @throws Exception if failed to create input format
     */
    public static InputFormat<?, ?> makeInputFormat(String inputFormatName,
                                                    JobConf jobConf)
            throws Exception {
        Class<?> c = Class.forName(inputFormatName, true,
                JavaUtils.getClassLoader());
        InputFormat<?, ?> inputFormat = (InputFormat<?, ?>) c.newInstance();

        if ("org.apache.hadoop.mapred.TextInputFormat".equals(inputFormatName)) {
            // TextInputFormat needs a special configuration
            ((TextInputFormat) inputFormat).configure(jobConf);
        }

        return inputFormat;
    }

    /*
     * Goes over the table partitions metadata and extracts the splits and the
     * InputFormat and Serde per split.
     */
    private void fetchTableMetaData(Metadata.Item tblDesc) throws Exception {

        Table tbl = HiveUtilities.getHiveTable(client, tblDesc);

        Metadata metadata = new Metadata(tblDesc);
        HiveUtilities.getSchema(tbl, metadata);
        boolean hasComplexTypes = HiveUtilities.hasComplexTypes(metadata);

        verifySchema(tbl);

        List<Partition> partitions = null;
        String filterStringForHive = "";

        // If query has filter and hive table has partitions, prepare the filter
        // string for hive metastore and retrieve only the matched partitions
        if (context.hasFilter() && tbl.getPartitionKeysSize() > 0) {

            // Save all hive partition names in a set for later filter match
            for (FieldSchema fs : tbl.getPartitionKeys()) {
                setPartitions.add(fs.getName());
                partitionkeyTypes.put(fs.getName(), fs.getType());
            }

            LOG.debug("setPartitions :" + setPartitions);

            // Generate filter string for retrieve match pxf filter/hive
            // partition name
            filterStringForHive = buildFilterStringForHive();
        }

        if (!filterStringForHive.isEmpty()) {

            LOG.debug("Filter String for Hive partition retrieval : "
                    + filterStringForHive);

            filterInFragmenter = true;

            // API call to Hive Metastore, will return a List of all the
            // partitions for this table, that matches the partition filters
            // Defined in filterStringForHive.
            partitions = client.listPartitionsByFilter(tblDesc.getPath(),
                    tblDesc.getName(), filterStringForHive, ALL_PARTS);

            // No matched partitions for the filter, no fragments to return.
            if (partitions == null || partitions.isEmpty()) {

                if (LOG.isDebugEnabled()) {
                    LOG.debug("Table -  " + tblDesc.getPath() + "."
                            + tblDesc.getName()
                            + " Has no matched partitions for the filter : "
                            + filterStringForHive);
                }
                return;
            }

            if (LOG.isDebugEnabled()) {
                LOG.debug("Table -  " + tblDesc.getPath() + "."
                        + tblDesc.getName()
                        + " Matched partitions list size: " + partitions.size());
            }

        } else {
            // API call to Hive Metastore, will return a List of all the
            // partitions for this table (no filtering)
            partitions = client.listPartitions(tblDesc.getPath(),
                    tblDesc.getName(), ALL_PARTS);
        }

        StorageDescriptor descTable = tbl.getSd();
        Properties props;

        if (partitions.isEmpty()) {
            props = getSchema(tbl);
            fetchMetaDataForSimpleTable(descTable, props, hasComplexTypes);
        } else {
            List<FieldSchema> partitionKeys = tbl.getPartitionKeys();

            for (Partition partition : partitions) {
                StorageDescriptor descPartition = partition.getSd();
                props = MetaStoreUtils.getSchema(descPartition, descTable,
                        null,
                        tblDesc.getPath(), tblDesc.getName(),
                        partitionKeys);
                fetchMetaDataForPartitionedTable(descPartition, props,
                        partition, partitionKeys, tblDesc.getName(), hasComplexTypes);
            }
        }
    }

    void verifySchema(Table tbl) throws Exception {
        /* nothing to verify here */
    }

    private static Properties getSchema(Table table) {
        return MetaStoreUtils.getSchema(table.getSd(), table.getSd(),
                table.getParameters(), table.getDbName(), table.getTableName(),
                table.getPartitionKeys());
    }

    private void fetchMetaDataForSimpleTable(StorageDescriptor stdsc,
                                             Properties props, boolean hasComplexTypes) throws Exception {
        fetchMetaDataForSimpleTable(stdsc, props, null, hasComplexTypes);
    }

    private void fetchMetaDataForSimpleTable(StorageDescriptor stdsc,
                                             Properties props, String tableName, boolean hasComplexTypes)
            throws Exception {
        fetchMetaData(new HiveTablePartition(stdsc, props, null, null,
                tableName), hasComplexTypes);
    }

    private void fetchMetaDataForPartitionedTable(StorageDescriptor stdsc,
                                                  Properties props,
                                                  Partition partition,
                                                  List<FieldSchema> partitionKeys,
                                                  String tableName,
                                                  boolean hasComplexTypes)
            throws Exception {
        fetchMetaData(new HiveTablePartition(stdsc, props, partition,
                partitionKeys, tableName), hasComplexTypes);
    }

    /* Fills a table partition */
    private void fetchMetaData(HiveTablePartition tablePartition, boolean hasComplexTypes)
            throws Exception {
        InputFormat<?, ?> fformat = makeInputFormat(
                tablePartition.storageDesc.getInputFormat(), jobConf);
        String profile = null;
        String userProfile = context.getProfile();
        if (userProfile != null) {
            // evaluate optimal profile based on file format if profile was explicitly specified in url
            // if user passed accessor+fragmenter+resolver - use them
            profile = ProfileFactory.get(fformat, hasComplexTypes, userProfile);
        }
        String fragmenterForProfile = null;
        if (profile != null) {
            fragmenterForProfile = context.getPluginConf().getPlugins(profile).get("FRAGMENTER");
        } else {
            fragmenterForProfile = context.getFragmenter();
        }

        FileInputFormat.setInputPaths(jobConf, new Path(
                tablePartition.storageDesc.getLocation()));

        InputSplit[] splits = null;
        try {
            splits = fformat.getSplits(jobConf, 1);
        } catch (org.apache.hadoop.mapred.InvalidInputException e) {
            LOG.debug("getSplits failed on " + e.getMessage());
            return;
        }

        for (InputSplit split : splits) {
            FileSplit fsp = (FileSplit) split;
            String[] hosts = fsp.getLocations();
            String filepath = fsp.getPath().toString();

            byte[] locationInfo = HdfsUtilities.prepareFragmentMetadata(fsp);
            Fragment fragment = new Fragment(filepath, hosts, locationInfo,
                    HiveUtilities.makeUserData(fragmenterForProfile, tablePartition, filterInFragmenter), profile);
            fragments.add(fragment);
        }
    }

    /*
     * Build filter string for HiveMetaStoreClient.listPartitionsByFilter API
     * method.
     *
     * The filter string parameter for
     * HiveMetaStoreClient.listPartitionsByFilter will be created from the
     * incoming getFragments filter string parameter. It will be in a format of:
     * [PARTITON1 NAME] = \"[PARTITON1 VALUE]\" AND [PARTITON2 NAME] =
     * \"[PARTITON2 VALUE]\" ... Filtering can be done only on string partition
     * keys and AND operators.
     *
     * For Example for query: SELECT * FROM TABLE1 WHERE part1 = 'AAAA' AND
     * part2 = '1111' For HIVE HiveMetaStoreClient.listPartitionsByFilter, the
     * incoming GPDB filter string will be mapped into :
     * "part1 = \"AAAA\" and part2 = \"1111\""
     */
    private String buildFilterStringForHive() throws Exception {

        StringBuilder filtersString = new StringBuilder();
        String filterInput = context.getFilterString();

        if (LOG.isDebugEnabled()) {

            for (ColumnDescriptor cd : context.getTupleDescription()) {
                LOG.debug("ColumnDescriptor : " + cd);
            }

            LOG.debug("Filter string input : " + context.getFilterString());
        }

        HiveFilterBuilder eval = new HiveFilterBuilder(context);
        Object filter = eval.getFilterObject(filterInput);

        if (filter instanceof LogicalFilter) {
            buildCompoundFilter((LogicalFilter) filter, filtersString);
        } else {
            buildSingleFilter(filter, filtersString, "");
        }

        return filtersString.toString();
    }

    private void buildCompoundFilter(LogicalFilter filter, StringBuilder filterString) throws Exception {
        String prefix;
        switch (filter.getOperator()) {
            case HDOP_AND:
                prefix = " and ";
                break;
            case HDOP_OR:
                prefix = " or ";
                break;
            case HDOP_NOT:
                prefix = " not ";
                break;
            default:
                prefix = "";
        }

        for (Object f : filter.getFilterList()) {
            if (f instanceof LogicalFilter) {
                buildCompoundFilter((LogicalFilter) f, filterString);
            } else {
                buildSingleFilter(f, filterString, prefix);
            }
        }
    }

    /*
     * Build filter string for a single filter and append to the filters string.
     * Filter string shell be added if filter name match hive partition name
     * Single filter will be in a format of: [PARTITON NAME] = \"[PARTITON
     * VALUE]\"
     */
    private boolean buildSingleFilter(Object filter,
                                      StringBuilder filtersString, String prefix)
            throws Exception {

        // Let's look first at the filter
        BasicFilter bFilter = (BasicFilter) filter;

        // Extract column name and value
        int filterColumnIndex = bFilter.getColumn().index();
        // Avoids NullPointerException in case of operations like HDOP_IS_NULL,
        // HDOP_IS_NOT_NULL where no constant value is passed as part of query
        String filterValue = bFilter.getConstant() != null ? bFilter.getConstant().constant().toString() : "";
        ColumnDescriptor filterColumn = context.getColumn(filterColumnIndex);
        String filterColumnName = filterColumn.columnName();
        FilterParser.Operation operation = ((BasicFilter) filter).getOperation();
        String colType = partitionkeyTypes.get(filterColumnName);
        boolean isIntegralSupported =
                canPushDownIntegral &&
                        (operation == FilterParser.Operation.HDOP_EQ || operation == FilterParser.Operation.HDOP_NE);
        // In case this filter is not a partition, we ignore this filter (no add
        // to filter list)
        if (!setPartitions.contains(filterColumnName)) {
            LOG.debug("Filter name is not a partition , ignore this filter for hive: "
                    + filter);
            return false;
        }

        /*
         * HAWQ-1527 - Filtering only supported for partition columns of type string or
         * intgeral datatype. Integral datatypes include - TINYINT, SMALLINT, INT, BIGINT.
         * Note that with integral data types only equals("=") and not equals("!=") operators
         * are supported. There are no operator restrictions with String.
         */
        if (!colType.equalsIgnoreCase(serdeConstants.STRING_TYPE_NAME)
                && (!isIntegralSupported || !serdeConstants.IntegralTypes.contains(colType))) {
            LOG.debug("Column type is neither string nor an integral data type, ignore this filter for hive: "
                    + filter);
            return false;
        }

        if (filtersString.length() != 0)
            filtersString.append(prefix);
        filtersString.append(filterColumnName);

        switch (operation) {
            case HDOP_EQ:
                filtersString.append(HIVE_API_EQ);
                break;
            case HDOP_LT:
                filtersString.append(HIVE_API_LT);
                break;
            case HDOP_GT:
                filtersString.append(HIVE_API_GT);
                break;
            case HDOP_LE:
                filtersString.append(HIVE_API_LTE);
                break;
            case HDOP_GE:
                filtersString.append(HIVE_API_GTE);
                break;
            case HDOP_NE:
                filtersString.append(HIVE_API_NE);
                break;
            default:
                // Set filter string to blank in case of unimplemented operations
                filtersString.setLength(0);
                return false;
        }

        filtersString.append(HIVE_API_DQUOTE);
        filtersString.append(filterValue);
        filtersString.append(HIVE_API_DQUOTE);

        return true;
    }

    /**
     * Returns statistics for Hive table. Currently it's not implemented.
     */
    @Override
    public FragmentStats getFragmentStats() throws Exception {
        Metadata.Item tblDesc = HiveUtilities.extractTableFromName(context.getDataSource());
        Table tbl = HiveUtilities.getHiveTable(client, tblDesc);
        Metadata metadata = new Metadata(tblDesc);
        HiveUtilities.getSchema(tbl, metadata);

        long split_count = Long.parseLong(tbl.getParameters().get("numFiles"));
        long totalSize = Long.parseLong(tbl.getParameters().get("totalSize"));
        long firstFragmentSize = totalSize / split_count;
        return new FragmentStats(split_count, firstFragmentSize, totalSize);
    }
}
