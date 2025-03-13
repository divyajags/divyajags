{\rtf1\ansi\ansicpg1252\cocoartf2761
\cocoatextscaling0\cocoaplatform0{\fonttbl\f0\fswiss\fcharset0 Helvetica;}
{\colortbl;\red255\green255\blue255;}
{\*\expandedcolortbl;;}
\margl1440\margr1440\vieww11520\viewh8400\viewkind0
\pard\tx720\tx1440\tx2160\tx2880\tx3600\tx4320\tx5040\tx5760\tx6480\tx7200\tx7920\tx8640\pardirnatural\partightenfactor0

\f0\fs24 \cf0 package s3.moria.probe.service\
\
import s3.anomalystore.client.AnomalyStoreClient\
import s3.anomalystore.client.AnomalyStoreQuery\
import s3.anomalystore.common.Anomaly\
import s3.anomalystore.common.AnomalyEntityType\
import s3.anomalystore.common.AnomalyId\
import s3.anomalystore.common.AnomalyType\
import s3.anomalystore.common.Entity\
import s3.commons.log.S3Logger\
import s3.keymap.kotlin.debug\
import s3.keymap.kotlin.message\
import s3.moria.common.Probe\
import s3.moria.probe.config.ProbeConfiguration\
import s3.querylogbuilder.EventData\
import java.util.concurrent.CompletableFuture\
import java.util.concurrent.ExecutionException\
import java.util.concurrent.TimeUnit\
import java.util.concurrent.TimeoutException\
\
class ProbeWorker(\
    val probe: Probe,\
    val probeConfiguration: ProbeConfiguration,\
    val allowedAnomalyTypes: Collection<AnomalyEntityType>,\
    val anomalyStoreClient: AnomalyStoreClient,\
    val stopList: ReadOnlyProbeStopList,\
    val anomalyStoreTimeout: Long\
) \{\
    companion object \{\
        private val log = S3Logger()\
        internal const val metricFindAnomaliesRuntime = "findAnomalies"\
        internal const val metricExceptionTimeout = "exception.timeout"\
        internal const val metricExceptionOther = "exception.other"\
        internal const val metricASGetSuccess = "get.success"\
        internal const val metricASWriteSuccess = "write.success"\
        internal const val ANOMALY_STORE_DEFAULT_TIMEOUT_SEC = 60L\
        internal const val ANOMALY_DROPPED_BY_TYPE = "%s.dropped"\
    \}\
\
    constructor(\
        probe: Probe,\
        probeConfiguration: ProbeConfiguration,\
        allowedAnomalyTypes: Collection<AnomalyEntityType>,\
        anomalyStoreClient: AnomalyStoreClient,\
        stopList: ReadOnlyProbeStopList\
    ) : this(\
        probe,\
        probeConfiguration,\
        allowedAnomalyTypes,\
        anomalyStoreClient,\
        stopList,\
        ANOMALY_STORE_DEFAULT_TIMEOUT_SEC\
    )\
\
    fun doWork(metrics: EventData) \{\
        metrics.startTimer(metricFindAnomaliesRuntime)\
        val newAnomalies = probe.findAnomalies(metrics)\
            .filter \{ anomaly: Anomaly -> isValidAnomaly(anomaly, metrics) \}\
\
        metrics.endTimer(metricFindAnomaliesRuntime)\
        metrics.setCounter(metricExceptionTimeout, 0)\
        metrics.setCounter(metricExceptionOther, 0)\
        metrics.setCounter(metricASWriteSuccess, 0)\
        metrics.setCounter(metricASGetSuccess, 0)\
        try \{\
            val oldAnomalies = getExistingAnomalies(metrics)[anomalyStoreTimeout, TimeUnit.SECONDS]\
            val anomaliesToUpdate = prioritizeAnomaliesToUpdate(oldAnomalies, newAnomalies, metrics)\
            if (probeConfiguration.isInLogOnlyMode) \{ // At INFO level, we just provide size\
                var oldAnomaliesLogKey = "#oldAnomalies"\
                var oldAnomaliesLogValues = oldAnomalies.size.toString()\
                var anomaliesToUpdateLogKey = "#anomaliesToUpdate"\
                var anomaliesToUpdateLogValue = anomaliesToUpdate.size.toString()\
                // At DEBUG level, we dump everything.\
                if (log.isDebugEnabled) \{\
                    oldAnomaliesLogKey = "oldAnomalies"\
                    oldAnomaliesLogValues = oldAnomalies.toString()\
                    anomaliesToUpdateLogKey = "anomaliesToUpdate"\
                    anomaliesToUpdateLogValue = anomaliesToUpdate.toString()\
                \}\
                // Only one call at INFO level - if DEBUG level is enabled, the p\
                log.info(\
                    "doWork", "logOnlyMode is enabled: no modification made to the anomaly store",\
                    "probe", probe, "configuration", probeConfiguration,\
                    oldAnomaliesLogKey, oldAnomaliesLogValues,\
                    anomaliesToUpdateLogKey, anomaliesToUpdateLogValue\
                )\
            \} else \{\
                writeToAnomalyStore(anomaliesToUpdate, metrics)[anomalyStoreTimeout, TimeUnit.SECONDS]\
                deleteExpiredAnomalies(oldAnomalies, anomaliesToUpdate, metrics)[anomalyStoreTimeout, TimeUnit.SECONDS]\
            \}\
        \} catch (e: InterruptedException) \{\
            Thread.currentThread().interrupt()\
            @Suppress("TooGenericExceptionThrown")\
            throw RuntimeException(e)\
        \} catch (e: ExecutionException) \{\
            log.error("doWork", "Failed to talk to the anomaly store", "Exception", e)\
            metrics.setCounter(metricExceptionOther, 1)\
        \} catch (e: TimeoutException) \{\
            log.error("doWork", "Time out talking to the anomaly store", "Exception", e)\
            metrics.setCounter(metricExceptionTimeout, 1)\
        \}\
    \}\
\
    /**\
     * Returns registered set of anomalies that current probe is allowed to emit.\
     */\
    fun getProbeAnomalyTypes() = allowedAnomalyTypes\
\
    /**\
     * Determines whether the anomaly is a valid anomaly for the worker, meaning that:\
     *\
     * 1. The entity of the anomaly should not be in the [NamedBlacklist]\
     * 2. The [AnomalyEntityType] of the anomaly should be allowed\
     */\
    private fun isValidAnomaly(anomaly: Anomaly, metrics: EventData): Boolean \{\
        val isStoplisted = isInStoplist(anomaly)\
\
        metrics.incCounter("anomaly.blacklisted", if (isStoplisted) 1 else 0)\
\
        var isValid = !(isStoplisted)\
\
        val type = AnomalyEntityType.typeOf(anomaly)\
        if (!allowedAnomalyTypes.contains(type)) \{\
            log.error(\
                "run", "Detecting an anomaly type which is not in the allowed list",\
                "Type", type\
            )\
            metrics.incCounter("anomaly.type.illegal", 1)\
            isValid = false\
        \}\
        return isValid\
    \}\
\
    private fun isInStoplist(anomaly: Anomaly): Boolean \{\
        val isStoplisted = stopList.contains(anomaly.entity)\
        return isStoplisted.also \{\
            if (it) \{\
                log.debug \{\
                    message(\
                        "isValidAnomaly",\
                        "Skipping anomalies against $\{anomaly.entity\} due to it being in the stoplist",\
                        "entity" to anomaly.entity\
                    )\
                \}\
            \}\
        \}\
    \}\
\
    /**\
     * Starts an async operation to get all the current existing anomalies from the anomaly store.\
     */\
    private fun getExistingAnomalies(metrics: EventData): CompletableFuture<Collection<Anomaly>> \{\
        // construct all the get queries and fuse them into one completable future\
        val getFuture = allowedAnomalyTypes\
            .map \{ registrationType: AnomalyEntityType ->\
                val query = AnomalyStoreQuery.Builder(registrationType.entityType())\
                    .anomalyType(registrationType.anomalyType())\
                    .filterExpired(false)\
                    .build()\
                anomalyStoreClient[query, metrics]\
            \}\
\
        @Suppress("SpreadOperator")\
        val allFutures = CompletableFuture\
            .allOf(*getFuture.toTypedArray())\
            .thenApply \{ getFuture.flatMap \{ it.join() \} \}\
            .thenApply \{ anomalies: Collection<Anomaly> ->\
                metrics.setCounter(metricASGetSuccess, 1)\
                logWorstOffenders(anomalies, metrics)\
                anomalies\
            \}\
\
        return allFutures\
    \}\
\
    private fun logWorstOffenders(anomalies: Collection<Anomaly>, metrics: EventData) \{\
        val worstOffenders: MutableMap<AnomalyEntityType, Anomaly> = HashMap()\
        anomalies\
            .filter \{ anomaly -> !anomaly.isExpired \}\
            .forEach \{ anomaly ->\
                val type = AnomalyEntityType.typeOf(anomaly)\
                worstOffenders.putIfAbsent(type, anomaly)\
                if (worstOffenders[type]!!.age < anomaly.duration) \{\
                    worstOffenders[type] = anomaly\
                \}\
            \}\
\
        allowedAnomalyTypes\
            .forEach \{ type: AnomalyEntityType ->\
                if (worstOffenders.containsKey(type)) \{\
                    val worstOffender = worstOffenders[type]\
                    log.info(\
                        "logWorstOffenders", "Logging the worst offender",\
                        "Type", type, "Anomaly", worstOffender\
                    )\
                    metrics.setCounter(type.toMetricsPrefix() + ".max.duration", worstOffender!!.age)\
                \} else \{\
                    metrics.setCounter(type.toMetricsPrefix() + ".max.duration", 0)\
                \}\
            \}\
    \}\
\
    /**\
     * Write the anomalies to the underlying anomaly store\
     */\
    private fun writeToAnomalyStore(anomalies: Collection<Anomaly>, metrics: EventData) = anomalyStoreClient\
        .update(anomalies, metrics)\
        .thenApply \{\
            metrics.setCounter(metricASWriteSuccess, 1)\
            null\
        \}\
\
    /**\
     * Prioritize the new anomalies to update. Per anomaly type:\
     *\
     * 1. If we still have quota in anomaly store, there is no need for prioritization. We will simply return\
     * all new anomalies;\
     * 2. Otherwise, we will try to return more important anomalies while making sure that\
     * we are not breaching the quota\
     */\
    private fun prioritizeAnomaliesToUpdate(\
        oldAnomalies: Collection<Anomaly>,\
        newAnomalies: Collection<Anomaly>,\
        metrics: EventData\
    ): Collection<Anomaly> \{\
        metrics.setCounter("old.anomalies.total", oldAnomalies.size.toLong())\
        metrics.setCounter("new.anomalies.total", newAnomalies.size.toLong())\
        // log count for each anomaly entity type\
        allowedAnomalyTypes.forEach \{ type -> metrics.setCounter(type.toMetricsPrefix() + ".found", 0) \}\
        newAnomalies\
            .map \{ anomaly: Anomaly -> AnomalyEntityType.typeOf(anomaly) \}\
            .forEach \{ type: AnomalyEntityType -> metrics.incCounter(type.toMetricsPrefix() + ".found", 1) \}\
\
        val oldTypeToAnomaliesMap = mapAnomaliesToTypes(oldAnomalies)\
        val newTypeToAnomaliesMap = mapAnomaliesToTypes(newAnomalies)\
        val anomaliesToUpdate: List<Anomaly> = newTypeToAnomaliesMap\
            .flatMap \{ entry ->\
                prioritizeAnomaliesForType(\
                    oldTypeToAnomaliesMap.getOrDefault(entry.key, emptyList()),\
                    entry.value,\
                    entry.key,\
                    metrics\
                )\
            \}\
\
        metrics.setCounter("anomalies.dropped", newAnomalies.size - anomaliesToUpdate.size.toLong())\
        return anomaliesToUpdate\
    \}\
\
    private fun mapAnomaliesToTypes(oldAnomalies: Collection<Anomaly>) =\
        oldAnomalies.groupBy \{ obj: Anomaly -> obj.anomalyType \}\
\
    /**\
     * Return no more anomalies than we have quota for (according to anomaly type). If we need to truncate the anomaly\
     * set, prioritize as follows:\
     * 1. old anomalies that are still active, by hit count\
     * 2. new anomalies by magnitude, then by entity name\
     *\
     * Keeping the oldest anomalies instead of just the ones with the greatest magnitude lets us reliably alarm on them\
     * which helps us find out about problems the control plane can't fix\
     */\
    private fun prioritizeAnomaliesForType(\
        oldAnomalies: Collection<Anomaly>,\
        newAnomalies: Collection<Anomaly>,\
        type: AnomalyType,\
        metrics: EventData\
    ): Collection<Anomaly> \{\
        /*\
            We only want to return as many anomalies as we have quota for. We have to reduce our quota by the number of\
            old anomalies that exist but aren't in the current set (old anomalies that are still current will be handled\
            naturally through prioritization)\
        */\
        val newAnomalyEntities = newAnomalies.map \{ obj: Anomaly -> obj.entityName \}.toSet()\
        val activeOldAnomalies = oldAnomalies\
            .filter \{ anomaly: Anomaly -> !(anomaly.isExpired || newAnomalyEntities.contains(anomaly.entityName)) \}\
            .count()\
        val refreshedAnomalies = oldAnomalies\
            .filter \{ anomaly: Anomaly -> newAnomalyEntities.contains(anomaly.entityName) \}\
            .count()\
        val quota = Math.max(0, probeConfiguration.maximumAnomaliesPerAnomalyType - activeOldAnomalies)\
        metrics.addNameValuePair(type.name.lowercase() + ".write.quota", quota.toString())\
        // Currently new.anomalies.total metric doesn't represent actual new anomalies generated different\
        // than old anomalies, so adding below metric to represent actual new anomalies generated\
        metrics.setCounter(\
            "diff.between.old.new.anomalies",\
            Math.max(0, newAnomalies.size - refreshedAnomalies).toLong()\
        )\
\
        val prioritizedAnomalies = if (quota >= newAnomalies.size) \{\
            // no need to prioritize the new anomalies since we have quota for all of them\
            newAnomalies\
        \} else \{\
            log.warn(\
                "prioritizeAnomaliesForType", "found more anomalies than there is quota to store",\
                "probe", probeConfiguration.recordId,\
                "anomaly type", type,\
                "anomalies found", newAnomalies.size,\
                "quota", probeConfiguration.maximumAnomaliesPerAnomalyType,\
                "old anomalies not yet expired", activeOldAnomalies,\
                "old anomalies found again", refreshedAnomalies\
            )\
\
            // Map by Entity (entityName:entityType) to properly handle cases where an entity's type has changed across\
            // probe runs. Otherwise, we would be unable to map two different anomalies for with the same entity name\
            // and fail here.\
            val oldAnomaliesByAnomalyId: Map<Entity, Anomaly> = oldAnomalies.associateBy \{ anomaly -> anomaly.entity \}\
\
            // we first sort by hit count rather than first seen time because within a round first seen time is just the\
            // time the anomaly happened to be instantiated and we don't want that to override eg magnitude\
            // we need to check presence in oldAnomalies as well because hit count hasn't been incremented yet\
            newAnomalies\
                .sortedWith(\
                    compareBy(\
                        \{ a: Anomaly -> oldAnomaliesByAnomalyId.containsKey(a.entity) \},\
                        \{ a: Anomaly -> oldAnomaliesByAnomalyId.getOrDefault(a.entity, a).hitcount \},\
                        \{ obj: Anomaly -> obj.magnitude \}\
                    )\
                )\
                .asReversed()\
                .take(quota)\
                .toSet()\
        \}\
        metrics.setCounter(\
            ANOMALY_DROPPED_BY_TYPE.format(type.name.lowercase()),\
            newAnomalies.size - prioritizedAnomalies.size.toLong()\
        )\
        return prioritizedAnomalies\
    \}\
\
    /**\
     * Asynchronously delete the expired anomalies from the given collection.\
     * Does not delete anomalies that were expired but have been refreshed.\
     */\
    private fun deleteExpiredAnomalies(\
        anomalies: Collection<Anomaly>,\
        newAnomalies: Collection<Anomaly>,\
        metrics: EventData\
    ): CompletableFuture<Void?> \{\
        metrics.setCounter("gc.success", 0)\
        val newAnomalyIds = newAnomalies.map \{ anomaly: Anomaly -> AnomalyId(anomaly) \}.toSet()\
        return anomalyStoreClient.delete(\
            anomalies\
                .filter \{ anomaly: Anomaly ->\
                    anomaly.isExpired &&\
                        !newAnomalyIds.contains(AnomalyId(anomaly))\
                \},\
            metrics\
        ).thenApply \{\
            metrics.setCounter("gc.success", 1)\
            null\
        \}\
    \}\
\}}