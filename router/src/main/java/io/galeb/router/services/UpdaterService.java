/*
 * Copyright (c) 2014-2017 Globo.com - ATeam
 * All rights reserved.
 *
 * This source is subject to the Apache License, Version 2.0.
 * Please see the LICENSE file for more information.
 *
 * Authors: See AUTHORS file
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.galeb.router.services;

import com.google.common.collect.Sets;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import io.galeb.core.entity.VirtualHost;
import io.galeb.core.enums.SystemEnv;
import io.galeb.router.VirtualHostsNotExpired;
import io.galeb.router.client.ExtendedProxyClient;
import io.galeb.router.configurations.ManagerClientCacheConfiguration.ManagerClientCache;
import io.galeb.router.handlers.PathGlobHandler;
import io.galeb.router.handlers.PoolHandler;
import io.galeb.router.handlers.RuleTargetHandler;
import io.galeb.router.sync.HttpClient;
import io.galeb.router.sync.ManagerClient;
import io.undertow.server.HttpHandler;
import io.undertow.server.handlers.IPAddressAccessControlHandler;
import io.undertow.server.handlers.NameVirtualHostHandler;
import io.undertow.server.handlers.proxy.ProxyHandler;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.concurrent.atomic.AtomicBoolean;

@SuppressWarnings("Duplicates")
@Service
public class UpdaterService {

    private static final Logger LOGGER = LogManager.getLogger(UpdaterService.class);

    public static final long   WAIT_TIMEOUT  = Long.parseLong(Optional.ofNullable(
        System.getenv("WAIT_TIMEOUT")).orElse("20000")); // ms

    // @formatter:off
    private final Gson gson = new GsonBuilder().serializeNulls()
                                               .setLenient()
                                               .setDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'")
                                               .create();
    // @formatter:on
    private final String envName = SystemEnv.ENVIRONMENT_NAME.getValue();
    private final AtomicBoolean executeSync = new AtomicBoolean(false);
    private final ManagerClient managerClient;
    private final ManagerClientCache cache;
    private final NameVirtualHostHandler nameVirtualHostHandler;

    private int count = 0;

    @Autowired
    public UpdaterService(final ManagerClient managerClient,
                          final ManagerClientCache cache,
                          final NameVirtualHostHandler nameVirtualHostHandler) {
        this.managerClient = managerClient;
        this.cache = cache;
        this.nameVirtualHostHandler = nameVirtualHostHandler;
    }

    @Scheduled(fixedDelay = 5000)
    public void execute() {
        if (executeSync.getAndSet(false)) {
            sync();
        }
    }

    public void sched() {
        executeSync.compareAndSet(false, true);
    }

    public void sync() {
        AtomicBoolean wait = new AtomicBoolean(true);
        final ManagerClient.ResultCallBack resultCallBack = result -> {
            if (result instanceof String && HttpClient.NOT_MODIFIED.equals(result)) {
                LOGGER.info("Environment " + envName + ": " + result);
            } else {
                ManagerClient.Virtualhosts virtualhostsFromManager = result instanceof ManagerClient.Virtualhosts ? (ManagerClient.Virtualhosts) result : null;
                if (virtualhostsFromManager != null) {
                    final List<VirtualHost> virtualhosts = processVirtualhosts(virtualhostsFromManager);
                    String newEtag = virtualhostsFromManager.etag;
                    LOGGER.info("Processing " + virtualhosts.size() + " virtualhost(s): Check update initialized");
                    cleanup(virtualhosts);
                    virtualhosts.forEach(this::updateCache);
                    updateEtagIfNecessary(newEtag);
                    LOGGER.info("Processed " + count + " virtualhost(s): Done");
                } else {
                    LOGGER.error("Virtualhosts Empty. Request problem?");
                }
            }
            count = 0;
            wait.set(false);
        };
        String etag = cache.etag();
        List<VirtualHost> lastCache = new ArrayList<>(cache.values());
        managerClient.register(etag);
        managerClient.getVirtualhosts(envName, etag, resultCallBack);
        // force wait
        long currentWaitTimeOut = System.currentTimeMillis();
        boolean failed = false;
        while (wait.get()) {
            if (currentWaitTimeOut < System.currentTimeMillis() - WAIT_TIMEOUT) {
                failed = true;
                break;
            }
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                failed = true;
                LOGGER.error(e);
            }
        }
        if (failed) {
            rollback(lastCache, etag);
        }
    }

    private void rollback(List<VirtualHost> lastCache, String etag) {
        cleanup(lastCache);
        lastCache.forEach(this::updateCache);
        updateEtagIfNecessary(etag);
    }

    private void updateEtagIfNecessary(String etag) {
        cache.updateEtag(etag);
    }

    private List<VirtualHost> processVirtualhosts(final ManagerClient.Virtualhosts virtualhostsFromManager) {
        return Arrays.asList(virtualhostsFromManager.virtualhosts);
    }

    private void cleanup(final List<VirtualHost> virtualhosts) {
        final Set<String> virtualhostSet = virtualhosts.stream().map(VirtualHost::getName).collect(Collectors.toSet());
        synchronized (cache) {
            Set<String> diff = Sets.difference(cache.getAll(), virtualhostSet);
            diff.forEach(virtualhostName -> {
                expireHandlers(virtualhostName);
                cache.remove(virtualhostName);
                LOGGER.warn("Virtualhost " + virtualhostName + " not exist. Removed.");
            });
        }
        Set<String> diff = Sets.difference(nameVirtualHostHandler.getHosts().keySet(), virtualhostSet);
        diff.forEach(this::expireHandlers);
    }

    private void updateCache(VirtualHost virtualHost) {
        String virtualhostName = virtualHost.getName();
        VirtualHost oldVirtualHost = cache.get(virtualhostName);
        if (oldVirtualHost != null) {
            Long newVersion = virtualHost.getVersion();
            Long currentVersion = oldVirtualHost.getVersion();
            if (currentVersion != null && currentVersion.equals(newVersion)) {
                if (LOGGER.isDebugEnabled()) {
                    LOGGER.debug("Virtualhost " + virtualhostName + " not changed.");
                }
                count++;
                return;
            } else {
                LOGGER.warn("Virtualhost " + virtualhostName + " changed. Updating cache.");
            }
        }
        cache.put(virtualhostName, virtualHost);
        expireHandlers(virtualhostName);
        count++;
    }

    private void expireHandlers(String virtualhostName) {
        if (Arrays.stream(VirtualHostsNotExpired.values()).anyMatch(s -> s.getHost().equals(virtualhostName))) return;
        if (nameVirtualHostHandler.getHosts().containsKey(virtualhostName)) {
            LOGGER.warn("Virtualhost " + virtualhostName + ": Rebuilding handlers.");
            cleanUpNameVirtualHostHandler(virtualhostName);
            nameVirtualHostHandler.removeHost(virtualhostName);
        }
    }

    private void cleanPoolHandler(final PoolHandler poolHandler) {
        final ProxyHandler proxyHandler = poolHandler.getProxyHandler();
        if (proxyHandler != null) {
            final ExtendedProxyClient proxyClient = (ExtendedProxyClient) proxyHandler.getProxyClient();
            proxyClient.removeAllHosts();
        }
    }

    private void cleanUpNameVirtualHostHandler(String virtualhost) {
        final HttpHandler handler = nameVirtualHostHandler.getHosts().get(virtualhost);
        RuleTargetHandler ruleTargetHandler = null;
        if (handler instanceof RuleTargetHandler) {
            ruleTargetHandler = (RuleTargetHandler)handler;
        }
        if (handler instanceof IPAddressAccessControlHandler) {
            ruleTargetHandler = (RuleTargetHandler) ((IPAddressAccessControlHandler) handler).getNext();
        }
        if (ruleTargetHandler != null) {
            final PoolHandler poolHandler = ruleTargetHandler.getPoolHandler();
            if (poolHandler != null) {
                cleanPoolHandler(poolHandler);
            } else {
                cleanUpPathGlobHandler(ruleTargetHandler.getPathGlobHandler());
            }
        }
    }

    private void cleanUpPathGlobHandler(final PathGlobHandler pathGlobHandler) {
        pathGlobHandler.getPaths().forEach((k, poolHandler) -> {
            cleanPoolHandler((PoolHandler) poolHandler);
        });
        pathGlobHandler.clear();
    }

}
