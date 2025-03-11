{\rtf1\ansi\ansicpg1252\cocoartf2761
\cocoatextscaling0\cocoaplatform0{\fonttbl\f0\fnil\fcharset0 HelveticaNeue;}
{\colortbl;\red255\green255\blue255;\red236\green244\blue251;\red17\green21\blue26;}
{\*\expandedcolortbl;;\cssrgb\c94118\c96471\c98824;\cssrgb\c8235\c10588\c13725;}
\margl1440\margr1440\vieww11520\viewh8400\viewkind0
\deftab720
\pard\pardeftab720\partightenfactor0

\f0\fs32 \cf2 \cb3 \expnd0\expndtw0\kerning0
\outl0\strokewidth0 \strokec2 package s3.index.preconditions.host;\
\
import s3.commons.log.S3Logger;\
import s3.commons.net.EndpointAddress;\
import s3.commons.util.Pair;\
import s3.index.hardware.client.CheckHealthResponse;\
import s3.index.hardware.client.HardwareHealthCheckClient;\
import s3.index.hardware.client.HostState;\
import s3.index.preconditions.client.PingAddressReachability;\
import s3.index.preconditions.common.PreconditionCheckerResult;\
import s3.querylogbuilder.EventData;\
\
import java.util.concurrent.CompletableFuture;\
import java.util.concurrent.ExecutionException;\
\
public class HostHealthPreconditionChecker \{\
\
    protected final S3Logger log = new S3Logger();\
    static final String HOST_HAS_ONLY_SENTINEL_ERROR = "host.has.only.sentinel.error.count";\
    static final String FAILURE_PINGABLE_METRIC = "failure.pingable.host";\
    static final String UNEXPECTED_RESPONSE_METRIC = "unexpected.handy.manny.response";\
    static final String OHA_ERROR_REASON = "oha.error.reason.%s";\
\
    private static final int INET_TIMEOUT_MS = 5_000;\
\
    private final HardwareHealthCheckClient hardwareHealthCheckClient;\
    private final PingAddressReachability pingAddressReachability;\
\
    public HostHealthPreconditionChecker(\
        HardwareHealthCheckClient hardwareHealthCheckClient,\
        PingAddressReachability pingAddressReachability\
    ) \{\
        this.hardwareHealthCheckClient = hardwareHealthCheckClient;\
        this.pingAddressReachability = pingAddressReachability;\
    \}\
\
    /**\
     * Check if the host:port is healthy or not.\
     * In case of unhealthy host, it will return true as it's eligible for fix.\
     *\
     * @param endpointAddress host endpoint to be checked\
     * @param eventData an \{@link EventData\} object.\
     * @return \{@link PreconditionCheckerResult\} to indicate if the host is eligible (unhealthy)\
     */\
    public PreconditionCheckerResult isEligibleForWorkflow(EndpointAddress endpointAddress, EventData eventData) \{\
        final Pair<Boolean, CheckHealthResponse> healthStatus;\
\
        try \{\
            healthStatus = hardwareHealthCheckClient.checkHealth(endpointAddress, eventData)\
                .thenApply((status) -> checkHealthStatus(endpointAddress, status, eventData))\
                .get();\
        \} catch (InterruptedException e) \{\
            Thread.currentThread().interrupt();\
            return new PreconditionCheckerResult(false, e.getMessage());\
        \} catch (ExecutionException e) \{\
            return new PreconditionCheckerResult(false, e.getMessage());\
        \}\
\
        return new PreconditionCheckerResult(!healthStatus.getFirst(),\
                String.format("Host is %s. hardware health check returned: %s.",\
                        healthStatus.getFirst() ? "Healthy" : "Not Healthy",\
                        healthStatus.getSecond().getHostState().name()));\
    \}\
\
    /**\
     * Check if the host:port is healthy or not.\
     * In case of unhealthy host, it will return true as it's eligible for fix.\
     *\
     * @param endpointAddress host endpoint to be checked\
     * @param eventData an \{@link EventData\} object.\
     * @return boolean to indicate if the host is eligible (unhealthy)\
     */\
    public CompletableFuture<Boolean> isEligibleForProbe(EndpointAddress endpointAddress,\
                                                         String app,\
                                                         EventData eventData) \{\
        return hardwareHealthCheckClient.checkHealth(endpointAddress, eventData)\
            .thenApply((status) -> checkHealthStatus(endpointAddress, status, eventData))\
            .thenApply((statusPair) -> !statusPair.getFirst())\
            .exceptionally((p) -> false);\
    \}\
\
    private boolean isReachable(String ip, EventData eventData) \{\
        return pingAddressReachability.isReachable(ip, INET_TIMEOUT_MS, eventData);\
    \}\
\
    /**\
     * Checks whether the endpoint is healthy or not.\
     *\
     * @param endpointAddress to be checked\
     * @param eventData an \{@link EventData\} object\
     * @return Pair<Boolean, CheckHealthResponse> where the first value identifies whether the host is healthy\
     * and the second value is the CheckHealthResponse state.\
     */\
    private Pair<Boolean, CheckHealthResponse> checkHealthStatus(EndpointAddress endpointAddress,\
                                                                 CheckHealthResponse checkHealthResponse,\
                                                                 EventData eventData) \{\
        eventData.incCounter(FAILURE_PINGABLE_METRIC, 0);\
        eventData.incCounter(HOST_HAS_ONLY_SENTINEL_ERROR, 0);\
\
        /*\
         * The cases when the host is not considered healthy are:\
         *  - Hardware health check responds with NOT_HEALTHY\
         *  - Hardware health check times out or an error is returned. For those two cases we check if the host is reachable before\
         *  saying it's not healthy.\
         */\
        boolean isHealthy = true;\
        HostState hostState = checkHealthResponse.getHostState();\
        switch (hostState) \{\
            case NOT_HEALTHY:\
                isHealthy = isNotHealthyWithOnlySentinelError(checkHealthResponse, eventData);\
\
                if (log.isDebugEnabled()) \{\
                    log.debug("isEligibleForWorkflow", "Host is not healthy",\
                            "Endpoint", endpointAddress,\
                            "Response", hostState);\
                \}\
\
                checkHealthResponse.getErrorList().forEach(error -> \{\
                    log.debug("checkHealthStatus", "Found an an unhealthy host with error",\
                            "endpointAddress", endpointAddress,\
                            "errorName", error.getErrorName(),\
                            "errorMessage", error.getErrorMessage());\
\
                    eventData.incCounter(String.format(OHA_ERROR_REASON, error.getErrorName()));\
                \});\
\
                break;\
            case UNEXPECTED_RESPONSE:\
            case TIMEOUT:\
                if (!isReachable(endpointAddress.getHost(), eventData)) \{\
                    isHealthy = false;\
                    eventData.incCounter(FAILURE_PINGABLE_METRIC, 1);\
                    if (log.isDebugEnabled()) \{\
                        log.debug("isEligibleForWorkflow", "Host is not healthy and not reachable",\
                                "Endpoint", endpointAddress,\
                                "Response", hostState);\
                    \}\
                \} else \{\
                    isHealthy = true;\
                    if (log.isDebugEnabled()) \{\
                        log.debug("isEligibleForWorkflow", "Host is not healthy but reachable",\
                                "Endpoint", endpointAddress,\
                                "Response", hostState);\
                    \}\
                \}\
                break;\
            // Findbugs complains if there isn't a default. If the variable is not initialized, the compiler will fail.\
            // In this case we need to initialize the variable and have the default.\
            case HEALTHY:\
                isHealthy = true;\
                if (log.isDebugEnabled()) \{\
                    log.debug("isEligibleForWorkflow", "Host is healthy",\
                            "Endpoint", endpointAddress,\
                            "Response", hostState);\
                \}\
                break;\
            default:\
                isHealthy = true;\
                log.warn("isEligibleForWorkflow", "Unexpected hardware health check state",\
                        "Endpoint", endpointAddress,\
                        "Response", hostState);\
                eventData.incCounter(UNEXPECTED_RESPONSE_METRIC, 1);\
        \}\
\
        return new Pair<>(isHealthy, checkHealthResponse);\
    \}\
\
    private boolean isNotHealthyWithOnlySentinelError(CheckHealthResponse checkHealthResponse, EventData eventData) \{\
        if (checkHealthResponse.getHostState().equals(HostState.NOT_HEALTHY) &&\
                checkHealthResponse.getErrorList().size() == 1 &&\
                checkHealthResponse.getErrorList().get(0).getErrorMessage().toUpperCase().contains("SENTINEL_BM_SUSPENDED")) \{\
            eventData.incCounter(HOST_HAS_ONLY_SENTINEL_ERROR, 1);\
            return true;\
        \}\
\
        return false;\
    \}\
\}}