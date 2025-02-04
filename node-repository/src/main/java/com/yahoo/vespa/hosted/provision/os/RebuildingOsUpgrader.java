// Copyright Verizon Media. Licensed under the terms of the Apache 2.0 license. See LICENSE in the project root.
package com.yahoo.vespa.hosted.provision.os;

import com.yahoo.component.Version;
import com.yahoo.vespa.hosted.provision.Node;
import com.yahoo.vespa.hosted.provision.NodeList;
import com.yahoo.vespa.hosted.provision.NodeRepository;
import com.yahoo.vespa.hosted.provision.node.Agent;
import com.yahoo.vespa.hosted.provision.node.filter.NodeListFilter;

import java.time.Instant;
import java.util.Optional;
import java.util.logging.Logger;

/**
 * An upgrader that retires and rebuilds hosts on stale OS versions. Retirement of each host is spread out in time,
 * according to a time budget, to avoid potential service impact of retiring too many hosts close together.
 *
 * Used in cases where performing an OS upgrade requires rebuilding the host, e.g. when upgrading across major versions.
 *
 * @author mpolden
 */
public class RebuildingOsUpgrader extends RetiringOsUpgrader {

    private static final Logger LOG = Logger.getLogger(RebuildingOsUpgrader.class.getName());

    private final int maxRebuilds;

    public RebuildingOsUpgrader(NodeRepository nodeRepository, int maxRebuilds) {
        super(nodeRepository);
        this.maxRebuilds = maxRebuilds;
        if (maxRebuilds < 1) throw new IllegalArgumentException("maxRebuilds must be positive, was " + maxRebuilds);
    }

    @Override
    protected NodeList candidates(Instant instant, OsVersionTarget target, NodeList allNodes) {
        if (allNodes.rebuilding().size() < maxRebuilds) {
            return super.candidates(instant, target, allNodes);
        }
        return NodeList.of();
    }

    @Override
    protected void upgradeNodes(NodeList candidates, Version version, Instant instant) {
        candidates.not().rebuilding()
                  .byIncreasingOsVersion()
                  .first(1)
                  .forEach(node -> rebuild(node, version, instant));
    }

    private void rebuild(Node host, Version target, Instant now) {
        LOG.info("Retiring and rebuilding " + host + ": On stale OS version " +
                 host.status().osVersion().current().map(Version::toFullString).orElse("<unset>") +
                 ", want " + target);
        nodeRepository.nodes().rebuild(host.hostname(), Agent.RebuildingOsUpgrader, now);
        nodeRepository.nodes().upgradeOs(NodeListFilter.from(host), Optional.of(target));
        nodeRepository.osVersions().writeChange((change) -> change.withRetirementAt(now, host.type()));
    }

}
