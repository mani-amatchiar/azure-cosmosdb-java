/*
 * The MIT License (MIT)
 * Copyright (c) 2018 Microsoft Corporation
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */
package com.azure.data.cosmos.internal.changefeed.implementation;

import com.azure.data.cosmos.internal.changefeed.CancellationToken;
import com.azure.data.cosmos.internal.changefeed.CancellationTokenSource;
import com.azure.data.cosmos.internal.changefeed.Lease;
import com.azure.data.cosmos.internal.changefeed.LeaseContainer;
import com.azure.data.cosmos.internal.changefeed.LeaseManager;
import com.azure.data.cosmos.internal.changefeed.PartitionController;
import com.azure.data.cosmos.internal.changefeed.PartitionSupervisor;
import com.azure.data.cosmos.internal.changefeed.PartitionSupervisorFactory;
import com.azure.data.cosmos.internal.changefeed.PartitionSynchronizer;
import com.azure.data.cosmos.internal.changefeed.exceptions.PartitionSplitException;
import com.azure.data.cosmos.internal.changefeed.exceptions.TaskCancelledException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;

/**
 * Implementation for {@link PartitionController}.
 */
class PartitionControllerImpl implements PartitionController {
    private final Logger logger = LoggerFactory.getLogger(PartitionControllerImpl.class);
    private final Map<String, WorkerTask> currentlyOwnedPartitions = new ConcurrentHashMap<>();

    private final LeaseContainer leaseContainer;
    private final LeaseManager leaseManager;
    private final PartitionSupervisorFactory partitionSupervisorFactory;
    private final PartitionSynchronizer synchronizer;
    private CancellationTokenSource shutdownCts;

    private final ExecutorService executorService;

    public PartitionControllerImpl(
        LeaseContainer leaseContainer,
        LeaseManager leaseManager,
        PartitionSupervisorFactory partitionSupervisorFactory,
        PartitionSynchronizer synchronizer,
        ExecutorService executorService) {

        this.leaseContainer = leaseContainer;
        this.leaseManager = leaseManager;
        this.partitionSupervisorFactory = partitionSupervisorFactory;
        this.synchronizer = synchronizer;
        this.executorService = executorService;
    }

    @Override
    public Mono<Void> initialize() {
        this.shutdownCts = new CancellationTokenSource();
        return this.loadLeases();
    }

    @Override
    public synchronized Mono<Lease> addOrUpdateLease(final Lease lease) {
        PartitionControllerImpl self = this;

        WorkerTask workerTask = this.currentlyOwnedPartitions.get(lease.getLeaseToken());
        if ( workerTask != null && workerTask.isRunning()) {
            return self.leaseManager.updateProperties(lease)
                .map(updatedLease -> {
                    logger.debug("Partition {}: updated.", updatedLease.getLeaseToken());
                    return updatedLease;
                });
        }

        return self.leaseManager.acquire(lease)
            .defaultIfEmpty(lease)
            .map(updatedLease -> {
                logger.info("Partition {}: acquired.", updatedLease.getLeaseToken());
                PartitionSupervisor supervisor = this.partitionSupervisorFactory.create(updatedLease);
                self.currentlyOwnedPartitions.put(updatedLease.getLeaseToken(), this.processPartition(supervisor, updatedLease));
                return updatedLease;
            })
            .onErrorResume(throwable -> self.removeLease(lease).then(Mono.error(throwable)));
    }

    @Override
    public Mono<Void> shutdown() {
        // TODO: wait for the threads to finish.
        this.shutdownCts.cancel();
//        this.currentlyOwnedPartitions.clear();

        return Mono.empty();
    }

    private Mono<Void> loadLeases() {
        PartitionControllerImpl self = this;
        logger.debug("Starting renew leases assigned to this host on initialize.");

        return this.leaseContainer.getOwnedLeases()
            .flatMap( lease -> {
                logger.info("Acquired lease for PartitionId '{}' on startup.", lease.getLeaseToken());
                return self.addOrUpdateLease(lease);
            }).then();
    }

    private Mono<Void> removeLease(Lease lease) {
            if (this.currentlyOwnedPartitions.get(lease.getLeaseToken()) != null) {
                WorkerTask workerTask = this.currentlyOwnedPartitions.remove(lease.getLeaseToken());

                if (workerTask.isRunning()) {
                    workerTask.interrupt();
                }

                logger.info("Partition {}: released.", lease.getLeaseToken());
            }

            return this.leaseManager.release(lease)
                .onErrorResume(e -> {
                        logger.warn("Partition {}: failed to remove lease.", lease.getLeaseToken(), e);
                        return Mono.empty();
                    }
                ).doOnSuccess(aVoid -> {
                    logger.info("Partition {}: successfully removed lease.", lease.getLeaseToken());
                });
    }

    private WorkerTask processPartition(PartitionSupervisor partitionSupervisor, Lease lease) {
        PartitionControllerImpl self = this;

        CancellationToken cancellationToken = this.shutdownCts.getToken();

        WorkerTask partitionSupervisorTask = new WorkerTask(lease, () -> {
            partitionSupervisor.run(cancellationToken)
                .onErrorResume(throwable -> {
                    if (throwable instanceof PartitionSplitException) {
                        PartitionSplitException ex = (PartitionSplitException) throwable;
                        return self.handleSplit(lease, ex.getLastContinuation());
                    } else if (throwable instanceof TaskCancelledException) {
                        logger.debug("Partition %s: processing canceled.", lease.getLeaseToken());
                    } else {
                        logger.warn("Partition %s: processing failed.", lease.getLeaseToken(), throwable);
                    }

                    return Mono.empty();
                })
                .then(self.removeLease(lease)).subscribe();
        });

        this.executorService.execute(partitionSupervisorTask);

        return partitionSupervisorTask;
    }

    private Mono<Void> handleSplit(Lease lease, String lastContinuationToken) {
        PartitionControllerImpl self = this;

        lease.setContinuationToken(lastContinuationToken);
        return this.synchronizer.splitPartition(lease)
            .flatMap(l -> {
                l.setProperties(lease.getProperties());
                return self.addOrUpdateLease(l);
            }).then(self.leaseManager.delete(lease))
            .onErrorResume(throwable -> {
                logger.warn("Partition %s: failed to split", lease.getLeaseToken(), throwable);
                return  Mono.empty();
            });
    }
}
