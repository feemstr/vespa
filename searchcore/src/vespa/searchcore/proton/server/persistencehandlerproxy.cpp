// Copyright 2016 Yahoo Inc. Licensed under the terms of the Apache 2.0 license. See LICENSE in the project root.

#include <vespa/fastos/fastos.h>
#include <vespa/log/log.h>
LOG_SETUP(".proton.server.persistencehandlerproxy");
#include "documentretriever.h"
#include "persistencehandlerproxy.h"
#include <vespa/persistence/spi/result.h>

using storage::spi::Bucket;
using storage::spi::Timestamp;

namespace proton {

PersistenceHandlerProxy::PersistenceHandlerProxy(const DocumentDB::SP &documentDB)
    : _documentDB(documentDB),
      _feedHandler(_documentDB->getFeedHandler()),
      _bucketHandler(_documentDB->getBucketHandler()),
      _clusterStateHandler(_documentDB->getClusterStateHandler())
{
    _documentDB->retain();
}


PersistenceHandlerProxy::~PersistenceHandlerProxy()
{
    _documentDB->release();
}


void
PersistenceHandlerProxy::initialize()
{
    _documentDB->waitForOnlineState();
}


void
PersistenceHandlerProxy::handlePut(FeedToken token,
                                   const Bucket &bucket,
                                   Timestamp timestamp,
                                   const document::Document::SP &doc)
{
    FeedOperation::UP op(new PutOperation(bucket.getBucketId().stripUnused(),
                                          timestamp, doc));
    _feedHandler.handleOperation(token, std::move(op));
}


void
PersistenceHandlerProxy::handleUpdate(FeedToken token,
                                      const Bucket &bucket,
                                      Timestamp timestamp,
                                      const document::DocumentUpdate::SP &upd)
{
    FeedOperation::UP op(new UpdateOperation(bucket.getBucketId().
                                             stripUnused(),
                                             timestamp, upd));
    _feedHandler.handleOperation(token, std::move(op));
}


void
PersistenceHandlerProxy::handleRemove(FeedToken token,
                                      const Bucket &bucket,
                                      Timestamp timestamp,
                                      const document::DocumentId &id)
{
    FeedOperation::UP op(new RemoveOperation(bucket.getBucketId().
                                             stripUnused(),
                                             timestamp, id));
    _feedHandler.handleOperation(token, std::move(op));
}


void
PersistenceHandlerProxy::handleListBuckets(IBucketIdListResultHandler &resultHandler)
{
    _bucketHandler.handleListBuckets(resultHandler);
}


void
PersistenceHandlerProxy::handleSetClusterState(const storage::spi::ClusterState &calc,
                                               IGenericResultHandler &resultHandler)
{
    _clusterStateHandler.handleSetClusterState(calc, resultHandler);
}


void
PersistenceHandlerProxy::handleSetActiveState(
        const storage::spi::Bucket &bucket,
        storage::spi::BucketInfo::ActiveState newState,
        IGenericResultHandler &resultHandler)
{
    _bucketHandler.handleSetCurrentState(bucket.getBucketId().stripUnused(),
                                         newState, resultHandler);
}


void
PersistenceHandlerProxy::handleGetBucketInfo(const Bucket &bucket,
                                             IBucketInfoResultHandler &resultHandler)
{
    _bucketHandler.handleGetBucketInfo(bucket, resultHandler);
}


void
PersistenceHandlerProxy::handleCreateBucket(FeedToken token,
                                            const Bucket &bucket)
{
    FeedOperation::UP op(new CreateBucketOperation(bucket.getBucketId().
                                                   stripUnused()));
    _feedHandler.handleOperation(token, std::move(op));
}


void
PersistenceHandlerProxy::handleDeleteBucket(FeedToken token,
                                            const Bucket &bucket)
{
    FeedOperation::UP op(new DeleteBucketOperation(bucket.getBucketId().
                                                   stripUnused()));
    _feedHandler.handleOperation(token, std::move(op));
}


void
PersistenceHandlerProxy::handleGetModifiedBuckets(IBucketIdListResultHandler &resultHandler)
{
    _clusterStateHandler.handleGetModifiedBuckets(resultHandler);
}


void
PersistenceHandlerProxy::handleSplit(FeedToken token,
                                     const Bucket &source,
                                     const Bucket &target1,
                                     const Bucket &target2)
{
    FeedOperation::UP op(new SplitBucketOperation(source.getBucketId().
                                                  stripUnused(),
                                                  target1.getBucketId().
                                                  stripUnused(),
                                                  target2.getBucketId().
                                                  stripUnused()));
    _feedHandler.handleOperation(token, std::move(op));
}


void
PersistenceHandlerProxy::handleJoin(FeedToken token,
                                    const Bucket &source1,
                                    const Bucket &source2,
                                    const Bucket &target)
{
    FeedOperation::UP op(new JoinBucketsOperation(source1.getBucketId().
                                                  stripUnused(),
                                                  source2.getBucketId().
                                                  stripUnused(),
                                                  target.getBucketId().
                                                  stripUnused()));
    _feedHandler.handleOperation(token, std::move(op));
}


IPersistenceHandler::RetrieversSP
PersistenceHandlerProxy::getDocumentRetrievers()
{
    return _documentDB->getDocumentRetrievers();
}

BucketGuard::UP
PersistenceHandlerProxy::lockBucket(const storage::spi::Bucket &bucket)
{
    return _documentDB->lockBucket(bucket.getBucketId().stripUnused());
}

void
PersistenceHandlerProxy::commitAndWait()
{
    _documentDB->commitAndWait();
}

void
PersistenceHandlerProxy::handleListActiveBuckets(
        IBucketIdListResultHandler &resultHandler)
{
    _bucketHandler.handleListActiveBuckets(resultHandler);
}


void
PersistenceHandlerProxy::handlePopulateActiveBuckets(
        document::BucketId::List &buckets,
        IGenericResultHandler &resultHandler)
{
    _bucketHandler.handlePopulateActiveBuckets(buckets, resultHandler);
}


} // namespace proton
