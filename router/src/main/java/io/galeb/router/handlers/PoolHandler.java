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

package io.galeb.router.handlers;

import io.galeb.core.enums.SystemEnv;
import io.galeb.core.entity.BalancePolicy;
import io.galeb.core.entity.Pool;
import io.galeb.router.client.ExtendedLoadBalancingProxyClient;
import io.galeb.router.client.hostselectors.HostSelector;
import io.galeb.router.client.hostselectors.HostSelectorLookup;
import io.galeb.router.ResponseCodeOnError;
import io.galeb.router.client.hostselectors.RoundRobinHostSelector;
import io.undertow.client.UndertowClient;
import io.undertow.server.HttpHandler;
import io.undertow.server.HttpServerExchange;
import io.undertow.server.handlers.proxy.ExclusivityChecker;
import io.undertow.server.handlers.proxy.ProxyHandler;
import io.undertow.util.AttachmentKey;
import io.undertow.util.Headers;
import io.undertow.util.HttpString;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;

public class PoolHandler implements HttpHandler {

    public static final AttachmentKey<String> POOL_NAME = AttachmentKey.create(String.class);

    private static final String CHECK_RULE_HEADER  = "X-Check-Pool";
    private static final String X_POOL_NAME_HEADER = "X-Pool-Name";

    private static final Logger LOGGER = LogManager.getLogger(PoolHandler.class);

    private final int maxRequestTime = Integer.parseInt(SystemEnv.POOL_MAX_REQUEST_TIME.getValue());
    private final boolean reuseXForwarded = Boolean.parseBoolean(SystemEnv.REUSE_XFORWARDED.getValue());
    private final boolean rewriteHostHeader = Boolean.parseBoolean(SystemEnv.REWRITE_HOST_HEADER.getValue());
    private final RequestIDHandler requestIDHandler = new RequestIDHandler();
    private final HttpHandler defaultHandler;

    private ProxyHandler proxyHandler = null;
    private ExtendedLoadBalancingProxyClient proxyClient;

    private final Pool pool;

    public PoolHandler(final Pool pool) {
        this.pool = pool;
        this.defaultHandler = buildPoolHandler();
    }

    @Override
    public void handleRequest(HttpServerExchange exchange) throws Exception {
        exchange.putAttachment(POOL_NAME, pool.getName());
        if (exchange.getRequestHeaders().contains(CHECK_RULE_HEADER)) {
            healthcheckPoolHandler().handleRequest(exchange);
            return;
        }
        if (proxyClient != null && proxyClient.isHostsEmpty()) {
            ResponseCodeOnError.HOSTS_EMPTY.getHandler().handleRequest(exchange);
            return;
        }
        requestIDHandler.setNext(proxyHandler != null ? proxyHandler : defaultHandler).handleRequest(exchange);
    }

    public Pool getPool() {
        return pool;
    }

    public ProxyHandler getProxyHandler() {
        return proxyHandler;
    }

    private synchronized HttpHandler buildPoolHandler() {
        return exchange -> {
            if (pool != null) {
                LOGGER.info("creating pool " + pool.getName());
                proxyClient = getProxyClient();
                addTargets(proxyClient);
                proxyHandler = new ProxyHandler(proxyClient, maxRequestTime, badGatewayHandler(), rewriteHostHeader, reuseXForwarded);
                proxyHandler.handleRequest(exchange);
                return;
            }
            ResponseCodeOnError.POOL_NOT_DEFINED.getHandler().handleRequest(exchange);
        };
    }

    private ExtendedLoadBalancingProxyClient getProxyClient() {
        final HostSelector hostSelector = defineHostSelector();
        LOGGER.info("[Pool " + pool.getName() + "] HostSelector: " + hostSelector.getClass().getSimpleName());

        final ExclusivityChecker exclusivityChecker = exclusivityCheckerExchange -> exclusivityCheckerExchange.getRequestHeaders().contains(Headers.UPGRADE);
        return new ExtendedLoadBalancingProxyClient(UndertowClient.getInstance(), exclusivityChecker, hostSelector)
                        .setTtl(Integer.parseInt(SystemEnv.POOL_CONN_TTL.getValue()))
                        .setConnectionsPerThread(getConnPerThread())
                        .setSoftMaxConnectionsPerThread(Integer.parseInt(SystemEnv.POOL_SOFTMAXCONN.getValue()));
    }

    private int getConnPerThread() {
        final int poolMaxConn = Integer.parseInt(SystemEnv.POOL_MAXCONN.getValue());
        final int poolSize = pool.getPoolSize().intValue();
        // TODO: connPerThread = Math.round((float) connPerThread / discoveryMembersSize)
        return (poolSize > 0 ? poolSize : poolMaxConn) / Integer.parseInt(SystemEnv.IO_THREADS.getValue());
    }

    private HttpHandler badGatewayHandler() {
        return exchange -> exchange.setStatusCode(502);
    }

    private HostSelector defineHostSelector() {
        BalancePolicy hostSelectorName = pool.getBalancepolicy();
        if (hostSelectorName != null) {
            return HostSelectorLookup.getHostSelector(hostSelectorName.getName());
        }
        return new RoundRobinHostSelector();
    }

    private void addTargets(final ExtendedLoadBalancingProxyClient proxyClient) {
        pool.getTargets().forEach(target -> {
            String value = target.getName();
            URI uri = URI.create(target.getName());
            proxyClient.addHost(uri);
            LOGGER.info("[pool:" + pool.getName() + "] added Target " + value);
        });
    }

    private HttpHandler healthcheckPoolHandler() {
        return exchange -> {
            LOGGER.warn("detected header " + CHECK_RULE_HEADER);
            exchange.getResponseHeaders().put(Headers.CONTENT_TYPE, "text/plain");
            exchange.getResponseHeaders().put(Headers.SERVER, "GALEB");
            exchange.getResponseHeaders().put(HttpString.tryFromString(X_POOL_NAME_HEADER), pool.getName());
            exchange.getResponseSender().send("POOL_REACHABLE");
        };
    }
}
