// Copyright 2017 Yahoo Holdings. Licensed under the terms of the Apache 2.0 license. See LICENSE in the project root.

#include "bucketmovejob.h"
#include "bucketmovejobv2.h"
#include "heart_beat_job.h"
#include "job_tracked_maintenance_job.h"
#include "lid_space_compaction_job.h"
#include "lid_space_compaction_job_take2.h"
#include "lid_space_compaction_handler.h"
#include "maintenance_jobs_injector.h"
#include "prune_session_cache_job.h"
#include "pruneremoveddocumentsjob.h"
#include "sample_attribute_usage_job.h"
#include <vespa/searchcore/proton/attribute/attribute_config_inspector.h>

using vespalib::system_clock;

namespace proton {

namespace {

IMaintenanceJob::UP
trackJob(IJobTracker::SP tracker, std::shared_ptr<IMaintenanceJob> job)
{
    return std::make_unique<JobTrackedMaintenanceJob>(std::move(tracker), std::move(job));
}

void
injectLidSpaceCompactionJobs(MaintenanceController &controller,
                             const DocumentDBMaintenanceConfig &config,
                             storage::spi::BucketExecutor & bucketExecutor,
                             ILidSpaceCompactionHandler::Vector lscHandlers,
                             IOperationStorer &opStorer,
                             IFrozenBucketHandler &fbHandler,
                             IJobTracker::SP tracker,
                             IDiskMemUsageNotifier &diskMemUsageNotifier,
                             IClusterStateChangedNotifier &clusterStateChangedNotifier,
                             const std::shared_ptr<IBucketStateCalculator> &calc,
                             document::BucketSpace bucketSpace)
{
    for (auto &lidHandler : lscHandlers) {
        std::shared_ptr<IMaintenanceJob> job;
        if (config.getLidSpaceCompactionConfig().useBucketExecutor()) {
            job = lidspace::CompactionJob::create(config.getLidSpaceCompactionConfig(), std::move(lidHandler), opStorer,
                                                  controller.masterThread(), bucketExecutor, diskMemUsageNotifier,
                                                  config.getBlockableJobConfig(), clusterStateChangedNotifier,
                                                  (calc ? calc->nodeRetired() : false), bucketSpace);
        } else {
            job = std::make_shared<LidSpaceCompactionJob>(
                    config.getLidSpaceCompactionConfig(),
                    std::move(lidHandler), opStorer, fbHandler,
                    diskMemUsageNotifier,
                    config.getBlockableJobConfig(),
                    clusterStateChangedNotifier,
                    (calc ? calc->nodeRetired() : false));
        }
        controller.registerJobInMasterThread(trackJob(tracker, std::move(job)));
    }
}

void
injectBucketMoveJob(MaintenanceController &controller,
                    const DocumentDBMaintenanceConfig &config,
                    IFrozenBucketHandler &fbHandler,
                    storage::spi::BucketExecutor & bucketExecutor,
                    bucketdb::IBucketCreateNotifier &bucketCreateNotifier,
                    const vespalib::string &docTypeName,
                    document::BucketSpace bucketSpace,
                    IDocumentMoveHandler &moveHandler,
                    IBucketModifiedHandler &bucketModifiedHandler,
                    IClusterStateChangedNotifier &clusterStateChangedNotifier,
                    IBucketStateChangedNotifier &bucketStateChangedNotifier,
                    const std::shared_ptr<IBucketStateCalculator> &calc,
                    DocumentDBJobTrackers &jobTrackers,
                    IDiskMemUsageNotifier &diskMemUsageNotifier)
{
    std::shared_ptr<IMaintenanceJob> bmj;
    if (config.getBucketMoveConfig().useBucketExecutor()) {
        bmj = BucketMoveJobV2::create(calc, moveHandler, bucketModifiedHandler, controller.masterThread(),
                                      bucketExecutor, controller.getReadySubDB(), controller.getNotReadySubDB(),
                                      bucketCreateNotifier, clusterStateChangedNotifier, bucketStateChangedNotifier,
                                      diskMemUsageNotifier, config.getBlockableJobConfig(), docTypeName, bucketSpace);
    } else {
        bmj = std::make_shared<BucketMoveJob>(calc,
                                              moveHandler,
                                              bucketModifiedHandler,
                                              controller.getReadySubDB(),
                                              controller.getNotReadySubDB(),
                                              fbHandler,
                                              bucketCreateNotifier,
                                              clusterStateChangedNotifier,
                                              bucketStateChangedNotifier,
                                              diskMemUsageNotifier,
                                              config.getBlockableJobConfig(),
                                              docTypeName, bucketSpace);
    }
    controller.registerJobInMasterThread(trackJob(jobTrackers.getBucketMove(), std::move(bmj)));
}

}

void
MaintenanceJobsInjector::injectJobs(MaintenanceController &controller,
                                    const DocumentDBMaintenanceConfig &config,
                                    storage::spi::BucketExecutor & bucketExecutor,
                                    IHeartBeatHandler &hbHandler,
                                    matching::ISessionCachePruner &scPruner,
                                    IOperationStorer &opStorer,
                                    IFrozenBucketHandler &fbHandler,
                                    bucketdb::IBucketCreateNotifier &bucketCreateNotifier,
                                    const vespalib::string &docTypeName,
                                    document::BucketSpace bucketSpace,
                                    IPruneRemovedDocumentsHandler &prdHandler,
                                    IDocumentMoveHandler &moveHandler,
                                    IBucketModifiedHandler &bucketModifiedHandler,
                                    IClusterStateChangedNotifier &clusterStateChangedNotifier,
                                    IBucketStateChangedNotifier &bucketStateChangedNotifier,
                                    const std::shared_ptr<IBucketStateCalculator> &calc,
                                    IDiskMemUsageNotifier &diskMemUsageNotifier,
                                    DocumentDBJobTrackers &jobTrackers,
                                    IAttributeManagerSP readyAttributeManager,
                                    IAttributeManagerSP notReadyAttributeManager,
                                    std::unique_ptr<const AttributeConfigInspector> attribute_config_inspector,
                                    std::shared_ptr<TransientResourceUsageProvider> transient_usage_provider,
                                    AttributeUsageFilter &attributeUsageFilter)
{
    controller.registerJobInMasterThread(std::make_unique<HeartBeatJob>(hbHandler, config.getHeartBeatConfig()));
    controller.registerJobInDefaultPool(std::make_unique<PruneSessionCacheJob>(scPruner, config.getSessionCachePruneInterval()));

    const MaintenanceDocumentSubDB &mRemSubDB(controller.getRemSubDB());
    auto pruneRDjob = std::make_unique<PruneRemovedDocumentsJob>(config.getPruneRemovedDocumentsConfig(), *mRemSubDB.meta_store(),
                                                                 mRemSubDB.sub_db_id(), docTypeName, prdHandler, fbHandler);
    controller.registerJobInMasterThread(trackJob(jobTrackers.getRemovedDocumentsPrune(), std::move(pruneRDjob)));

    if (!config.getLidSpaceCompactionConfig().isDisabled()) {
        ILidSpaceCompactionHandler::Vector lidSpaceCompactionHandlers;
        lidSpaceCompactionHandlers.push_back(std::make_shared<LidSpaceCompactionHandler>(controller.getReadySubDB(), docTypeName));
        lidSpaceCompactionHandlers.push_back(std::make_shared<LidSpaceCompactionHandler>(controller.getRemSubDB(), docTypeName));
        lidSpaceCompactionHandlers.push_back(std::make_shared<LidSpaceCompactionHandler>(controller.getNotReadySubDB(), docTypeName));
        injectLidSpaceCompactionJobs(controller, config, bucketExecutor, std::move(lidSpaceCompactionHandlers),
                                     opStorer, fbHandler, jobTrackers.getLidSpaceCompact(), diskMemUsageNotifier,
                                     clusterStateChangedNotifier, calc, bucketSpace);
    }

    injectBucketMoveJob(controller, config, fbHandler, bucketExecutor, bucketCreateNotifier, docTypeName, bucketSpace,
                        moveHandler, bucketModifiedHandler, clusterStateChangedNotifier, bucketStateChangedNotifier,
                        calc, jobTrackers, diskMemUsageNotifier);

    controller.registerJobInMasterThread(
            std::make_unique<SampleAttributeUsageJob>(readyAttributeManager, notReadyAttributeManager,
                                                      attributeUsageFilter, docTypeName,
                                                      config.getAttributeUsageSampleInterval(),
                                                      std::move(attribute_config_inspector),
                                                      transient_usage_provider));
}

} // namespace proton
