import com.amazon.util.time.TimeSource;\
import com.fasterxml.jackson.databind.JavaType;\
import com.google.common.collect.ImmutableMap;\
import com.google.common.collect.ImmutableSet;\
import com.google.common.collect.Sets;\
import s3.ElrondClient.ElrondClient;\
import s3.anomalystore.common.Anomaly;\
import s3.anomalystore.common.AnomalyType;\
import s3.anomalystore.common.Entity;\
import s3.anomalystore.common.EntityType;\
import s3.applicationregistry.ApplicationRegistryException;\
import s3.applicationregistry.ApplicationStatus;\
import s3.applicationregistry.BasicFilter;\
import s3.applicationregistry.Registration;\
import s3.applicationregistry.RegistryReader;\
import s3.commons.log.S3Logger;\
import s3.commons.net.EndpointAddress;\
import s3.commons.util.Pair;\
import s3.host.Semantic;\
import s3.host.SemanticHost;\
import s3.index.partitioninfo.client.CacheBackedPartitionInfoReader;\
import s3.index.preconditions.host.HostHealthPreconditionChecker;\
import s3.index.probe.common.FailedHost;\
import s3.index.probe.config.AutoSmashProbeConfig;\
import s3.kmi.KeymapInstanceFinder;\
import s3.moria.common.Probe;\
import s3.moria.common.constants.SharedConstants;\
import s3.moria.common.store.KeyValueStore;\
import s3.moria.common.util.BoundedFutureQueue;\
import s3.moria.common.util.ObjectMapperFactory;\
import s3.querylogbuilder.EventData;\
\
import java.util.Collection;\
import java.util.Collections;\
import java.util.Comparator;\
import java.util.HashSet;\
import java.util.List;\
import java.util.Map;\
import java.util.Optional;\
import java.util.Set;\
import java.util.concurrent.CompletableFuture;\
import java.util.concurrent.ExecutionException;\
import java.util.concurrent.TimeUnit;\
import java.util.concurrent.TimeoutException;\
import java.util.function.Function;\
import java.util.stream.Collectors;\
\
import static java.util.stream.Collectors.collectingAndThen;\
import static java.util.stream.Collectors.toMap;\
import static java.util.stream.Collectors.toSet;\
import static s3.moria.common.constants.SharedConstants.INDEX_NODE_APP_NAME;\
\
\
public class AutoSmashProbe implements Probe \{\
\
    static final Double DEFAULT_THRESHOLD = 75D;\
    private static final long ELROND_TIMEOUT_MS = 5000;\
\
    static final String TOTAL_ELIGIBLE_INSTANCES_SERVICE_METRIC = "%s.unhealthy.instances";\
    static final String TOTAL_ELIGIBLE_INSTANCES_METRIC = "unhealthy.instances";\
    static final String TOTAL_ELIGIBLE_INC_INSTANCES_METRIC = "unhealthy.inc.instances";\
    static final String TOTAL_INC_HOSTS = "inc.candidates";\
    static final String TOTAL_CANDIDATES_SERVICE_METRIC = "%s.candidates";\
    static final String TOTAL_CANDIDATES_METRIC = "candidates";\
    static final String SERVICE_AVAILABILITY_METRIC = "%s.hosts.available.percentage";\
    static final String SERVICE_AVAILABILITY_DC_METRIC = "%s.hosts.available.in.%s.percentage";\
    static final String SERVICE_DEGRADED_CONDITION_METRIC = "service.degraded.condition";\
    static final String SERVICE_ANOMALY_HOSTS_TOTAL = "%s.unhealthy.anomaly.total";\
    static final String SERVICE_ANOMALY_HOSTS_CREATED = "%s.unhealthy.anomaly.created";\
    static final String UPDATE_REPORTED_HOSTS_TIMER_METRIC = "update.reported.hosts.timer";\
    static final String DUPLICATED_RECORDS_DFDD_COUNT_METRIC = "duplicated.records.dfdd";\
    static final String PRECONDITION_CHECK_TIMEOUT_COUNT_METRIC = "precondition.check.timeout.count";\
    static final String HOSTS_FILTERED_BY_SEMANTIC = "hosts.filtered.by.semantic";\
    static final String LOCKED_HOSTS_QUERY_FAILED = "elrond.locked.hosts.query.fail";\
\
    private static final String REGISTRATION_NOT_FOUND_METRIC = "service.registration.not.found";\
\
    // LOCKED (or any other) semantic makes sense only for these services\
    private static final Set<String> SERVICES_WITH_LOCKED_SEMANTIC = Sets.newHashSet(INDEX_NODE_APP_NAME);\
\
    private static final S3Logger log = new S3Logger();\
    private static final String REPORTED_HOSTS = "reported_hosts";\
    private static final JavaType SET_OF_REPORTED_HOSTS = ObjectMapperFactory\
            .get().getTypeFactory().constructCollectionType(Set.class, FailedHost.class);\
\
    private final AutoSmashProbeConfig config;\
    private final RegistryReader registryReader;\
    private final CacheBackedPartitionInfoReader partitionInfoReader;\
    private final HostHealthPreconditionChecker hostHealthPreconditionChecker;\
    private final KeyValueStore reportedHostStore;\
    private final String cellId;\
    private final KeymapInstanceFinder kmiFinder;\
    private final ElrondClient elrondClient;\
    private final ImmutableMap<String, Double> availabilityThresholdByService;\
\
    public AutoSmashProbe(AutoSmashProbeConfig config,\
                          RegistryReader registryReader,\
                          CacheBackedPartitionInfoReader partitionInfoReader,\
                          HostHealthPreconditionChecker hostHealthPreconditionChecker,\
                          KeyValueStore reportedHostStore,\
                          String cellId,\
                          KeymapInstanceFinder kmiFinder,\
                          ElrondClient elrondClient) \{\
        this.config = config;\
        this.registryReader = registryReader;\
        this.partitionInfoReader = partitionInfoReader;\
        this.hostHealthPreconditionChecker = hostHealthPreconditionChecker;\
        this.reportedHostStore = reportedHostStore;\
        this.cellId = cellId;\
        this.kmiFinder = kmiFinder;\
        this.elrondClient = elrondClient;\
\
        // Create a map of availability per service to avoid parsing it every time.\
        this.availabilityThresholdByService = config.getAvailabilityThresholdPerService()\
                .entrySet()\
                .stream()\
                .collect(collectingAndThen(\
                        toMap(\
                                Map.Entry::getKey,\
                                entry -> Double.parseDouble(entry.getValue())),\
                        ImmutableMap::copyOf));\
    \}\
\
    /**\
     * Kick off the probe to scan through the hosts registered in the \{@link RegistryReader\} for the current cell.\
     *\
     * @param eventData EventData\
     */\
    @Override\
    public Set<Anomaly> findAnomalies(EventData eventData) \{\
        partitionInfoReader.refresh(eventData);\
        long timeSeen = TimeSource.now();\
\
        final Map<String, Set<FailedHost>> eligibleHostsPerService = filterEligibleHosts(timeSeen, eventData);\
\
        // Update KeyValueStore and emit longest candidate metric\
        updateReportedHostStore(eligibleHostsPerService, eventData);\
\
        // Filter the longest host for each service (limited by configuration) and generate anomalies\
        return eligibleHostsPerService\
                .entrySet()\
                .stream()\
                .map(entry -> filterFailedHosts(timeSeen, entry.getKey(), entry.getValue(), eventData))\
                .flatMap(Set::stream)\
                .map(this::createAnomaly)\
                .collect(toSet());\
    \}\
\
    private Set<FailedHost> filterFailedHosts(\
            long timeSeen,\
            String serviceName,\
            Collection<FailedHost> failedHosts,\
            EventData eventData\
    ) \{\
        Set<FailedHost> failedHostsFiltered = failedHosts.stream()\
                .filter(failedHost -> timeSeen - failedHost.getFirstSeenTime() >= config.getFailureTimeMillis())\
                .collect(Collectors.toSet());\
        eventData.setCounter(String.format(SERVICE_ANOMALY_HOSTS_TOTAL, serviceName), failedHostsFiltered.size());\
        log.info("filterFailedHosts", "before limiting", "service", serviceName, "failedHosts", failedHostsFiltered);\
\
        Set<FailedHost> failedHostsFilteredAndLimited = failedHostsFiltered.stream()\
                .sorted(Comparator.comparingLong(FailedHost::getFirstSeenTime))\
                .limit(getMaxNumberOfAnomaliesByServiceType(serviceName))\
                .collect(Collectors.toSet());\
        eventData.setCounter(String.format(SERVICE_ANOMALY_HOSTS_CREATED, serviceName), failedHostsFilteredAndLimited.size());\
\
        log.info("filterFailedHosts", "after limiting", "service", serviceName, "failedHosts", failedHostsFilteredAndLimited);\
        return failedHostsFilteredAndLimited;\
    \}\
\
    /**\
     * Different services have different scale on number of hosts. For example, in IAD cell-0, we have\
     * 4 PAM instances, and we have over 25k BM/IndexNode. Therefore, we should define separate threshold\
     * on number of anomalies to be emitted based on service type. Since we have only a few services\
     * onboarded to AutoSmash, we now separate them by IndexNode vs. other services.\
     */\
    private long getMaxNumberOfAnomaliesByServiceType(String serviceName) \{\
        if (SharedConstants.INDEX_NODE_APP_NAME.equals(serviceName)) \{\
            return config.getMaximumNumberOfIndexNodeAnomalies();\
        \} else \{\
            return config.getMaximumNumberOfAnomaliesPerService();\
        \}\
    \}\
\
    /*\
     * Persist the reported hosts to the KeyValue store and emit longest candidate metric.\
     */\
    private void updateReportedHostStore(Map<String, Set<FailedHost>> eligibleHostsPerService, EventData eventData) \{\
        eventData.startTimer(UPDATE_REPORTED_HOSTS_TIMER_METRIC);\
        final Set<FailedHost> hostSet = eligibleHostsPerService\
                .values()\
                .stream()\
                .flatMap(Set::stream)\
                .collect(toSet());\
\
        reportedHostStore.put(eventData, REPORTED_HOSTS, hostSet);\
        eventData.endTimer(UPDATE_REPORTED_HOSTS_TIMER_METRIC);\
    \}\
\
    /*\
     * Based on the whitelisted services from configuration, scan the hosts in the \{@link RegistryReader\} for the current cell\
     * and check against the \{@link HostHealthPreconditionChecker\} if the host is eligible (has problems) and return a map\
     * of service identified by its port and a set of candidates.\
     *\
     * If the host has already been identified as a candidate in previous runs, it'll use the time seen before.\
     */\
    private Map<String, Set<FailedHost>> filterEligibleHosts(long timeSeen, EventData eventData) \{\
\
        Map<EndpointAddress, FailedHost> previouslyReportedHostsMap = getReportedHostsMap(eventData);\
\
        eventData.setCounter(TOTAL_CANDIDATES_METRIC, 0);\
        eventData.setCounter(TOTAL_ELIGIBLE_INSTANCES_METRIC, 0);\
        eventData.setCounter(TOTAL_ELIGIBLE_INC_INSTANCES_METRIC, 0);\
        eventData.setCounter(REGISTRATION_NOT_FOUND_METRIC, 0);\
\
        log.debug("filterEligibleHosts", "configuration",\
                "whitelisted services", config.getWhitelistedServices(),\
                "endpoints previously reported", previouslyReportedHostsMap);\
\
        return config.getWhitelistedServices()\
                .stream()\
                .map(value -> new Pair<>(value, getEligibleEndpointForApp(value, eventData)))\
                .flatMap(pair -> pair.getSecond().stream().map(\
                        endpointAddress -> previouslyReportedHostsMap.getOrDefault(\
                                endpointAddress,\
                                new FailedHost(endpointAddress, pair.getFirst().toLowerCase(), timeSeen))\
                        )\
                )\
                .collect(Collectors.groupingBy(FailedHost::getDfddAppName, toSet()));\
    \}\
\
    /**\
     * Get the hosts that we recently reported as anomalies, if they exist.\
     */\
    private Map<EndpointAddress, FailedHost> getReportedHostsMap(EventData eventData) \{\
        return reportedHostStore\
                .<Set<FailedHost>>get(eventData, REPORTED_HOSTS, SET_OF_REPORTED_HOSTS)\
                .orElse(ImmutableSet.of())\
                .stream()\
                .collect(toMap(FailedHost::getEndpointAddress, Function.identity()));\
    \}\
\
    private List<Registration> getRegistrationsForApp(String app, EventData eventData) throws ApplicationRegistryException \{\
        registryReader.refresh();\
\
        Collection<Registration> registrationsForService =\
                registryReader.query(new BasicFilter(app, cellId, null, null), eventData);\
\
        // get the set of LOCKED hosts (if applicable for the given app) so that we can ignore them\
        // TODO remove this check when locked partitions rescue is automated\
        Set<String> lockedHosts = SERVICES_WITH_LOCKED_SEMANTIC.contains(app) ? getLockedHosts(eventData) : Collections.emptySet();\
\
        List<Registration> filteredRegistrationsForService = registrationsForService\
                .stream()\
                .filter(reg -> !lockedHosts.contains(reg.getEndpointAddress().getHost()))\
                .collect(Collectors.toList());\
\
        log.debug("getRegistrationsForApp", "Registered services being checked after filtering",\
                "Registrations", filteredRegistrationsForService);\
\
        eventData.setCounter(HOSTS_FILTERED_BY_SEMANTIC, (registrationsForService.size() - filteredRegistrationsForService.size()));\
        eventData.incCounter(TOTAL_CANDIDATES_METRIC, filteredRegistrationsForService.size());\
        eventData.setCounter(String.format(TOTAL_CANDIDATES_SERVICE_METRIC, app), filteredRegistrationsForService.size());\
\
        return filteredRegistrationsForService;\
    \}\
\
    /*\
     * Check against the \{@link HostHealthPreconditionChecker\} if the host is eligible (has problems).\
     */\
    private Set<EndpointAddress> getEligibleEndpointForApp(String app, EventData eventData) \{\
        Set<EndpointAddress> eligibleEndpoints;\
        Set<EndpointAddress> eligibleIncEndpoints;\
        Set<Registration> candidates = Collections.emptySet();\
        eventData.incCounter(PRECONDITION_CHECK_TIMEOUT_COUNT_METRIC, 0);\
        eventData.incCounter(DUPLICATED_RECORDS_DFDD_COUNT_METRIC, 0);\
\
        try \{\
            Collection<Registration> registrationService = getRegistrationsForApp(app, eventData);\
\
            // The probe should only consider OK and InC hosts.\
            candidates = registrationService\
                    .stream()\
                    .filter(reg -> reg.getStatus().equals(ApplicationStatus.INCOMMUNICADO)\
                            || reg.getStatus().equals(ApplicationStatus.OK))\
                    .collect(Collectors.toSet());\
\
            BoundedFutureQueue<Boolean> queue = new BoundedFutureQueue<>(config.getHardwareHealthCheckMaxRequests());\
\
            Map<EndpointAddress, CompletableFuture<Boolean>> checkHealthRequests = candidates\
                    .stream()\
                    .collect(toMap(\
                            Registration::getEndpointAddress,\
                            reg -> queue.pass(hostHealthPreconditionChecker.isEligibleForProbe(reg.getEndpointAddress(),\
                                    app,\
                                    eventData)),\
                            (r1, r2) -> \{\
                                eventData.incCounter(DUPLICATED_RECORDS_DFDD_COUNT_METRIC, 1);\
                                log.warn("getEligibleEndpointForApp", "Found duplicated records in DFDD", "Endpoint", r1, "App", app);\
                                return r1; // Return the first one seen.\
                            \}));\
\
            /*\
             * Checks whether each one of the hosts are eligible:\
             * - Meets the isEligibleForProbe criteria (bad hardware or timeout + unpingable)\
             */\
            eligibleEndpoints = checkHealthRequests.entrySet()\
                    .stream()\
                    .filter(result -> \{\
                        try \{\
                            return result.getValue().get(config.getHardwareHealthCheckTimeoutMillis(), TimeUnit.MILLISECONDS);\
                        \} catch (InterruptedException e) \{\
                            Thread.currentThread().interrupt();\
                            return false;\
                        \} catch (ExecutionException e) \{\
                            Thread.currentThread().interrupt();\
                            log.error("getEligibleEndpointForApp", "Failed to retrieve health status for host",\
                                    "Endpoint", result.getKey(), e);\
                            return false;\
                        \} catch (TimeoutException e) \{\
                            log.error("getEligibleEndpointForApp", "Failed to retrieve health status for host",\
                                    "Endpoint", result.getKey(), e);\
                            eventData.incCounter(PRECONDITION_CHECK_TIMEOUT_COUNT_METRIC);\
                            return false;\
                        \}\
                    \})\
                    .map(Map.Entry::getKey)\
                    .collect(toSet());\
        \} catch (ApplicationRegistryException e) \{\
            log.warn("getEligibleEndpointForApp", "Could not retrieve registrations", "App", app, e);\
            eventData.incCounter(REGISTRATION_NOT_FOUND_METRIC, 1);\
            eligibleEndpoints = new HashSet<>();\
        \}\
\
        //filter candidates to only include endpoint address with InC hosts to find unhealthy hosts that are also inc.\
        Set<EndpointAddress> incCandidates = candidates\
                .stream()\
                .filter(reg -> reg.getStatus().equals(ApplicationStatus.INCOMMUNICADO))\
                .map(registration -> registration.getEndpointAddress())\
                .collect(Collectors.toSet());\
\
            eligibleIncEndpoints = eligibleEndpoints\
                .stream()\
                .filter(result -> incCandidates.contains(result))\
                .collect(toSet());\
\
        eventData.incCounter(TOTAL_INC_HOSTS, incCandidates.size());\
        eventData.incCounter(TOTAL_ELIGIBLE_INC_INSTANCES_METRIC, eligibleIncEndpoints.size());\
        eventData.incCounter(TOTAL_ELIGIBLE_INSTANCES_METRIC, eligibleEndpoints.size());\
        eventData.setCounter(String.format(TOTAL_ELIGIBLE_INSTANCES_SERVICE_METRIC, app), eligibleEndpoints.size());\
        // Makes sure we have this metric even when it's 0.\
        eventData.incCounter(SERVICE_DEGRADED_CONDITION_METRIC, 0);\
        log.debug("getEligibleEndpointForApp", "eligible",\
                "app", app,\
                "endpoints", eligibleEndpoints);\
\
        if (candidates.size() > 0) \{\
\
            if (config.getCheckAvailabilityThresholdPerAz().contains(app)) \{\
                 return filterPerServiceAvailabilityPerAz(app, candidates, eligibleEndpoints, eventData);\
            \} else \{\
\
                long serviceAvailability = calculateServiceAvailability(candidates, eligibleEndpoints);\
\
                eventData.setCounter(String.format(SERVICE_AVAILABILITY_METRIC, app), serviceAvailability);\
\
                // If the service availability is below the threshold, don't return any eligible host and emit metrics.\
                // By default (no threshold in the config), if the availability is below 75% consider it degraded service\
                Double availabilityThreshold = availabilityThresholdByService.getOrDefault(app, DEFAULT_THRESHOLD);\
                if (availabilityThreshold != null && serviceAvailability < availabilityThreshold) \{\
                    eventData.incCounter(SERVICE_DEGRADED_CONDITION_METRIC, 1);\
\
                    return Collections.emptySet();\
                \}\
            \}\
        \} else \{\
            eventData.setCounter(String.format(SERVICE_AVAILABILITY_METRIC, app), 0);\
        \}\
\
        return eligibleEndpoints;\
    \}\
\
    /*\
     * The service availability is defined by the OK hosts - OK hosts eligible for probe / OK hosts\
     */\
    private long calculateServiceAvailability(Set<Registration> candidates,\
                                              Set<EndpointAddress> eligibleForProbeHosts) \{\
\
        // Find all the hosts that are OK in DFDD\
        Set<Registration> okHosts = candidates\
                .stream()\
                .filter(reg -> reg.getStatus().equals(ApplicationStatus.OK))\
                .collect(Collectors.toSet());\
\
        // From the list of all the hosts that are OK in DFDD count how many of them are not healthy.\
        long okHostsEligibleForProbeCount = eligibleForProbeHosts.stream()\
                .filter(a -> okHosts.stream().anyMatch(b -> a.getHost().equals(b.getEndpointAddress().getHost())))\
                .count();\
\
        return (okHosts.size() - okHostsEligibleForProbeCount) * 100 / candidates.size();\
    \}\
\
    private Set<EndpointAddress> filterPerServiceAvailabilityPerAz(String app,\
                                                                   Set<Registration> candidates,\
                                                                   Set<EndpointAddress> eligibleForProbeHosts,\
                                                                   EventData eventData) \{\
        // Since the set will be modified, creating a local copy.\
        Set<EndpointAddress> localEligibleForProbeHosts = new HashSet<>(eligibleForProbeHosts);\
\
        Set<String> datacenterInCandidates = candidates.stream()\
                .map(reg -> reg.getLocation().getDatacenter())\
                .collect(toSet());\
\
        for (String datacenter : datacenterInCandidates) \{\
            // Calculate the availability per AZ and remove from the list of eligible hosts if below the threshold.\
            long availabilityPerAz = calculateServiceAvailabilityPerAz(datacenter, candidates, eligibleForProbeHosts);\
            eventData.setCounter(String.format(SERVICE_AVAILABILITY_DC_METRIC, app, datacenter), availabilityPerAz);\
\
            Double availabilityThreshold = availabilityThresholdByService.getOrDefault(app, DEFAULT_THRESHOLD);\
            if (availabilityThreshold != null && availabilityPerAz < availabilityThreshold) \{\
                eventData.incCounter(SERVICE_DEGRADED_CONDITION_METRIC, 1);\
                localEligibleForProbeHosts.removeIf(endpointAddress -> \{\
                    Registration registration = candidates.stream().filter(reg -> reg.getEndpointAddress().equals(endpointAddress)).findFirst().get();\
                    return registration.getLocation().getDatacenter().equals(datacenter);\
                \});\
            \}\
        \}\
\
        return localEligibleForProbeHosts;\
    \}\
\
    /*\
     * The service availability is defined by the OK hosts - OK hosts eligible for probe / OK hosts\
     */\
    private long calculateServiceAvailabilityPerAz(String dc,\
                                                   Set<Registration> candidates,\
                                                   Set<EndpointAddress> eligibleForProbeHosts) \{\
\
        Collection<String> areas = kmiFinder.getAllAreasForAreaNameWithAlias(dc);\
\
        // Filter the candidates\
        Set<Registration> candidatesFiltered = candidates\
                .stream()\
                .filter(reg -> areas.stream().anyMatch(area -> area.equals(reg.getLocation().getDatacenter())))\
                .collect(toSet());\
\
        if (candidates.size() == 0) \{\
            return 0;\
        \}\
\
        // Find all the hosts that are OK in DFDD for the desired areas.\
        Set<Registration> okHostsForAreas = candidatesFiltered\
                .stream()\
                .filter(reg -> reg.getStatus().equals(ApplicationStatus.OK))\
                .collect(Collectors.toSet());\
\
        // From the list of all the hosts that are OK in DFDD count how many of them are not healthy.\
        long okHostsEligibleForProbeCount = eligibleForProbeHosts\
                .stream()\
                .filter(endpointAddress -> \{\
                    Optional<Registration> maybeRegistration = candidates.stream().filter(candidate -> candidate.getEndpointAddress().equals(endpointAddress)).findFirst();\
                    return okHostsForAreas\
                            .stream()\
                            .anyMatch(b -> maybeRegistration.get().getLocation().getDatacenter().equals(b.getLocation().getDatacenter()));\
                \})\
                .filter(reg -> areas.stream().anyMatch(area -> reg.getHost().contains(area)))\
                .count();\
        if (candidatesFiltered.size() > 0) \{\
            return (okHostsForAreas.size() - okHostsEligibleForProbeCount) * 100 / candidatesFiltered.size();\
        \} else \{\
            return 0;\
        \}\
    \}\
\
    private Anomaly createAnomaly(FailedHost failedHost) \{\
        return new Anomaly(\
                new Entity(\
                        String.format(\
                                SharedConstants.Autosmash.UNHEALTHY_HOST_ANOMALY_ENTITY_NAME_FORMAT,\
                                failedHost.getDfddAppName(),\
                                failedHost.getEndpointAddress().stumpyEndpoint()\
                        ),\
                        EntityType.HOST\
                ),\
                AnomalyType.HOSTUNHEALTHY,\
                1,\
                config.getAnomalyDuration());\
    \}\
\
    private Set<String> getLockedHosts(EventData eventData) \{\
        try \{\
            return elrondClient.getSemanticHosts(Semantic.LOCKED, eventData)\
                    .get(ELROND_TIMEOUT_MS, TimeUnit.MILLISECONDS)\
                    .stream()\
                    .map(SemanticHost::getHost)\
                    .collect(Collectors.toSet());\
        \} catch (InterruptedException e) \{\
            Thread.currentThread().interrupt();\
            throw new RuntimeException(e);\
        \} catch (ExecutionException | TimeoutException e) \{\
            log.error("getLockedHosts", "error getting locked hosts from Elrond", e);\
            eventData.incCounter(LOCKED_HOSTS_QUERY_FAILED, 1);\
\
            // let's consider no host is locked unless Elrond says so\
            return Collections.emptySet();\
        \}\
    \}\
\}}
