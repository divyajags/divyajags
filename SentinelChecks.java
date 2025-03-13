package s3.index.preconditions.host.base;

import s3.applicationregistry.ApplicationRegistryException;
import s3.applicationregistry.ApplicationStatus;
import s3.applicationregistry.BasicFilter;
import s3.applicationregistry.Registration;
import s3.applicationregistry.RegistryReader;
import s3.barney.metadata.chains.ChainConfiguration;
import s3.barney.metadata.chains.ChainConfigurationCache;
import s3.barney.metadata.chains.SentinelQuorum;
import s3.commons.log.S3Logger;

import javax.annotation.Nonnull;
import s3.moria.common.chain.ChainsConstants;
import s3.querylogbuilder.EventData;

import java.util.Collection;
import java.util.Collections;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;


/**
 * Precondition checks for workflows related to Sentinel Heat Management. See:
 * https://w.amazon.com/bin/view/S3/Systems/Index/Moria/Resources/Designs/Workflows/SentinelHeatBalancing/
 *
 * The metrics for this class should use 0/1 (success/failure) instead of incCounter(). This is because if the method
 * is called by the probe, it will iterate over all Sentinels. inCounter() will be multi-counting for some chains and
 * Sentinels thus gives misleading information.
 */
public class SentinelChecks {

    private static final S3Logger log = new S3Logger();

    /**
     * For each chain served by the Sentinel, check if all Sentinels serving the chain are 'ok'. If not,
     * the chain will be considered with at risk config.
     *
     * @param sentinelId the id of candidate sentinel
     * @param cache an up-to-date {@link ChainConfigurationCache}
     * @param okSentinels a set of ok sentinels
     *
     * @return a set of chains that are at risk, i.e., not all Sentinels serving the chain are healthy
     */
    public Set<String> findChainsWithAtRiskConfig(@Nonnull String sentinelId,
                                                  @Nonnull ChainConfigurationCache cache,
                                                  @Nonnull Set<String> okSentinels) {
        Set<String> chains = cache.getBussesForSentinel(sentinelId);

        // For each chain, if any of the sentinels serving the chain is not ok in DFDD,
        // we will conclude that the chain has a config at risk.
        Set<String> chainsAtRisk = chains.stream()
                .filter(chain ->
                        cache.getConfigForBus(chain)
                                // since server sentinels should include all client sentinels,
                                // we will just check if all server sentinels are healthy
                                .map(config -> !okSentinels.containsAll(getServerSentinels(config)))
                                .orElse(true))
        .collect(Collectors.toSet());

        if (!chainsAtRisk.isEmpty()) {
            log.info("findChainsWithAtRiskConfig", "find " + chainsAtRisk.size() + " chains at risk",
                    "sentinelId", sentinelId, "chains", chainsAtRisk);
        }

        return chainsAtRisk;
    }

    /**
     * For every chain, there can be only 2 cases:
     * - [degraded] client config sentinels are a proper subset of server config sentinels
     * - [healthy]  client config sentinels exactly equal server config sentinels
     *
     * @param sentinelId the id of candidate sentinel
     * @param cache an up-to-date {@link ChainConfigurationCache}
     *
     * @return a set of degraded chains
     */
    public Set<String> findChainsWithDegradedClientConfig(@Nonnull String sentinelId,
                                                          @Nonnull ChainConfigurationCache cache) {
        Set<String> chains = cache.getBussesForSentinel(sentinelId);

        Set<String> degradedChains = chains.stream().filter(chain ->
                cache.getConfigForBus(chain)
                        .map(chainConfig ->
                                !getServerSentinels(chainConfig).equals(getClientSentinels(chainConfig)))
                        .orElse(true))
                .collect(Collectors.toSet());

        if (!degradedChains.isEmpty()) {
            log.info("findChainsWithDegradedClientConfig",
                    "find " + degradedChains.size() + " degraded chains",
                    "sentinelId", sentinelId, "chains", chains);
        }

        return degradedChains;
    }

    /**
     * Check if the candidate Sentinel serves one cluster entirely, i.e., there exists one cluster where
     * all the chains in that cluster are served by the candidate Sentinel.
     *
     * If we found more than one clusters entirely served by the candidate sentinel, we will return the first one.
     *
     * @param sentinelId the id of candidate Sentinel
     * @param cache an up-to-date {@link ChainConfigurationCache}
     *
     * @return Optional<String> if present, return the cluster that the Sentinel serves entirely
     */
    public Optional<String> findClusterEntirelyOnSentinel(@Nonnull String sentinelId,
                                                          @Nonnull ChainConfigurationCache cache) {
        Set<String> chains = cache.getBussesForSentinel(sentinelId);

        // Get all the clusters served by the candidate Sentinel
        Set<String> clusters = chains.stream()
                .map(cache::getConfigForBus)
                .filter(Optional::isPresent)
                .map(chainConfig -> chainConfig.get().cluster())
                .collect(Collectors.toSet());

        log.debug("findClusterEntirelyOnSentinel", "clusters the sentinel serves",
                "sentinelId", sentinelId, "clusters", clusters);

        // For each cluster served by the Sentinel, we check if all chains in that cluster are
        // also served by the candidate Sentinel. If so, we will return such cluster.
        Set<String> candidateClusters = clusters.stream()
                .filter(cluster -> chains.containsAll(cache.getBussesForCluster(cluster)))
                .collect(Collectors.toSet());

        if (candidateClusters.isEmpty()) {
            log.debug("findClusterEntirelyOnSentinel", "did not find any qualified cluster",
                    "sentinelId", sentinelId);
            return Optional.empty();
        } else {
            Optional<String> candidate = candidateClusters.stream().findFirst();
            log.debug("findClusterEntirelyOnSentinel", "find candidate cluster",
                    "sentinelId", sentinelId, "cluster", candidate);
            return candidate;
        }
    }

    /**
     * Check if the given sentinel has exactly 1 OK record in the given cell in AppRegistry.
     */
    public boolean isSentinelOK(@Nonnull RegistryReader registryReader,
                                @Nonnull String sentinelId,
                                @Nonnull String cellId,
                                @Nonnull EventData eventData) throws ApplicationRegistryException {
        BasicFilter filter = new BasicFilter(ChainsConstants.SENTINEL_APP_NAME, cellId, ApplicationStatus.OK, sentinelId);
        Collection<Registration> registrations = registryReader.query(filter, eventData);

        if (registrations.size() != 1 || !registrations.iterator().next().getInstanceId().equals(sentinelId)) {
            log.error("isSentinelOK", "sentinel instanceId doesn't match to an OK registration",
                    "sentinelId", sentinelId, "registrations", registrations);
            return false;
        }

        return true;
    }

    private static Set<String> getServerSentinels(@Nonnull ChainConfiguration chainConfig) {
        return chainConfig.sentinelServerConfiguration().getSentinelInstances();
    }

    private static Set<String> getClientSentinels(@Nonnull ChainConfiguration chainConfig) {
        return chainConfig.sentinelConfiguration().getQuorum()
                .map(SentinelQuorum::getInstanceIds)
                .orElse(Collections.emptySet());
    }
}
