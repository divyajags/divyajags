
package s3.index.probe.impl;\
\
import com.amazon.util.time.TimeSource;\
import com.google.common.collect.ImmutableList;\
import com.google.common.collect.ImmutableMap;\
import com.google.common.collect.Lists;\
import junitparams.JUnitParamsRunner;\
import junitparams.Parameters;\
import org.assertj.core.util.Sets;\
import org.junit.After;\
import org.junit.Test;\
import org.junit.runner.RunWith;\
import org.mockito.ArgumentCaptor;\
import org.mockito.Mockito;\
import s3.ElrondClient.ElrondClient;\
import s3.anomalystore.common.Anomaly;\
import s3.applicationregistry.ApplicationStatus;\
import s3.applicationregistry.Registration;\
import s3.applicationregistry.RegistrationImpl;\
import s3.applicationregistry.UpdateCookie;\
import s3.applicationregistry.mock.MockRegistryReader;\
import s3.commons.net.EndpointAddress;\
import s3.configulator.ConfigDelegator;\
import s3.dfddclient.Location;\
import s3.host.Semantic;\
import s3.host.SemanticHost;\
import s3.index.hardware.client.CheckHealthResponse;\
import s3.index.hardware.client.HardwareHealthCheckClient;\
import s3.index.hardware.client.HostState;\
import s3.index.preconditions.client.PingAddressReachability;\
import s3.index.preconditions.host.HostHealthPreconditionChecker;\
import s3.index.probe.ProbeTestBase;\
import s3.index.probe.config.AutoSmashProbeConfig;\
import s3.kmi.KeymapInstanceFinder;\
import s3.moria.common.constants.SharedConstants;\
import s3.moria.common.store.test.InMemoryKeyValueStore;\
import s3.querylogbuilder.EventData;\
import s3.querylogbuilder.EventDataFactory;\
import s3.querylogbuilder.NoopEventData;\
import s3.trie.ByteString;\
\
import java.util.ArrayList;\
import java.util.Collections;\
import java.util.HashMap;\
import java.util.HashSet;\
import java.util.List;\
import java.util.Map;\
import java.util.Set;\
import java.util.concurrent.CompletableFuture;\
\
import static org.assertj.core.api.Assertions.assertThat;\
import static org.junit.Assert.assertEquals;\
import static org.junit.Assert.assertTrue;\
import static org.mockito.Matchers.any;\
import static org.mockito.Mockito.doAnswer;\
import static org.mockito.Mockito.mock;\
import static org.mockito.Mockito.when;\
import static s3.index.probe.impl.AutoSmashProbe.HOSTS_FILTERED_BY_SEMANTIC;\
import static s3.index.probe.impl.AutoSmashProbe.LOCKED_HOSTS_QUERY_FAILED;\
import static s3.moria.common.constants.SharedConstants.INDEX_NODE_APP_NAME;\
import static s3.testutils.assertions.EventDataAssert.assertThatEventData;\
\
@RunWith(JUnitParamsRunner.class)\
public class AutoSmashProbeTest extends ProbeTestBase \{\
\
    private final String FAKE_HOST = "127.0.0.1";\
    private final int FAKE_PORT = 1111;\
    private final int FAKE_ANOTHER_PORT = 1112;\
    private final String FAKE_CELL = "cell-x";\
    private final String FAKE_APP_NAME = "test-app";\
    private final String FAKE_ANOTHER_APP_NAME = "new-app";\
\
    private final ArgumentCaptor<EndpointAddress> endpointArgumentCaptor = ArgumentCaptor.forClass(EndpointAddress.class);\
    private final KeymapInstanceFinder kmiFinder = mock(KeymapInstanceFinder.class);\
    private final EndpointAddress endpointAddress = new EndpointAddress(FAKE_HOST, FAKE_PORT);\
\
    private EventData eventData = EventDataFactory.create();\
\
    private AutoSmashProbe probe;\
    private AutoSmashProbeConfig config;\
    private MockRegistryReader registryReader;\
    private HostHealthPreconditionChecker hostHealthPreconditionChecker;\
    private HardwareHealthCheckClient mockHardwareHealthCheckClient;\
    private ElrondClient elrondClient;\
    private TimeSource.LogicalTimeProvider testTime;\
\
    @Override\
    public void setUp() throws Exception \{\
        super.setUp();\
\
        setUpConfig();\
\
        elrondClient = mock(ElrondClient.class);\
        registryReader = new MockRegistryReader();\
        mockHardwareHealthCheckClient = mock(HardwareHealthCheckClient.class);\
        PingAddressReachability pingAddressReachability = mock(PingAddressReachability.class);\
        hostHealthPreconditionChecker = new HostHealthPreconditionChecker(mockHardwareHealthCheckClient, pingAddressReachability);\
\
        testTime = new TimeSource.LogicalTimeProvider();\
        TimeSource.install(testTime);\
\
        setUpHealthyHardwareHealthCheckResponse(new HashMap<>());\
\
        when(pingAddressReachability.isReachable(FAKE_HOST, 5000, new NoopEventData())).thenReturn(true);\
\
        when(elrondClient.getSemanticHosts(any(), any()))\
                .thenReturn(CompletableFuture.completedFuture(Collections.emptySet()));\
    \}\
\
    private void setUpConfig() \{\
        config = (AutoSmashProbeConfig) ConfigDelegator.constructConfig(AutoSmashProbeConfig.class);\
\
        Set<String> whitelistedServices = new HashSet<>();\
        whitelistedServices.add(FAKE_APP_NAME);\
        config.setAvailabilityThresholdPerService(ImmutableMap.of(FAKE_APP_NAME, "0"));\
        config.setWhitelistedServices(whitelistedServices);\
        config.setFailureTimeMillis(1);\
    \}\
\
    private void setUpProbe() \{\
        probe = new AutoSmashProbe(config,\
                registryReader,\
                partitionInfoReader,\
                hostHealthPreconditionChecker,\
                new InMemoryKeyValueStore(),\
                FAKE_CELL,\
                kmiFinder,\
                elrondClient);\
    \}\
\
    @Test\
    public void testMetricsWhenWhitelistIsEmpty() \{\
        setUpProbe();\
\
        registerApp(endpointAddress, FAKE_CELL, FAKE_APP_NAME);\
\
        config.setWhitelistedServices(new HashSet<>());\
\
        Set<Anomaly> anomalySet = probe.findAnomalies(eventData);\
        assertEquals(0, anomalySet.size());\
        assertThatEventData(eventData).hasCounter(AutoSmashProbe.TOTAL_CANDIDATES_METRIC, 0);\
\
        /* Second Run */\
        testTime.increment(10);\
        anomalySet = probe.findAnomalies(eventData);\
        assertEquals(0, anomalySet.size());\
\
        assertThatEventData(eventData).hasCounter(AutoSmashProbe.TOTAL_ELIGIBLE_INSTANCES_METRIC, 0);\
        assertThatEventData(eventData).hasCounter(AutoSmashProbe.TOTAL_ELIGIBLE_INC_INSTANCES_METRIC, 0);\
        assertThatEventData(eventData).hasCounter(AutoSmashProbe.TOTAL_CANDIDATES_METRIC, 0);\
    \}\
\
    @Test\
    @Parameters(\{"OK", "INCOMMUNICADO"\})\
    public void testGenerateAnomaly(ApplicationStatus status) \{\
        setUpProbe();\
\
        registerApp(endpointAddress, FAKE_CELL, FAKE_APP_NAME, status);\
        Set<Anomaly> anomalySet = probe.findAnomalies(eventData);\
        assertEquals(0, anomalySet.size());\
\
        /* Second Run */\
        testTime.increment(10);\
        anomalySet = probe.findAnomalies(eventData);\
        assertEquals(1, anomalySet.size());\
\
        assertThatEventData(eventData)\
                .hasCounter(String.format(AutoSmashProbe.SERVICE_ANOMALY_HOSTS_CREATED, FAKE_APP_NAME), 1)\
                .hasCounter(String.format(AutoSmashProbe.SERVICE_ANOMALY_HOSTS_TOTAL, FAKE_APP_NAME), 1);\
    \}\
\
    @Test\
    public void testGenerateAnomalyForIndexNode() \{\
        int maxNumberOfIndexNodeAnomalies = 3;\
        int unhealthyIndexNodes = maxNumberOfIndexNodeAnomalies + 1;\
        int totalIndexNodes = 2 * unhealthyIndexNodes;\
        config.setMaximumNumberOfIndexNodeAnomalies(maxNumberOfIndexNodeAnomalies);\
        config.setAvailabilityThresholdPerService(ImmutableMap.of(INDEX_NODE, "0"));\
        config.setWhitelistedServices(Collections.singleton(INDEX_NODE));\
\
        Map<EndpointAddress, CheckHealthResponse> hardwareHealthCheckStates = new HashMap<>();\
\
        for (int i = 0; i < totalIndexNodes; i++) \{\
            EndpointAddress host = new EndpointAddress(String.format("127.0.0.%s", i + 2), FAKE_PORT);\
\
            if (i < unhealthyIndexNodes) \{\
                hardwareHealthCheckStates.put(host, new CheckHealthResponse(host, HostState.NOT_HEALTHY));\
                registerApp(host, FAKE_CELL, INDEX_NODE, ApplicationStatus.INCOMMUNICADO);\
            \} else \{\
                hardwareHealthCheckStates.put(host, new CheckHealthResponse(host, HostState.HEALTHY));\
                registerApp(host, FAKE_CELL, INDEX_NODE, ApplicationStatus.OK);\
            \}\
        \}\
\
        setUpHealthyHardwareHealthCheckResponse(hardwareHealthCheckStates);\
        setUpProbe();\
\
        /* First Run */\
        Set<Anomaly> anomalySet = probe.findAnomalies(eventData);\
        assertThat(anomalySet).isEmpty();\
\
        /* Second Run */\
        testTime.increment(10);\
        anomalySet = probe.findAnomalies(eventData);\
        assertThat(anomalySet.size())\
                .isEqualTo(maxNumberOfIndexNodeAnomalies)\
                .isGreaterThan((int) config.getMaximumNumberOfAnomaliesPerService());\
    \}\
\
    @Test\
    public void testDuplicatedRecordsDFDD() \{\
        Registration registration1 = new RegistrationImpl(FAKE_CELL,\
                FAKE_APP_NAME,\
                "instance-1",\
                "instance-1",\
                new Location(CLUSTER, AREA, endpointAddress.getHost(), "test-rack"),\
                endpointAddress,\
                ApplicationStatus.INCOMMUNICADO,\
                UpdateCookie.EMPTY,\
                null);\
\
        Registration registration2 = new RegistrationImpl(FAKE_CELL,\
                FAKE_APP_NAME,\
                "instance-2",\
                "instance-2",\
                new Location(CLUSTER, AREA, endpointAddress.getHost(), "test-rack"),\
                endpointAddress,\
                ApplicationStatus.INCOMMUNICADO,\
                UpdateCookie.EMPTY,\
                null);\
\
        registryReader.register(registration1, registration2);\
\
        Set<String> whitelistedServices = new HashSet<>();\
        whitelistedServices.add(FAKE_APP_NAME);\
\
        config.setWhitelistedServices(whitelistedServices);\
\
        setUpProbe();\
\
        ImmutableMap<EndpointAddress, CheckHealthResponse> hardwareHealthCheckStates = ImmutableMap.of(\
                endpointAddress, new CheckHealthResponse(endpointAddress, HostState.NOT_HEALTHY));\
\
        setUpHealthyHardwareHealthCheckResponse(hardwareHealthCheckStates);\
\
        Set<Anomaly> anomalySet = probe.findAnomalies(eventData);\
        assertEquals(0, anomalySet.size());\
        assertThatEventData(eventData).hasCounter(AutoSmashProbe.DUPLICATED_RECORDS_DFDD_COUNT_METRIC, 1);\
\
        eventData.reset();\
\
        /* Second Run */\
        testTime.increment(10);\
        anomalySet = probe.findAnomalies(eventData);\
        assertEquals(1, anomalySet.size());\
        assertThatEventData(eventData).hasCounter(AutoSmashProbe.DUPLICATED_RECORDS_DFDD_COUNT_METRIC, 1);\
        assertThatEventData(eventData).hasCounter(String.format(AutoSmashProbe.SERVICE_ANOMALY_HOSTS_CREATED, FAKE_APP_NAME), 1);\
    \}\
\
    @Test\
    public void testGenerateAnomalyOnlyForCellIdSpecifiedDuringProbeCreation() \{\
        setUpProbe();\
\
        registerApp(endpointAddress, FAKE_CELL, FAKE_APP_NAME);\
        registerApp(new EndpointAddress("127.0.0.2", FAKE_PORT), "cell-1", FAKE_APP_NAME);\
\
        Set<Anomaly> anomalySet = probe.findAnomalies(eventData);\
        assertEquals(0, anomalySet.size());\
\
        /* Second Run */\
        testTime.increment(10);\
        registerApp(new EndpointAddress("127.0.0.3", FAKE_PORT), FAKE_CELL, FAKE_APP_NAME);\
        eventData = EventDataFactory.create();\
        anomalySet = probe.findAnomalies(eventData);\
\
        assertEquals(1, anomalySet.size());\
        ByteString name = anomalySet.iterator().next().getEntityName();\
        assertEquals(ByteString.wrap(String.format(SharedConstants.Autosmash.UNHEALTHY_HOST_ANOMALY_ENTITY_NAME_FORMAT, FAKE_APP_NAME, endpointAddress.stumpyEndpoint()))\
                , name);\
        assertThatEventData(eventData).hasCounter(AutoSmashProbe.TOTAL_ELIGIBLE_INSTANCES_METRIC, 2);\
        assertThatEventData(eventData).hasCounter(AutoSmashProbe.TOTAL_ELIGIBLE_INC_INSTANCES_METRIC, 0);\
        assertThatEventData(eventData).hasCounter(AutoSmashProbe.TOTAL_CANDIDATES_METRIC, 2);\
\
        assertThatEventData(eventData)\
                .hasCounter(String.format(AutoSmashProbe.SERVICE_ANOMALY_HOSTS_CREATED, FAKE_APP_NAME), 1);\
    \}\
\
    @Test\
    @Parameters(\{"0", "1", "2"\})\
    public void testGenerateOnlyConfiguredNumberAnomalyPerService(String maxPerService) \{\
        setUpProbe();\
\
        config.setMaximumNumberOfAnomaliesPerService(Long.parseLong(maxPerService));\
\
        probe = new AutoSmashProbe(config,\
                registryReader,\
                partitionInfoReader,\
                hostHealthPreconditionChecker,\
                new InMemoryKeyValueStore(),\
                FAKE_CELL,\
                kmiFinder,\
                elrondClient);\
\
        registerApp(endpointAddress, FAKE_CELL, FAKE_APP_NAME);\
        EndpointAddress endpointAddress2 = new EndpointAddress("127.0.0.2", FAKE_PORT);\
        registerApp(endpointAddress2, FAKE_CELL, FAKE_APP_NAME);\
        EndpointAddress endpointAddress3 = new EndpointAddress("127.0.0.3", FAKE_PORT);\
        registerApp(endpointAddress3, FAKE_CELL, FAKE_APP_NAME);\
\
        Set<Anomaly> anomalySet = probe.findAnomalies(eventData);\
\
        assertEquals(0, anomalySet.size());\
\
        // Adds another service on the same whitelisted cell and it should only return one anomaly\
        testTime.increment(10);\
        registerApp(new EndpointAddress("127.0.0.2", FAKE_PORT), FAKE_CELL, FAKE_APP_NAME);\
        anomalySet = probe.findAnomalies(eventData);\
\
        assertEquals(Integer.valueOf(maxPerService), (Integer)anomalySet.size());\
        if(anomalySet.size() > 0) \{\
            assertThatEventData(eventData)\
                    .hasCounter(String.format(AutoSmashProbe.SERVICE_ANOMALY_HOSTS_TOTAL, FAKE_APP_NAME), 3)\
                    .hasCounter(String.format(AutoSmashProbe.SERVICE_ANOMALY_HOSTS_CREATED, FAKE_APP_NAME), Integer.parseInt(maxPerService));\
        \}\
    \}\
\
    @Test\
    @Parameters(\{"FAILED", "UNKNOWN"\})\
    public void testDontFindInvalidDfddStatus(ApplicationStatus status) \{\
        setUpProbe();\
\
        registerApp(endpointAddress, FAKE_CELL, FAKE_APP_NAME, status);\
\
        Set<Anomaly> anomalySet = probe.findAnomalies(eventData);\
        assertEquals(0, anomalySet.size());\
\
        /* Second Run */\
        testTime.increment(10);\
        anomalySet = probe.findAnomalies(eventData);\
        assertEquals(0, anomalySet.size());\
    \}\
\
    @Test\
    public void testMultipleServices() \{\
        config.setAvailabilityThresholdPerService(ImmutableMap.of(FAKE_APP_NAME, "0", FAKE_ANOTHER_APP_NAME, "0"));\
        setUpProbe();\
\
        registerApp(endpointAddress, FAKE_CELL, FAKE_APP_NAME);\
        EndpointAddress newEndpoint = new EndpointAddress("127.0.0.2", FAKE_ANOTHER_PORT);\
        registerApp(newEndpoint, FAKE_CELL, FAKE_ANOTHER_APP_NAME);\
\
        Set<String> whitelistedServices = new HashSet<>();\
        whitelistedServices.add(FAKE_APP_NAME);\
        whitelistedServices.add(FAKE_ANOTHER_APP_NAME);\
        config.setWhitelistedServices(whitelistedServices);\
\
        Set<Anomaly> anomalySet = probe.findAnomalies(eventData);\
\
        assertEquals(0, anomalySet.size());\
        assertThatEventData(eventData).hasCounter(AutoSmashProbe.TOTAL_ELIGIBLE_INSTANCES_METRIC, 2);\
        assertThatEventData(eventData).hasCounter(AutoSmashProbe.TOTAL_CANDIDATES_METRIC, 2);\
\
        /* Second Run */\
        testTime.increment(10);\
        eventData = EventDataFactory.create();\
        anomalySet = probe.findAnomalies(eventData);\
        assertEquals(2, anomalySet.size());\
\
        List<ByteString> expectedEndpoints = new ArrayList<>();\
        expectedEndpoints.add(ByteString.wrap(String.format(SharedConstants.Autosmash.UNHEALTHY_HOST_ANOMALY_ENTITY_NAME_FORMAT, FAKE_APP_NAME, endpointAddress.stumpyEndpoint())));\
        expectedEndpoints.add(ByteString.wrap(String.format(SharedConstants.Autosmash.UNHEALTHY_HOST_ANOMALY_ENTITY_NAME_FORMAT, FAKE_ANOTHER_APP_NAME, newEndpoint.stumpyEndpoint())));\
\
        anomalySet.forEach(anomaly -> assertTrue(expectedEndpoints.contains(anomaly.getEntityName())));\
\
        assertThatEventData(eventData).hasCounter(String.format(AutoSmashProbe.SERVICE_ANOMALY_HOSTS_CREATED, FAKE_APP_NAME), 1);\
        assertThatEventData(eventData).hasCounter(String.format(AutoSmashProbe.SERVICE_ANOMALY_HOSTS_CREATED, FAKE_ANOTHER_APP_NAME), 1);\
        assertThatEventData(eventData).hasCounter(String.format(AutoSmashProbe.TOTAL_ELIGIBLE_INSTANCES_SERVICE_METRIC, FAKE_APP_NAME), 1);\
        assertThatEventData(eventData).hasCounter(String.format(AutoSmashProbe.TOTAL_ELIGIBLE_INSTANCES_SERVICE_METRIC, FAKE_ANOTHER_APP_NAME), 1);\
        assertThatEventData(eventData).hasCounter(String.format(AutoSmashProbe.TOTAL_CANDIDATES_SERVICE_METRIC, FAKE_APP_NAME), 1);\
        assertThatEventData(eventData).hasCounter(String.format(AutoSmashProbe.TOTAL_CANDIDATES_SERVICE_METRIC, FAKE_ANOTHER_APP_NAME), 1);\
        assertThatEventData(eventData).hasCounter(AutoSmashProbe.TOTAL_CANDIDATES_METRIC, 2);\
        assertThatEventData(eventData).hasCounter(AutoSmashProbe.TOTAL_ELIGIBLE_INSTANCES_METRIC, 2);\
    \}\
\
    /**\
     * Test that a healthy candidate is ineligible\
     */\
    @Test\
    public void testNotEligibleHost_Healthy() \{\
        config.setAvailabilityThresholdPerService(ImmutableMap.of(FAKE_APP_NAME, "0", FAKE_ANOTHER_APP_NAME, "0"));\
        setUpProbe();\
\
        // Testing when the preconditions check says the hosts are not eligible.\
        EndpointAddress unhealthyEndpointAddress = new EndpointAddress("127.0.0.2", FAKE_ANOTHER_PORT);\
        registerApp(endpointAddress, FAKE_CELL, FAKE_APP_NAME);\
        registerApp(unhealthyEndpointAddress, FAKE_CELL, FAKE_ANOTHER_APP_NAME);\
\
        Set<String> whitelistedServices = new HashSet<>();\
        whitelistedServices.add(FAKE_APP_NAME);\
        whitelistedServices.add(FAKE_ANOTHER_APP_NAME);\
        config.setWhitelistedServices(whitelistedServices);\
\
        ImmutableMap<EndpointAddress, CheckHealthResponse> hardwareHealthCheckStates = ImmutableMap.of(\
                endpointAddress, new CheckHealthResponse(endpointAddress, HostState.HEALTHY),\
                unhealthyEndpointAddress, new CheckHealthResponse(endpointAddress, HostState.NOT_HEALTHY));\
        Mockito.reset(mockHardwareHealthCheckClient);\
        setUpHealthyHardwareHealthCheckResponse(hardwareHealthCheckStates);\
\
        Set<Anomaly> anomalySet = probe.findAnomalies(eventData);\
        assertEquals(0, anomalySet.size());\
        assertThatEventData(eventData).hasCounter(AutoSmashProbe.TOTAL_CANDIDATES_METRIC, 2);\
        assertThatEventData(eventData).hasCounter(AutoSmashProbe.TOTAL_ELIGIBLE_INSTANCES_METRIC, 1);\
        assertThatEventData(eventData).hasCounter(String.format(AutoSmashProbe.TOTAL_ELIGIBLE_INSTANCES_SERVICE_METRIC, FAKE_APP_NAME), 0);\
        assertThatEventData(eventData).hasCounter(String.format(AutoSmashProbe.TOTAL_ELIGIBLE_INSTANCES_SERVICE_METRIC, FAKE_ANOTHER_APP_NAME), 1);\
        assertThatEventData(eventData).hasCounter(String.format(AutoSmashProbe.TOTAL_CANDIDATES_SERVICE_METRIC, FAKE_APP_NAME), 1);\
        assertThatEventData(eventData).hasCounter(String.format(AutoSmashProbe.TOTAL_CANDIDATES_SERVICE_METRIC, FAKE_ANOTHER_APP_NAME), 1);\
\
        /* Second Run */\
        testTime.increment(10);\
        eventData = EventDataFactory.create();\
        anomalySet = probe.findAnomalies(eventData);\
        assertEquals(1, anomalySet.size());\
        assertThatEventData(eventData).hasCounter(String.format(AutoSmashProbe.SERVICE_ANOMALY_HOSTS_CREATED, FAKE_ANOTHER_APP_NAME), 1);\
        assertThatEventData(eventData).hasCounter(AutoSmashProbe.TOTAL_CANDIDATES_METRIC, 2);\
        assertThatEventData(eventData).hasCounter(AutoSmashProbe.TOTAL_ELIGIBLE_INSTANCES_METRIC, 1);\
        assertThatEventData(eventData).hasCounter(String.format(AutoSmashProbe.TOTAL_ELIGIBLE_INSTANCES_SERVICE_METRIC, FAKE_APP_NAME), 0);\
        assertThatEventData(eventData).hasCounter(String.format(AutoSmashProbe.TOTAL_ELIGIBLE_INSTANCES_SERVICE_METRIC, FAKE_ANOTHER_APP_NAME), 1);\
        assertThatEventData(eventData).hasCounter(String.format(AutoSmashProbe.TOTAL_CANDIDATES_SERVICE_METRIC, FAKE_APP_NAME), 1);\
        assertThatEventData(eventData).hasCounter(String.format(AutoSmashProbe.TOTAL_CANDIDATES_SERVICE_METRIC, FAKE_ANOTHER_APP_NAME), 1);\
    \}\
\
    @Test\
    public void testNoRegisteredEndpoint() \{\
        setUpProbe();\
\
        Set<String> whitelistedServices = new HashSet<>();\
        whitelistedServices.add(FAKE_APP_NAME);\
        config.setWhitelistedServices(whitelistedServices);\
\
        Set<Anomaly> anomalySet = probe.findAnomalies(eventData);\
        assertEquals(0, anomalySet.size());\
        assertThatEventData(eventData).hasCounter(AutoSmashProbe.TOTAL_CANDIDATES_METRIC, 0);\
        assertThatEventData(eventData).hasCounter(AutoSmashProbe.TOTAL_ELIGIBLE_INSTANCES_METRIC, 0);\
        assertThatEventData(eventData).hasCounter(String.format(AutoSmashProbe.TOTAL_ELIGIBLE_INSTANCES_SERVICE_METRIC, FAKE_APP_NAME), 0);\
        assertThatEventData(eventData).hasCounter(String.format(AutoSmashProbe.TOTAL_CANDIDATES_SERVICE_METRIC, FAKE_APP_NAME), 0);\
\
        /* Second Run */\
        testTime.increment(10);\
        eventData = EventDataFactory.create();\
        anomalySet = probe.findAnomalies(eventData);\
        assertEquals(0, anomalySet.size());\
        assertThatEventData(eventData).hasCounter(AutoSmashProbe.TOTAL_CANDIDATES_METRIC, 0);\
        assertThatEventData(eventData).hasCounter(AutoSmashProbe.TOTAL_ELIGIBLE_INSTANCES_METRIC, 0);\
        assertThatEventData(eventData).hasCounter(String.format(AutoSmashProbe.TOTAL_ELIGIBLE_INSTANCES_SERVICE_METRIC, FAKE_APP_NAME), 0);\
        assertThatEventData(eventData).hasCounter(String.format(AutoSmashProbe.TOTAL_CANDIDATES_SERVICE_METRIC, FAKE_APP_NAME), 0);\
    \}\
\
    @Test\
    @Parameters(\{"100, 4, 2, 50, false",\
            "50.01, 4, 2, 50, false",\
            "50, 4, 2, 50, true",\
            "49.99, 4, 2, 50, true",\
            "0, 4, 2, 50, true"\})\
    public void testThreshold(Double threshold, int numberOfHosts, int numberOfUnhealthy, long expectedAvailability, boolean shouldFindCandidates) \{\
\
        config.setAvailabilityThresholdPerService(ImmutableMap.of(FAKE_APP_NAME, threshold.toString()));\
        setUpProbe();\
\
        Map<EndpointAddress, CheckHealthResponse> hardwareHealthCheckStates =\
                createHardwareHealthCheckStates(numberOfHosts, numberOfUnhealthy);\
\
        Set<String> whitelistedServices = new HashSet<>();\
        whitelistedServices.add(FAKE_APP_NAME);\
        config.setWhitelistedServices(whitelistedServices);\
\
        Mockito.reset(mockHardwareHealthCheckClient);\
        setUpHealthyHardwareHealthCheckResponse(hardwareHealthCheckStates);\
\
        Set<Anomaly> anomalySet = probe.findAnomalies(eventData);\
        assertEquals(0, anomalySet.size());\
        assertThatEventData(eventData).hasCounter(AutoSmashProbe.TOTAL_CANDIDATES_METRIC, numberOfHosts);\
        assertThatEventData(eventData).hasCounter(AutoSmashProbe.TOTAL_ELIGIBLE_INSTANCES_METRIC, numberOfUnhealthy);\
\
        /* Second Run */\
        testTime.increment(10);\
        eventData = EventDataFactory.create();\
        anomalySet = probe.findAnomalies(eventData);\
        assertEquals(shouldFindCandidates ? 1 : 0, anomalySet.size());\
        if (shouldFindCandidates) \{\
            assertThatEventData(eventData).hasCounter(String.format(AutoSmashProbe.SERVICE_ANOMALY_HOSTS_CREATED, FAKE_APP_NAME), 1);\
        \}\
        assertThatEventData(eventData).hasCounter(AutoSmashProbe.TOTAL_CANDIDATES_METRIC, numberOfHosts);\
        assertThatEventData(eventData).hasCounter(AutoSmashProbe.TOTAL_ELIGIBLE_INSTANCES_METRIC, numberOfUnhealthy);\
        assertThatEventData(eventData).hasCounter(AutoSmashProbe.TOTAL_ELIGIBLE_INC_INSTANCES_METRIC, numberOfUnhealthy);\
        assertThatEventData(eventData).hasCounter(String.format(AutoSmashProbe.SERVICE_AVAILABILITY_METRIC, FAKE_APP_NAME), expectedAvailability);\
        assertThatEventData(eventData).hasCounter(AutoSmashProbe.SERVICE_DEGRADED_CONDITION_METRIC, shouldFindCandidates ? 0 : 1);\
    \}\
\
    @Test\
    @Parameters(\{"100, 5, 2, 60, false",\
            "40.01, 5, 3, 40, false",\
            "20, 5, 4, 20, true",\
            "19.99, 5, 4, 20, true",\
            "0, 5, 2, 60, true"\})\
    public void testThresholdPerAz(Double threshold,\
                                   int numberOfHosts,\
                                   int numberOfUnhealthy,\
                                   long expectedAvailability,\
                                   boolean shouldFindCandidates) \{\
\
        config.setAvailabilityThresholdPerService(ImmutableMap.of(FAKE_APP_NAME, threshold.toString()));\
        config.setCheckAvailabilityThresholdPerAz(ImmutableList.of(FAKE_APP_NAME));\
        setUpProbe();\
\
        doAnswer(invocationOnMock -> ImmutableList.of("area0"))\
                .when(kmiFinder)\
                .getAllAreasForAreaNameWithAlias(any());\
\
        Map<EndpointAddress, CheckHealthResponse> hardwareHealthCheckStates =\
                createHardwareHealthCheckStates(numberOfHosts, numberOfUnhealthy);\
\
        Set<String> whitelistedServices = new HashSet<>();\
        whitelistedServices.add(FAKE_APP_NAME);\
        config.setWhitelistedServices(whitelistedServices);\
\
        Mockito.reset(mockHardwareHealthCheckClient);\
        setUpHealthyHardwareHealthCheckResponse(hardwareHealthCheckStates);\
\
        Set<Anomaly> anomalySet = probe.findAnomalies(eventData);\
        assertEquals(0, anomalySet.size());\
        assertThatEventData(eventData).hasCounter(AutoSmashProbe.TOTAL_CANDIDATES_METRIC, numberOfHosts);\
        assertThatEventData(eventData).hasCounter(AutoSmashProbe.TOTAL_ELIGIBLE_INSTANCES_METRIC, numberOfUnhealthy);\
\
        /* Second Run */\
        testTime.increment(10);\
        eventData = EventDataFactory.create();\
        anomalySet = probe.findAnomalies(eventData);\
        assertEquals(shouldFindCandidates ? 1 : 0, anomalySet.size());\
        if (shouldFindCandidates) \{\
            assertThatEventData(eventData).hasCounter(String.format(AutoSmashProbe.SERVICE_ANOMALY_HOSTS_CREATED, FAKE_APP_NAME), 1);\
        \}\
        assertThatEventData(eventData).hasCounter(AutoSmashProbe.TOTAL_CANDIDATES_METRIC, numberOfHosts);\
        assertThatEventData(eventData).hasCounter(AutoSmashProbe.TOTAL_ELIGIBLE_INSTANCES_METRIC, numberOfUnhealthy);\
        assertThatEventData(eventData).hasCounter(AutoSmashProbe.TOTAL_ELIGIBLE_INC_INSTANCES_METRIC, numberOfUnhealthy);\
        assertThatEventData(eventData).hasCounter(String.format(AutoSmashProbe.SERVICE_AVAILABILITY_DC_METRIC, FAKE_APP_NAME, AREA), expectedAvailability);\
        assertThatEventData(eventData).hasCounter(AutoSmashProbe.SERVICE_DEGRADED_CONDITION_METRIC, shouldFindCandidates ? 0 : 1);\
    \}\
\
    @Test\
    @Parameters(\{"100, 0, 100, false",\
            "4, 1, 75, true",\
            "4, 3, 25, false",\
            "100, 100, 0, false"\})\
    public void testNoThreshold(int numberOfHosts,\
                                int numberOfUnhealthy,\
                                long expectedAvailability,\
                                boolean shouldFindCandidates) \{\
\
        // Cleaning up the previous configuration\
        config.setAvailabilityThresholdPerService(Collections.emptyMap());\
        setUpProbe();\
\
        // When there is no threshold defined, consider the default (75%).\
        Map<EndpointAddress, CheckHealthResponse> hardwareHealthCheckStates =\
                createHardwareHealthCheckStates(numberOfHosts, numberOfUnhealthy);\
\
        Set<String> whitelistedServices = new HashSet<>();\
        whitelistedServices.add(FAKE_APP_NAME);\
        config.setWhitelistedServices(whitelistedServices);\
\
        Mockito.reset(mockHardwareHealthCheckClient);\
        setUpHealthyHardwareHealthCheckResponse(hardwareHealthCheckStates);\
\
        Set<Anomaly> anomalySet = probe.findAnomalies(eventData);\
        assertEquals(0, anomalySet.size());\
        assertThatEventData(eventData).hasCounter(AutoSmashProbe.TOTAL_CANDIDATES_METRIC, numberOfHosts);\
        assertThatEventData(eventData).hasCounter(AutoSmashProbe.TOTAL_ELIGIBLE_INSTANCES_METRIC, numberOfUnhealthy);\
\
        /* Second Run */\
        testTime.increment(10);\
        eventData = EventDataFactory.create();\
        anomalySet = probe.findAnomalies(eventData);\
        assertEquals(shouldFindCandidates ? 1 : 0, anomalySet.size());\
        if (shouldFindCandidates) \{\
            assertThatEventData(eventData).hasCounter(String.format(AutoSmashProbe.SERVICE_ANOMALY_HOSTS_CREATED, FAKE_APP_NAME), 1);\
        \}\
        assertThatEventData(eventData).hasCounter(AutoSmashProbe.TOTAL_CANDIDATES_METRIC, numberOfHosts);\
        assertThatEventData(eventData).hasCounter(AutoSmashProbe.TOTAL_ELIGIBLE_INSTANCES_METRIC, numberOfUnhealthy);\
        assertThatEventData(eventData).hasCounter(AutoSmashProbe.TOTAL_ELIGIBLE_INC_INSTANCES_METRIC, numberOfUnhealthy);\
        assertThatEventData(eventData).hasCounter(String.format(AutoSmashProbe.SERVICE_AVAILABILITY_METRIC, FAKE_APP_NAME), expectedAvailability);\
        assertThatEventData(eventData).hasCounter(AutoSmashProbe.SERVICE_DEGRADED_CONDITION_METRIC, expectedAvailability < AutoSmashProbe.DEFAULT_THRESHOLD ? 1 : 0);\
    \}\
\
\
    /*\
     * Example test case:\
     * 8 total hosts\
     * 5 OK hosts with 1 bad hardware\
     * 3 InC hosts\
     *\
     * The availability should be 50%\
     */\
    @Test\
    @Parameters(\{"50, 50, 8, 3, 1, false",\
            "50, 25, 8, 3, 3, true",\
            "75, 50, 8, 3, 1, true",\
            "75, 50, 8, 0, 4, true"\
    \})\
    public void testHostOKButBadHardware(Double threshold,\
                                         long expectedAvailability,\
                                         int numberOfHosts,\
                                         int numberOfUnhealthy,\
                                         int numberOfOKHostsBadHW,\
                                         boolean degradedService) \{\
\
        config.setAvailabilityThresholdPerService(ImmutableMap.of(FAKE_APP_NAME, threshold.toString()));\
        setUpProbe();\
\
        Map<EndpointAddress, CheckHealthResponse> hardwareHealthCheckStates =\
                createHardwareHealthCheckStates(numberOfHosts, numberOfUnhealthy);\
\
        // Set one OK host to Bad HW\
        hardwareHealthCheckStates.entrySet()\
                .stream()\
                .filter(kvp -> kvp.getValue().getHostState().equals(HostState.HEALTHY))\
                .limit(numberOfOKHostsBadHW)\
                .forEach(kvp -> hardwareHealthCheckStates\
                    .replace(kvp.getKey(), new CheckHealthResponse(endpointAddress, HostState.NOT_HEALTHY)));\
\
        Set<String> whitelistedServices = new HashSet<>();\
        whitelistedServices.add(FAKE_APP_NAME);\
        config.setWhitelistedServices(whitelistedServices);\
\
        Mockito.reset(mockHardwareHealthCheckClient);\
        setUpHealthyHardwareHealthCheckResponse(hardwareHealthCheckStates);\
\
        Set<Anomaly> anomalySet = probe.findAnomalies(eventData);\
        assertEquals(0, anomalySet.size());\
        assertThatEventData(eventData).hasCounter(AutoSmashProbe.TOTAL_CANDIDATES_METRIC, numberOfHosts);\
        assertThatEventData(eventData).hasCounter(AutoSmashProbe.TOTAL_ELIGIBLE_INSTANCES_METRIC, numberOfUnhealthy + numberOfOKHostsBadHW);\
        assertThatEventData(eventData).hasCounter(AutoSmashProbe.TOTAL_INC_HOSTS, numberOfUnhealthy);\
        assertThatEventData(eventData).hasCounter(AutoSmashProbe.TOTAL_ELIGIBLE_INC_INSTANCES_METRIC, numberOfUnhealthy);\
\
        /* Second Run */\
        testTime.increment(10);\
        eventData = EventDataFactory.create();\
        anomalySet = probe.findAnomalies(eventData);\
        assertEquals(degradedService ? 0 : 1, anomalySet.size());\
\
        assertThatEventData(eventData).hasCounter(AutoSmashProbe.TOTAL_CANDIDATES_METRIC, numberOfHosts);\
        assertThatEventData(eventData).hasCounter(AutoSmashProbe.TOTAL_ELIGIBLE_INSTANCES_METRIC, numberOfUnhealthy  + numberOfOKHostsBadHW);\
        assertThatEventData(eventData).hasCounter(AutoSmashProbe.TOTAL_INC_HOSTS, numberOfUnhealthy);\
        assertThatEventData(eventData).hasCounter(AutoSmashProbe.TOTAL_ELIGIBLE_INC_INSTANCES_METRIC, numberOfUnhealthy);\
        assertThatEventData(eventData).hasCounter(String.format(AutoSmashProbe.SERVICE_AVAILABILITY_METRIC, FAKE_APP_NAME), expectedAvailability);\
        assertThatEventData(eventData).hasCounter(AutoSmashProbe.SERVICE_DEGRADED_CONDITION_METRIC, degradedService ? 1 : 0);\
    \}\
\
    /*\
     * Example test case:\
     * 8 total hosts\
     * 4 OK hosts\
     * 4 InC Hosts\
     * 1 InC hosts with 1 okay hardware\
     *\
     * The availability should be 50%\
     */\
    @Test\
    @Parameters(\{"50, 50, 8, 4, 1, false",\
            "50, 25, 8, 6, 0, true"\
    \})\
    public void testHostIncButOkayHardware(Double threshold,\
                                           long expectedAvailability,\
                                           int numberOfHosts,\
                                           int numberOfIncHosts,\
                                           int numberOfIncHostsOkHW,\
                                           boolean degradedService) \{\
        config.setAvailabilityThresholdPerService(ImmutableMap.of(FAKE_APP_NAME, threshold.toString()));\
        setUpProbe();\
\
        Map<EndpointAddress, CheckHealthResponse> hardwareHealthCheckStates =\
                createHardwareHealthCheckStates(numberOfHosts, numberOfIncHosts);\
\
        // Set one InC host to okay HW\
        hardwareHealthCheckStates.entrySet()\
                .stream()\
                .filter(kvp -> kvp.getValue().getHostState().equals(HostState.NOT_HEALTHY))\
                .limit(numberOfIncHostsOkHW)\
                .forEach(kvp -> hardwareHealthCheckStates\
                        .replace(kvp.getKey(), new CheckHealthResponse(endpointAddress, HostState.HEALTHY)));\
\
        Set<String> whitelistedServices = new HashSet<>();\
        whitelistedServices.add(FAKE_APP_NAME);\
        config.setWhitelistedServices(whitelistedServices);\
\
        Mockito.reset(mockHardwareHealthCheckClient);\
        setUpHealthyHardwareHealthCheckResponse(hardwareHealthCheckStates);\
\
        Set<Anomaly> anomalySet = probe.findAnomalies(eventData);\
        assertEquals(0, anomalySet.size());\
        assertThatEventData(eventData)\
                .hasCounter(AutoSmashProbe.TOTAL_CANDIDATES_METRIC, numberOfHosts)\
                .hasCounter(AutoSmashProbe.TOTAL_INC_HOSTS, numberOfIncHosts)\
                .hasCounter(AutoSmashProbe.TOTAL_ELIGIBLE_INSTANCES_METRIC, numberOfIncHosts - numberOfIncHostsOkHW)\
                .hasCounter(AutoSmashProbe.TOTAL_ELIGIBLE_INC_INSTANCES_METRIC, numberOfIncHosts - numberOfIncHostsOkHW);\
\
        /* Second Run */\
        testTime.increment(10);\
        eventData = EventDataFactory.create();\
        anomalySet = probe.findAnomalies(eventData);\
        assertEquals(degradedService ? 0 : 1, anomalySet.size());\
\
        assertThatEventData(eventData)\
                .hasCounter(AutoSmashProbe.TOTAL_CANDIDATES_METRIC, numberOfHosts)\
                .hasCounter(AutoSmashProbe.TOTAL_ELIGIBLE_INSTANCES_METRIC, numberOfIncHosts - numberOfIncHostsOkHW)\
                .hasCounter(AutoSmashProbe.TOTAL_INC_HOSTS, numberOfIncHosts)\
                .hasCounter(AutoSmashProbe.TOTAL_ELIGIBLE_INC_INSTANCES_METRIC, numberOfIncHosts - numberOfIncHostsOkHW)\
                .hasCounter(String.format(AutoSmashProbe.SERVICE_AVAILABILITY_METRIC, FAKE_APP_NAME), expectedAvailability)\
                .hasCounter(AutoSmashProbe.SERVICE_DEGRADED_CONDITION_METRIC, degradedService ? 1 : 0);\
    \}\
\
    @Test\
    public void testPreconditionTimeout() \{\
\
        config.setHardwareHealthCheckTimeoutMillis(100);\
\
        setUpProbe();\
\
        // Never complete the check to simulate a long hanging connection;\
        when(mockHardwareHealthCheckClient.checkHealth(\
            endpointArgumentCaptor.capture(), any()))\
                .thenAnswer(invocationOnMock -> new CompletableFuture<>());\
\
        // register an app instance as unhealthy\
        registerApp(endpointAddress, FAKE_CELL, FAKE_APP_NAME);\
        Set<Anomaly> anomalySet = probe.findAnomalies(eventData);\
        assertEquals(0, anomalySet.size());\
\
        assertThatEventData(eventData).hasCounter(AutoSmashProbe.PRECONDITION_CHECK_TIMEOUT_COUNT_METRIC, 1);\
\
        assertThatEventData(eventData).hasCounter(AutoSmashProbe.TOTAL_CANDIDATES_METRIC, 1);\
        assertThatEventData(eventData).hasCounter(String.format(AutoSmashProbe.TOTAL_CANDIDATES_SERVICE_METRIC, FAKE_APP_NAME), 1);\
\
        assertThatEventData(eventData).hasCounter(AutoSmashProbe.TOTAL_ELIGIBLE_INSTANCES_METRIC, 0);\
        assertThatEventData(eventData).hasCounter(String.format(AutoSmashProbe.TOTAL_ELIGIBLE_INSTANCES_SERVICE_METRIC, FAKE_APP_NAME), 0);\
    \}\
\
    @Test\
    public void testSkipLockedHosts() \{\
        config.setWhitelistedServices(Lists.newArrayList(INDEX_NODE_APP_NAME));\
        config.setAvailabilityThresholdPerService(ImmutableMap.of(INDEX_NODE_APP_NAME, "0"));\
        setUpProbe();\
\
        EndpointAddress eaLocked = EndpointAddress.valueOf("127.0.0.2:1113");\
        EndpointAddress eaNotLocked = EndpointAddress.valueOf("127.0.0.3:1113");\
\
        registerApp(eaLocked, FAKE_CELL, INDEX_NODE_APP_NAME, ApplicationStatus.OK);\
        registerApp(eaNotLocked, FAKE_CELL, INDEX_NODE_APP_NAME, ApplicationStatus.OK);\
        when(elrondClient.getSemanticHosts(any(), any()))\
                .thenReturn(CompletableFuture.completedFuture(Sets.newLinkedHashSet(new SemanticHost(eaLocked, Semantic.LOCKED))));\
\
        ImmutableMap<EndpointAddress, CheckHealthResponse> hardwareHealthCheckStates = ImmutableMap.of(\
                eaNotLocked, new CheckHealthResponse(eaNotLocked, HostState.NOT_HEALTHY));\
        Mockito.reset(mockHardwareHealthCheckClient);\
        setUpHealthyHardwareHealthCheckResponse(hardwareHealthCheckStates);\
\
        Set<Anomaly> anomalySet = probe.findAnomalies(eventData);\
        assertEquals(0, anomalySet.size());\
\
        testTime.increment(10);\
\
        // probe should find not-locked host as anomalous but ignore the locked host which is also anomalous otherwise\
        eventData = EventDataFactory.create();\
        anomalySet = probe.findAnomalies(eventData);\
        assertEquals(1, anomalySet.size());\
        assertThat(anomalySet.iterator().next().getEntityName().toString())\
                .isEqualTo(String.format(\
                        SharedConstants.Autosmash.UNHEALTHY_HOST_ANOMALY_ENTITY_NAME_FORMAT,\
                        INDEX_NODE_APP_NAME,\
                        eaNotLocked.stumpyEndpoint()));\
        assertThatEventData(eventData)\
                .hasCounter(HOSTS_FILTERED_BY_SEMANTIC, 1)\
                .hasCounter(String.format(AutoSmashProbe.SERVICE_ANOMALY_HOSTS_CREATED, INDEX_NODE_APP_NAME), 1)\
                .hasCounter(AutoSmashProbe.TOTAL_CANDIDATES_METRIC, 1)\
                .hasCounter(AutoSmashProbe.TOTAL_ELIGIBLE_INSTANCES_METRIC, 1)\
                .hasCounter(String.format(AutoSmashProbe.TOTAL_ELIGIBLE_INSTANCES_SERVICE_METRIC, INDEX_NODE_APP_NAME), 1)\
                .hasCounter(String.format(AutoSmashProbe.TOTAL_CANDIDATES_SERVICE_METRIC, INDEX_NODE_APP_NAME), 1);\
    \}\
\
    @Test\
    public void testDoNotSkipHostsOnElrondQueryFailure() \{\
        config.setWhitelistedServices(Lists.newArrayList(INDEX_NODE_APP_NAME));\
        config.setAvailabilityThresholdPerService(ImmutableMap.of(INDEX_NODE_APP_NAME, "0"));\
        setUpProbe();\
\
        EndpointAddress eaLocked = EndpointAddress.valueOf("127.0.0.2:1113");\
\
        registerApp(eaLocked, FAKE_CELL, INDEX_NODE_APP_NAME, ApplicationStatus.OK);\
\
        CompletableFuture<Set<SemanticHost>> elrondResultFuture = new CompletableFuture<>();\
        elrondResultFuture.completeExceptionally(new Exception("Was asked to fail"));\
        when(elrondClient.getSemanticHosts(any(), any())).thenReturn(elrondResultFuture);\
\
        ImmutableMap<EndpointAddress, CheckHealthResponse> hardwareHealthCheckStates = ImmutableMap.of(\
                eaLocked, new CheckHealthResponse(eaLocked, HostState.NOT_HEALTHY));\
        Mockito.reset(mockHardwareHealthCheckClient);\
        setUpHealthyHardwareHealthCheckResponse(hardwareHealthCheckStates);\
\
        Set<Anomaly> anomalySet = probe.findAnomalies(eventData);\
        assertEquals(0, anomalySet.size());\
\
        testTime.increment(10);\
\
        // probe should find the host as anomalous because query to elrond failed and we assumed that none are locked by default\
        eventData = EventDataFactory.create();\
        anomalySet = probe.findAnomalies(eventData);\
        assertEquals(1, anomalySet.size());\
        assertThat(anomalySet.iterator().next().getEntityName().toString())\
                .isEqualTo(String.format(\
                        SharedConstants.Autosmash.UNHEALTHY_HOST_ANOMALY_ENTITY_NAME_FORMAT,\
                        INDEX_NODE_APP_NAME,\
                        eaLocked.stumpyEndpoint()));\
        assertThatEventData(eventData)\
                .hasCounter(LOCKED_HOSTS_QUERY_FAILED, 1)\
                .hasCounter(HOSTS_FILTERED_BY_SEMANTIC, 0)\
                .hasCounter(String.format(AutoSmashProbe.SERVICE_ANOMALY_HOSTS_CREATED, INDEX_NODE_APP_NAME), 1)\
                .hasCounter(AutoSmashProbe.TOTAL_CANDIDATES_METRIC, 1)\
                .hasCounter(AutoSmashProbe.TOTAL_ELIGIBLE_INSTANCES_METRIC, 1)\
                .hasCounter(String.format(AutoSmashProbe.TOTAL_ELIGIBLE_INSTANCES_SERVICE_METRIC, INDEX_NODE_APP_NAME), 1)\
                .hasCounter(String.format(AutoSmashProbe.TOTAL_CANDIDATES_SERVICE_METRIC, INDEX_NODE_APP_NAME), 1);\
    \}\
\
    private Map<EndpointAddress, CheckHealthResponse> createHardwareHealthCheckStates(\
        int numberOfHosts, int numberOfUnhealthy\
    ) \{\
        Map<EndpointAddress, CheckHealthResponse> hardwareHealthCheckStates = new HashMap<>();\
\
        for (int i = 0; i < numberOfHosts; i++) \{\
            EndpointAddress host = new EndpointAddress(String.format("127.0.0.%s", i + 2), FAKE_PORT);\
\
            if(i < numberOfUnhealthy) \{\
                hardwareHealthCheckStates.put(host, new CheckHealthResponse(host, HostState.NOT_HEALTHY));\
                registerApp(host, FAKE_CELL, FAKE_APP_NAME, ApplicationStatus.INCOMMUNICADO);\
            \}\
            else \{\
                hardwareHealthCheckStates.put(host, new CheckHealthResponse(host, HostState.HEALTHY));\
                registerApp(host, FAKE_CELL, FAKE_APP_NAME, ApplicationStatus.OK);\
            \}\
        \}\
\
        return hardwareHealthCheckStates;\
    \}\
\
    @After\
    @Override\
    public void tearDown() throws Exception \{\
        testTime.dispose();\
        super.tearDown();\
    \}\
\
    /**\
     * Register app.\
     * - ApplicationStatus is default to OK,\
     */\
    private void registerApp(EndpointAddress endpointAddress, String cell, String appName) \{\
        registerApp(endpointAddress, cell, appName, ApplicationStatus.OK);\
    \}\
\
    private void registerApp(EndpointAddress endpointAddress, String cell, String appName, ApplicationStatus status) \{\
        Registration registration = new RegistrationImpl(cell,\
                appName,\
                endpointAddress.toString(),\
                endpointAddress.toString(),\
                new Location(CLUSTER, AREA, endpointAddress.getHost(), "test-rack"),\
                endpointAddress,\
                status,\
                UpdateCookie.EMPTY,\
                null);\
\
        registryReader.register(registration);\
    \}\
\
    private void setUpHealthyHardwareHealthCheckResponse(Map<EndpointAddress, CheckHealthResponse> hostStates) \{\
        when(mockHardwareHealthCheckClient.checkHealth(endpointArgumentCaptor.capture(), any()))\
            .thenAnswer(invocationOnMock -> \{\
                EndpointAddress endpoint = invocationOnMock.getArgumentAt(0, EndpointAddress.class);\
                if (endpoint != null) \{\
                    return CompletableFuture.completedFuture(\
                            hostStates.getOrDefault(endpoint, new CheckHealthResponse(endpoint, HostState.NOT_HEALTHY))\
                    );\
                \} else \{\
                    return CompletableFuture.completedFuture(null);\
                \}\
            \});\
    \}\
\}}
