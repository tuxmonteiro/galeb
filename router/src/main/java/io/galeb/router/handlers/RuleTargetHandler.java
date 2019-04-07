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

import io.galeb.core.entity.Pool;
import io.galeb.core.entity.Rule;
import io.galeb.core.entity.RuleOrdered;
import io.galeb.core.entity.VirtualHost;
import io.galeb.router.ResponseCodeOnError;
import io.undertow.server.HttpHandler;
import io.undertow.server.HttpServerExchange;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.util.Assert;

import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

public class RuleTargetHandler implements HttpHandler {

    public static final String RULE_ORDER  = "order";
    public static final String RULE_MATCH  = "match";

    private final Logger logger = LoggerFactory.getLogger(this.getClass());

    private final AtomicBoolean firstLoad = new AtomicBoolean(true);
    private final VirtualHost virtualHost;
    private final PathGlobHandler pathGlobHandler;
    private PoolHandler poolHandler = null;

    public RuleTargetHandler(final VirtualHost virtualHost) {
        this.virtualHost = virtualHost;
        Assert.notNull(virtualHost, "[ Virtualhost NOT FOUND ]");
        this.pathGlobHandler = new PathGlobHandler().setDefaultHandler(loadRulesHandler());
    }

    @Override
    public void handleRequest(HttpServerExchange exchange) throws Exception {
        if (poolHandler != null) {
            poolHandler.handleRequest(exchange);
        } else {
            pathGlobHandler.handleRequest(exchange);
        }
    }

    public PoolHandler getPoolHandler() {
        return poolHandler;
    }

    public PathGlobHandler getPathGlobHandler() {
        return pathGlobHandler;
    }

    private HttpHandler loadRulesHandler() {
        return new HttpHandler() {

            @Override
            public synchronized void handleRequest(HttpServerExchange exchange) throws Exception {
                if (pathGlobHandler.getPaths().isEmpty()) {
                    loadRules();
                }
                if (poolHandler != null) {
                    poolHandler.handleRequest(exchange);
                    return;
                }
                if (!pathGlobHandler.getPaths().isEmpty()) {
                    if (firstLoad.getAndSet(false)) {
                        pathGlobHandler.handleRequest(exchange);
                    } else {
                        ResponseCodeOnError.RULE_PATH_NOT_FOUND.getHandler().handleRequest(exchange);
                    }
                } else {
                    ResponseCodeOnError.RULES_EMPTY.getHandler().handleRequest(exchange);
                }
            }

            private void loadRules() {
                final Rule ruleSlashOnly;
                final Set<RuleOrdered> rulesordered = virtualHost.getVirtualhostgroup().getRulesordered();
                if (rulesordered.size() == 1 &&
                        (ruleSlashOnly = rulesordered.stream().map(RuleOrdered::getRule).findAny().orElse(null)) != null &&
                        "/".equals(ruleSlashOnly.getMatching())) {
                    poolHandler = new PoolHandler(ruleSlashOnly.getPools().stream().findAny().orElse(null));
                    return;
                }

                rulesordered.forEach(ruleOrdered -> {
                    Rule rule = ruleOrdered.getRule();
                    Integer order = ruleOrdered.getOrder();
                    Pool pool = rule.getPools().stream().findAny().orElse(null);
                    String path = rule.getMatching();
                    if (path != null) {
                        logger.info("[" + virtualHost.getName() + "] adding Rule " + rule.getName() + " [order:" + order + "]");
                        final PoolHandler poolHandler = new PoolHandler(pool);
                        pathGlobHandler.addPath(path, order, poolHandler);
                    } else {
                        logger.warn("[" + virtualHost.getName() + "] Rule " + rule.getName() + " ignored. properties.match IS NULL");
                    }
                });
            }
        };
    }

}
